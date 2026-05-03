package io.aeyer.anchor.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.protocol.ask.AgentEnvelope;
import io.aeyer.anchor.protocol.ask.EvidenceAccess;
import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.protocol.sse.JobEventType;
import io.aeyer.anchor.server.domain.DocumentContext;
import io.aeyer.anchor.server.jobs.AskJob;
import io.aeyer.anchor.server.jobs.JobStore;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepositoryDomain.ChunkSearchHit;
import io.aeyer.anchor.server.sse.JobStreamRegistry;
import io.aeyer.anchor.server.workers.WorkerPools;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Three-agent deliberation orchestrator (SPEC §6.6 / §7.5).
 *
 * Critical: evidence asymmetry is the design. Proposer and synthesiser see the
 * full hierarchy plus the top-K retrieved chunks; critic sees only chapter
 * summaries + doc summary. Giving critic the same evidence as proposer turns it
 * into a paraphrase generator — the asymmetry is what catches macro-vs-local
 * contradictions.
 *
 * Runs on {@link WorkerPools#deliberationPool()}; each LLM call goes through
 * {@link WorkerPools#chatPool()} via {@code submit().get()} — orchestrator
 * threads are cheap and mostly waiting on chat.
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final JobStore jobs;
    private final DocumentRepository documents;
    private final LMStudioClient llm;
    private final WorkerPools pools;
    private final ObjectMapper mapper;
    private final JobStreamRegistry stream;

    @Value("classpath:prompts/ask-proposer.txt") Resource proposerPrompt;
    @Value("classpath:prompts/ask-critic.txt") Resource criticPrompt;
    @Value("classpath:prompts/ask-synthesiser.txt") Resource synthesiserPrompt;

    @Value("${ask.retrieval.top-sections:5}") int topSections;
    @Value("${ask.retrieval.top-chunks-per-section:3}") int topChunksPerSection;
    @Value("${ask.temperatures.proposer:0.3}") double proposerTemp;
    @Value("${ask.temperatures.critic:0.0}") double criticTemp;
    @Value("${ask.temperatures.synthesiser:0.2}") double synthTemp;

    private String proposerTpl;
    private String criticTpl;
    private String synthesiserTpl;

    public AskService(JobStore jobs, DocumentRepository documents,
                      LMStudioClient llm, WorkerPools pools, ObjectMapper mapper,
                      JobStreamRegistry stream) {
        this.jobs = jobs;
        this.documents = documents;
        this.llm = llm;
        this.pools = pools;
        this.mapper = mapper;
        this.stream = stream;
    }

    @PostConstruct
    void loadPrompts() {
        proposerTpl = read(proposerPrompt);
        criticTpl = read(criticPrompt);
        synthesiserTpl = read(synthesiserPrompt);
    }

    /**
     * Submit a fresh deliberation. Returns the {@link AskJob} immediately
     * (with status QUEUED) and runs the deliberation asynchronously on the
     * deliberation pool. Caller polls {@code GET /jobs/{id}} for progress.
     */
    public AskJob startAsk(UUID documentId, String query) {
        if (documents.findAsDomain(documentId).isEmpty()) {
            throw new IngestException("Unknown document: " + documentId);
        }
        AskJob job = new AskJob(UUID.randomUUID(), documentId, query, Instant.now());
        jobs.put(job);
        pools.deliberationPool().submit(() -> runDeliberation(job));
        return job;
    }

    private void runDeliberation(AskJob job) {
        try {
            DocumentContext ctx = documents.findDocumentContextAsDomain(job.documentId())
                    .orElseThrow(() -> new IngestException("Document vanished mid-deliberation"));
            float[] queryEmbedding = embedQuery(job.query());
            List<ChunkSearchHit> retrieved = documents.findSimilarChunksInDocument(
                    job.documentId(), queryEmbedding, topSections * topChunksPerSection);
            List<ChunkSearchHit> topChunks = topByScore(retrieved, topSections * topChunksPerSection);

            // Proposer
            transitionWithEvent(job, JobStatus.PROPOSING);
            AgentEnvelope proposer = runProposer(job.jobId(), ctx, topChunks, job.query());
            job.setProposer(proposer);
            if (proposer.error() != null) {
                failJob(job, "Proposer failed: " + proposer.error());
                return;
            }
            stream.emitAgentComplete(job.jobId(), JobEventType.PROPOSER_COMPLETE, proposer.response());

            // Critic — macro view only. Blocking call (output is JSON, not worth streaming).
            transitionWithEvent(job, JobStatus.CRITIQUING);
            AgentEnvelope critic = runCritic(ctx, job.query(), proposer.response());
            job.setCritic(critic);
            stream.emitAgentComplete(job.jobId(), JobEventType.CRITIC_COMPLETE,
                    critic.response() == null ? "(critic failed)" : critic.response());
            // Critic failure isn't fatal — synthesiser can proceed with no challenges.

            // Synthesiser — full hierarchy + debate
            transitionWithEvent(job, JobStatus.SYNTHESISING);
            AgentEnvelope synthesiser = runSynthesiser(job.jobId(), ctx, topChunks, job.query(),
                    proposer.response(), critic);
            job.setSynthesiser(synthesiser);
            if (synthesiser.error() != null) {
                failJob(job, "Synthesiser failed: " + synthesiser.error());
                return;
            }
            stream.emitAgentComplete(job.jobId(), JobEventType.SYNTHESISER_COMPLETE, synthesiser.response());

            String finalResponse = extractSynthesiserResponse(synthesiser.response());
            job.complete(finalResponse, Instant.now());
            stream.emitFinal(job.jobId(), finalResponse);
        } catch (Exception e) {
            log.error("Deliberation {} failed", job.jobId(), e);
            failJob(job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            stream.close(job.jobId());
        }
    }

    private void transitionWithEvent(AskJob job, JobStatus next) {
        job.transition(next);
        stream.emitStatus(job.jobId(), next.name());
    }

    private void failJob(AskJob job, String message) {
        job.fail(message, Instant.now());
        stream.emitFailure(job.jobId(), message);
    }

    // ---- Agents ----

    private AgentEnvelope runProposer(UUID jobId, DocumentContext ctx,
                                      List<ChunkSearchHit> topChunks, String query) {
        Instant start = Instant.now();
        String prompt = proposerTpl
                .replace("{document_title}", nullSafe(ctx.document().title()))
                .replace("{doc_summary}", nullSafe(ctx.document().docSummary()))
                .replace("{concatenated_chapter_titles_and_summaries}", chapterSummariesBlock(ctx))
                .replace("{top_sections_with_summaries}", topSectionsBlock(ctx, topChunks))
                .replace("{top_chunks_with_section_attribution}", topChunksBlock(topChunks))
                .replace("{query}", nullSafe(query));
        try {
            String response = chatStreaming(prompt, proposerTemp,
                    token -> stream.emitToken(jobId, JobEventType.PROPOSER_THOUGHT, token));
            return new AgentEnvelope("proposer", EvidenceAccess.FULL_HIERARCHY,
                    start, Instant.now(), response, null, null, null);
        } catch (Exception e) {
            return new AgentEnvelope("proposer", EvidenceAccess.FULL_HIERARCHY,
                    start, Instant.now(), null, null, null, e.getMessage());
        }
    }

    private AgentEnvelope runCritic(DocumentContext ctx, String query, String proposerResponse) {
        Instant start = Instant.now();
        String prompt = criticTpl
                .replace("{document_title}", nullSafe(ctx.document().title()))
                .replace("{doc_summary}", nullSafe(ctx.document().docSummary()))
                .replace("{concatenated_chapter_titles_and_summaries}", chapterSummariesBlock(ctx))
                .replace("{query}", nullSafe(query))
                .replace("{proposer_response}", nullSafe(proposerResponse));
        try {
            String raw = chat(prompt, criticTemp);
            ParsedCritic parsed = parseCriticOrRetry(prompt, raw);
            return new AgentEnvelope("critic", EvidenceAccess.MACRO_ONLY,
                    start, Instant.now(), raw, parsed.grounding, parsed.challenges, null);
        } catch (Exception e) {
            return new AgentEnvelope("critic", EvidenceAccess.MACRO_ONLY,
                    start, Instant.now(), null, null, null, e.getMessage());
        }
    }

    private AgentEnvelope runSynthesiser(UUID jobId, DocumentContext ctx, List<ChunkSearchHit> topChunks,
                                         String query, String proposerResponse, AgentEnvelope critic) {
        Instant start = Instant.now();
        List<String> challenges = critic.challenges() == null ? List.of() : critic.challenges();
        String challengesFormatted = challenges.isEmpty()
                ? "(no challenges raised)"
                : formatNumberedList(challenges);
        String prompt = synthesiserTpl
                .replace("{document_title}", nullSafe(ctx.document().title()))
                .replace("{doc_summary}", nullSafe(ctx.document().docSummary()))
                .replace("{concatenated_chapter_titles_and_summaries}", chapterSummariesBlock(ctx))
                .replace("{top_sections_with_summaries}", topSectionsBlock(ctx, topChunks))
                .replace("{top_chunks_with_section_attribution}", topChunksBlock(topChunks))
                .replace("{query}", nullSafe(query))
                .replace("{proposer_response}", nullSafe(proposerResponse))
                .replace("{critic_challenges_formatted}", challengesFormatted);
        try {
            String raw = chatStreaming(prompt, synthTemp,
                    token -> stream.emitToken(jobId, JobEventType.SYNTHESISER_THOUGHT, token));
            Map<String, Object> grounding = parseSynthesiserGrounding(raw);
            return new AgentEnvelope("synthesiser", EvidenceAccess.FULL_HIERARCHY_PLUS_DEBATE,
                    start, Instant.now(), raw, grounding, null, null);
        } catch (Exception e) {
            return new AgentEnvelope("synthesiser", EvidenceAccess.FULL_HIERARCHY_PLUS_DEBATE,
                    start, Instant.now(), null, null, null, e.getMessage());
        }
    }

    // ---- LLM call ----

    private String chat(String prompt, double temperature) {
        try {
            ChatCompletion completion = pools.chatPool()
                    .submit(() -> llm.complete("", prompt, temperature))
                    .get();
            return completion.content() == null ? "" : completion.content().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during deliberation", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Deliberation chat call failed", cause);
        }
    }

    /**
     * Streaming variant — token handler fires as the model emits tokens (used to
     * push *_THOUGHT SSE events). The chat-pool slot is held for the entire
     * stream so the single Gemma slot is respected; the stream future resolves
     * on [DONE].
     */
    private String chatStreaming(String prompt, double temperature, Consumer<String> tokenHandler) {
        try {
            ChatCompletion completion = pools.chatPool()
                    .submit(() -> llm.completeStreaming("", prompt, temperature, tokenHandler).get())
                    .get();
            return completion.content() == null ? "" : completion.content().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during deliberation streaming", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Deliberation streaming chat call failed", cause);
        }
    }

    private float[] embedQuery(String query) {
        try {
            List<Embedding> embeddings = pools.embeddingPool()
                    .submit(() -> llm.embedBatch(List.of(query)))
                    .get();
            return embeddings.get(0).vector();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during query embedding", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Query embedding failed", cause);
        }
    }

    // ---- Critic JSON parsing (with retry-once-at-temp-0 elsewhere; here best-effort) ----

    private ParsedCritic parseCriticOrRetry(String prompt, String firstRaw) {
        ParsedCritic parsed = tryParseCritic(firstRaw);
        if (parsed != null) return parsed;
        log.warn("Critic returned non-JSON; retrying once at temperature 0");
        String secondRaw = chat(prompt, 0.0);
        parsed = tryParseCritic(secondRaw);
        if (parsed != null) return parsed;
        // Critic failure isn't fatal — fall back to "no challenges" so synthesiser can proceed.
        return new ParsedCritic(List.of(), Map.of(
                "macro_view_supports_proposer", "unknown",
                "raw_output", truncate(secondRaw, 500)));
    }

    private ParsedCritic tryParseCritic(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = stripFences(raw).trim();
        try {
            JsonNode root = mapper.readTree(cleaned);
            JsonNode challengesNode = root.path("challenges");
            List<String> challenges = new ArrayList<>();
            if (challengesNode.isArray()) {
                for (JsonNode c : challengesNode) challenges.add(c.asText(""));
            }
            Map<String, Object> grounding = new LinkedHashMap<>();
            grounding.put("challenges_count", root.path("challenges_count").asInt(challenges.size()));
            grounding.put("macro_view_supports_proposer",
                    root.path("macro_view_supports_proposer").asText("unknown"));
            return new ParsedCritic(challenges, grounding);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Synthesiser parsing: split on RESPONSE: / GROUNDING: markers ----

    private String extractSynthesiserResponse(String raw) {
        if (raw == null) return "";
        int responseIdx = raw.indexOf("RESPONSE:");
        int groundingIdx = raw.indexOf("GROUNDING:");
        if (responseIdx < 0) return raw.trim();
        int start = responseIdx + "RESPONSE:".length();
        int end = groundingIdx > start ? groundingIdx : raw.length();
        return raw.substring(start, end).trim();
    }

    private Map<String, Object> parseSynthesiserGrounding(String raw) {
        if (raw == null) return null;
        int groundingIdx = raw.indexOf("GROUNDING:");
        if (groundingIdx < 0) return null;
        String jsonPart = raw.substring(groundingIdx + "GROUNDING:".length()).trim();
        jsonPart = stripFences(jsonPart);
        try {
            JsonNode root = mapper.readTree(jsonPart);
            return mapper.convertValue(root, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Synthesiser grounding JSON unparseable: {}", e.getMessage());
            return Map.of("raw_output", truncate(jsonPart, 500));
        }
    }

    // ---- Evidence formatting ----

    private String chapterSummariesBlock(DocumentContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (DocumentContext.ChapterContext cc : ctx.chapters()) {
            sb.append("- ").append(nullSafe(cc.chapter().title()))
                    .append(": ").append(nullSafe(cc.chapter().summary())).append('\n');
        }
        return sb.toString();
    }

    private String topSectionsBlock(DocumentContext ctx, List<ChunkSearchHit> topChunks) {
        // Find the unique sections referenced by the top chunks; emit each with summary.
        Map<UUID, DocumentContext.SectionContext> bySectionId = new HashMap<>();
        for (DocumentContext.ChapterContext cc : ctx.chapters()) {
            for (DocumentContext.SectionContext sc : cc.sections()) {
                bySectionId.put(sc.section().id(), sc);
            }
        }
        java.util.LinkedHashSet<UUID> ordered = new java.util.LinkedHashSet<>();
        for (ChunkSearchHit hit : topChunks) {
            UUID sectionId = sectionIdFor(hit, ctx);
            if (sectionId != null) ordered.add(sectionId);
            if (ordered.size() >= topSections) break;
        }
        StringBuilder sb = new StringBuilder();
        for (UUID id : ordered) {
            DocumentContext.SectionContext sc = bySectionId.get(id);
            if (sc != null) {
                sb.append("- ").append(nullSafe(sc.section().title()))
                        .append(": ").append(nullSafe(sc.section().summary())).append('\n');
            }
        }
        return sb.toString();
    }

    private UUID sectionIdFor(ChunkSearchHit hit, DocumentContext ctx) {
        for (DocumentContext.ChapterContext cc : ctx.chapters()) {
            for (DocumentContext.SectionContext sc : cc.sections()) {
                for (DocumentContext.ParagraphContext pc : sc.paragraphs()) {
                    if (pc.paragraph().id().equals(hit.paragraphId())) return sc.section().id();
                }
            }
        }
        return null;
    }

    private String topChunksBlock(List<ChunkSearchHit> topChunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < topChunks.size(); i++) {
            ChunkSearchHit hit = topChunks.get(i);
            sb.append(i + 1).append(". [").append(nullSafe(hit.sectionTitle())).append("] ")
                    .append(nullSafe(hit.chunkText())).append('\n');
        }
        return sb.toString();
    }

    private List<ChunkSearchHit> topByScore(List<ChunkSearchHit> hits, int max) {
        // Already ordered by similarity from the repo, just trim.
        return hits.size() <= max ? hits : new ArrayList<>(hits.subList(0, max));
    }

    private String formatNumberedList(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i));
            if (i < items.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t;
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private String read(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt: " + resource, e);
        }
    }

    private record ParsedCritic(List<String> challenges, Map<String, Object> grounding) {}
}
