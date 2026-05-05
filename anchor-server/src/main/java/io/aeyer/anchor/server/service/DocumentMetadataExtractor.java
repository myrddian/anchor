package io.aeyer.anchor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.server.domain.Citation;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.workers.WorkerPools;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Extracts non-claim-bearing document metadata that the claim-bearing
 * summariser correctly filters out: authors (from front matter) and
 * citations (from the references section the structural parser drops).
 *
 * Tier 2.5 follow-up — the deliberation pipeline was structurally blind to
 * identity-shaped questions like "what method does Wagner use to refute
 * the conjectures in this paper?" because no summary at any level mentions
 * the author by name. This is the parallel ingest pass that surfaces those
 * facts to the proposer / synthesiser via {@code Document.metadata}.
 *
 * Two LLM calls per document, both submitted through the shared chat pool
 * so they queue behind the regular summariser work rather than competing
 * for the chat slot. Failures are non-fatal — an empty list is preferable
 * to a failed ingest, since metadata is augmenting evidence, not replacing
 * it.
 */
@Service
public class DocumentMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentMetadataExtractor.class);

    private static final double EXTRACT_TEMPERATURE = 0.0;
    private static final int FRONT_MATTER_CHARS = 2000;
    private static final int REFERENCES_CHARS_LIMIT = 12000;

    private final LMStudioClient llm;
    private final WorkerPools pools;
    private final ObjectMapper mapper;

    @Value("classpath:prompts/extract-authors.txt") Resource authorsPrompt;
    @Value("classpath:prompts/extract-citations.txt") Resource citationsPrompt;

    private String authorsTpl;
    private String citationsTpl;

    public DocumentMetadataExtractor(LMStudioClient llm, WorkerPools pools, ObjectMapper mapper) {
        this.llm = llm;
        this.pools = pools;
        this.mapper = mapper;
    }

    @PostConstruct
    void loadPrompts() {
        authorsTpl = read(authorsPrompt);
        citationsTpl = read(citationsPrompt);
    }

    /**
     * Returns a possibly-empty list of authors extracted from the first
     * {@value #FRONT_MATTER_CHARS} characters of the document. Returns
     * empty on any failure — the caller treats the absence as "no authors
     * surfaced", not as an ingest error.
     */
    public List<String> extractAuthors(String fullText) {
        if (fullText == null || fullText.isBlank()) return List.of();
        String frontMatter = fullText.substring(0, Math.min(FRONT_MATTER_CHARS, fullText.length()));
        String prompt = authorsTpl.replace("{front_matter_text}", frontMatter);
        try {
            String raw = submitChat(prompt);
            JsonNode root = mapper.readTree(stripFences(raw));
            JsonNode arr = root.get("authors");
            if (arr == null || !arr.isArray()) return List.of();
            LinkedHashSet<String> dedup = new LinkedHashSet<>();
            for (JsonNode n : arr) {
                String name = n.asText("").trim();
                if (!name.isEmpty()) dedup.add(name);
            }
            return List.copyOf(dedup);
        } catch (Exception e) {
            log.warn("Author extraction failed; returning empty list. cause={}", e.toString());
            return List.of();
        }
    }

    /**
     * Returns a possibly-empty list of citations extracted from the supplied
     * references-section text. Empty on any failure (see {@link #extractAuthors}).
     */
    public List<Citation> extractCitations(String referencesText) {
        if (referencesText == null || referencesText.isBlank()) return List.of();
        String trimmed = referencesText.length() <= REFERENCES_CHARS_LIMIT
                ? referencesText
                : referencesText.substring(0, REFERENCES_CHARS_LIMIT);
        String prompt = citationsTpl.replace("{references_text}", trimmed);
        try {
            String raw = submitChat(prompt);
            JsonNode root = mapper.readTree(stripFences(raw));
            JsonNode arr = root.get("citations");
            if (arr == null || !arr.isArray()) return List.of();
            List<Citation> out = new ArrayList<>(arr.size());
            for (JsonNode n : arr) {
                JsonNode refNumNode = n.get("ref_num");
                JsonNode rawNode = n.get("raw");
                if (refNumNode == null || !refNumNode.isInt()) continue;
                if (rawNode == null) continue;
                String text = rawNode.asText("").trim();
                if (text.isEmpty()) continue;
                out.add(new Citation(refNumNode.asInt(), text));
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.warn("Citation extraction failed; returning empty list. cause={}", e.toString());
            return List.of();
        }
    }

    /**
     * Convenience for {@link io.aeyer.anchor.server.persistence.entity.DocumentDbo}'s
     * JSONB metadata column: serialises the {@link Citation} record to a
     * plain {@code Map<String,Object>} so Hibernate / Jackson don't need
     * type info for the JSONB write.
     */
    public static List<Map<String, Object>> toMetadataList(List<Citation> citations) {
        List<Map<String, Object>> out = new ArrayList<>(citations.size());
        for (Citation c : citations) {
            out.add(Map.of("ref_num", c.refNum(), "raw", c.raw()));
        }
        return out;
    }

    private String submitChat(String prompt) throws InterruptedException, ExecutionException {
        return pools.chatPool().submit(() -> {
            ChatCompletion completion = llm.complete("", prompt, EXTRACT_TEMPERATURE);
            return completion == null ? "" : completion.content();
        }).get();
    }

    private static String stripFences(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String read(Resource r) {
        try (var in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
