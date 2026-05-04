package io.aeyer.anchor.server.service;

import io.aeyer.anchor.protocol.ingest.IngestPhase;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedChapter;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedChunk;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedDocument;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedParagraph;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedSection;
import io.aeyer.anchor.server.ingest.DocumentTextExtractor;
import io.aeyer.anchor.server.ingest.ExtractedDocument;
import io.aeyer.anchor.server.ingest.StructuralParser;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.persistence.entity.ChapterDbo;
import io.aeyer.anchor.server.persistence.entity.ChunkDbo;
import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import io.aeyer.anchor.server.persistence.entity.ParagraphDbo;
import io.aeyer.anchor.server.persistence.entity.SectionDbo;
import io.aeyer.anchor.server.persistence.repo.ChapterRepository;
import io.aeyer.anchor.server.persistence.repo.ChunkRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.ParagraphRepository;
import io.aeyer.anchor.server.persistence.repo.SectionRepository;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ingest orchestrator (SPEC §4 / §5.1). Runs on {@code WorkerPools.ingestPool()}
 * via {@link WorkerPools#submitIngest} so concurrent requests for the same
 * document content converge on a single execution.
 *
 * Pipeline:
 *   parse PDF (CPU)
 *     → structural decomposition (chapter/section/paragraph/chunk)
 *     → paragraph summaries (chat pool, parallel-friendly but here sequential
 *        per paragraph so the ledger and back-pressure stay simple)
 *     → embeddings for chunks (embedding pool, batched)
 *     → section / chapter / doc summaries (chat pool, claim-bearing cascade)
 *     → persist (delete-then-insert for idempotency on the source-path hash)
 *
 * Document id is a name-based UUID derived from the SHA-256 of the file content
 * so re-ingesting the same file replaces the same row — clean cascade delete
 * via the FK, no orphans.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final UUID NAMESPACE = UUID.fromString("a07c4cd9-8c8e-4f3e-9b69-9b6f1a7c1e00");

    private final DocumentTextExtractor extractor;
    private final StructuralParser parser;
    private final SummariserService summariser;
    private final EmbeddingService embedder;
    private final TokenLedger ledger;
    private final WorkerPools pools;
    private final DocumentRepository documents;
    private final ChapterRepository chapters;
    private final SectionRepository sections;
    private final ParagraphRepository paragraphs;
    private final ChunkRepository chunks;
    private final TransactionTemplate transactionTemplate;

    public IngestService(DocumentTextExtractor extractor, StructuralParser parser,
                         SummariserService summariser, EmbeddingService embedder,
                         TokenLedger ledger, WorkerPools pools,
                         DocumentRepository documents, ChapterRepository chapters,
                         SectionRepository sections, ParagraphRepository paragraphs,
                         ChunkRepository chunks, PlatformTransactionManager txManager) {
        this.extractor = extractor;
        this.parser = parser;
        this.summariser = summariser;
        this.embedder = embedder;
        this.ledger = ledger;
        this.pools = pools;
        this.documents = documents;
        this.chapters = chapters;
        this.sections = sections;
        this.paragraphs = paragraphs;
        this.chunks = chunks;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    public IngestResult ingest(String sourcePath) {
        return ingest(sourcePath, IngestProgressReporter.NOOP);
    }

    /**
     * Blocking entrypoint that submits via {@link WorkerPools#submitIngest}
     * and waits for completion. Preserves the document-id dedup convergence
     * (concurrent calls for the same content hash share a Future). Used by
     * integration tests; the production async path goes through
     * {@link #runOnCurrentThread} via the IngestJobRunner instead, since
     * blocking on .get() inside an HTTP request thread is what we built
     * the async story to avoid.
     */
    public IngestResult ingest(String sourcePath, IngestProgressReporter progress) {
        Path documentPath = Paths.get(sourcePath);
        if (!Files.isRegularFile(documentPath)) {
            throw new IngestException("Source path is not a readable file: " + sourcePath);
        }

        progress.report(IngestPhase.EXTRACTING, PHASE_END_EXTRACT - 5, "Extracting text");
        ExtractedDocument extracted = parseDocument(documentPath);
        UUID documentId = stableDocumentId(extracted.contentHash());
        progress.attachDocument(documentId, extracted.title());
        progress.report(IngestPhase.EXTRACTING, PHASE_END_EXTRACT, "Extracted " + extracted.text().length() + " chars");

        // Reset the ledger so the snapshot we return covers only this run.
        ledger.snapshotAndReset();

        try {
            return pools.submitIngest(documentId, () -> runIngest(documentId, sourcePath, extracted, progress)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during ingest", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Ingest failed", cause);
        }
    }

    /**
     * Run the full ingest pipeline on the calling thread. The async path
     * (IngestJobRunner) calls this directly on the ingest pool — no
     * {@code submitIngest} wrap, so no nested-pool deadlock when the
     * orchestrator is itself an ingest-pool task. Trade-off: skips
     * document-id dedup convergence; two concurrent ingests of the same
     * file produce two jobs, both serialised on the size-1 ingest pool,
     * second one cascade-deletes the first's rows. Acceptable for v0.
     */
    public IngestResult runOnCurrentThread(String sourcePath, IngestProgressReporter progress) {
        Path documentPath = Paths.get(sourcePath);
        if (!Files.isRegularFile(documentPath)) {
            throw new IngestException("Source path is not a readable file: " + sourcePath);
        }
        progress.report(IngestPhase.EXTRACTING, PHASE_END_EXTRACT - 5, "Extracting text");
        ExtractedDocument extracted = parseDocument(documentPath);
        UUID documentId = stableDocumentId(extracted.contentHash());
        progress.attachDocument(documentId, extracted.title());
        progress.report(IngestPhase.EXTRACTING, PHASE_END_EXTRACT,
                "Extracted " + extracted.text().length() + " chars");
        ledger.snapshotAndReset();
        return runIngest(documentId, sourcePath, extracted, progress);
    }

    // Phase budget — pre-allocated slices of the 0..100 percent. The summary
    // cascade dominates wall time on a long book (Gemma is the bottleneck);
    // weights are eyeballed from real ingests and refine in-phase by counting
    // items processed.
    private static final int PHASE_END_EXTRACT = 5;
    private static final int PHASE_END_PARSE = 8;
    private static final int PHASE_END_PARAGRAPHS = 60;
    private static final int PHASE_END_SECTIONS = 75;
    private static final int PHASE_END_CHAPTERS = 85;
    private static final int PHASE_END_DOC_SUMMARY = 88;
    private static final int PHASE_END_EMBEDDING = 97;
    private static final int PHASE_END_PERSIST = 100;

    private ExtractedDocument parseDocument(Path documentPath) {
        try {
            return extractor.extract(documentPath);
        } catch (IOException e) {
            throw new IngestException("Could not parse document: " + documentPath, e);
        }
    }

    private IngestResult runIngest(UUID documentId, String sourcePath, ExtractedDocument extracted,
                                   IngestProgressReporter progress) {
        progress.report(IngestPhase.PARSING, PHASE_END_PARSE, "Parsing structure");
        ParsedDocument parsed = parser.parse(extracted);

        Map<UUID, String> paragraphSummaries = new HashMap<>();
        Map<UUID, String> sectionSummaries = new HashMap<>();
        Map<UUID, String> chapterSummaries = new HashMap<>();

        // Materialise persistent IDs up-front so summaries can be keyed by them.
        Map<ParsedChapter, UUID> chapterIds = new HashMap<>();
        Map<ParsedSection, UUID> sectionIds = new HashMap<>();
        Map<ParsedParagraph, UUID> paragraphIds = new HashMap<>();
        Map<ParsedChunk, UUID> chunkIds = new HashMap<>();
        Map<ParsedSection, ParsedChapter> sectionToChapter = new HashMap<>();
        Map<ParsedParagraph, ParsedSection> paragraphToSection = new HashMap<>();
        for (ParsedChapter chapter : parsed.chapters()) {
            chapterIds.put(chapter, UUID.randomUUID());
            for (ParsedSection section : chapter.sections()) {
                sectionIds.put(section, UUID.randomUUID());
                sectionToChapter.put(section, chapter);
                for (ParsedParagraph paragraph : section.paragraphs()) {
                    paragraphIds.put(paragraph, UUID.randomUUID());
                    paragraphToSection.put(paragraph, section);
                    for (ParsedChunk chunk : paragraph.chunks()) {
                        chunkIds.put(chunk, UUID.randomUUID());
                    }
                }
            }
        }

        // Paragraph summaries — only layer that sees raw text. This is the
        // longest phase by wall time, so report after every Nth paragraph
        // (and always on the last) — finer granularity than per-phase
        // bookends at the cost of a few more progress writes.
        List<ParsedParagraph> allParagraphs = new ArrayList<>(paragraphIds.keySet());
        int totalParagraphs = allParagraphs.size();
        progress.report(IngestPhase.SUMMARISING_PARAGRAPHS, PHASE_END_PARSE,
                "Summarising " + totalParagraphs + " paragraphs");
        int paragraphReportEvery = Math.max(1, totalParagraphs / 25);
        for (int i = 0; i < totalParagraphs; i++) {
            ParsedParagraph paragraph = allParagraphs.get(i);
            String text = paragraphRawText(paragraph);
            paragraphSummaries.put(paragraphIds.get(paragraph), summariser.summariseParagraph(text));
            if ((i + 1) % paragraphReportEvery == 0 || i == totalParagraphs - 1) {
                int pct = phasePercent(PHASE_END_PARSE, PHASE_END_PARAGRAPHS, i + 1, totalParagraphs);
                progress.report(IngestPhase.SUMMARISING_PARAGRAPHS, pct,
                        "Summarised " + (i + 1) + "/" + totalParagraphs + " paragraphs");
            }
        }

        // Section summaries — see only paragraph summaries.
        int totalSections = sectionIds.size();
        progress.report(IngestPhase.SUMMARISING_SECTIONS, PHASE_END_PARAGRAPHS,
                "Summarising " + totalSections + " sections");
        int sectionsDone = 0;
        int sectionReportEvery = Math.max(1, totalSections / 10);
        for (ParsedChapter chapter : parsed.chapters()) {
            for (ParsedSection section : chapter.sections()) {
                List<String> belowSummaries = section.paragraphs().stream()
                        .map(p -> paragraphSummaries.get(paragraphIds.get(p)))
                        .toList();
                sectionSummaries.put(sectionIds.get(section),
                        summariser.summariseSection(section.title(), belowSummaries));
                sectionsDone++;
                if (sectionsDone % sectionReportEvery == 0 || sectionsDone == totalSections) {
                    int pct = phasePercent(PHASE_END_PARAGRAPHS, PHASE_END_SECTIONS, sectionsDone, totalSections);
                    progress.report(IngestPhase.SUMMARISING_SECTIONS, pct,
                            "Summarised " + sectionsDone + "/" + totalSections + " sections");
                }
            }
        }

        // Chapter summaries — see only section summaries.
        int totalChapters = parsed.chapters().size();
        progress.report(IngestPhase.SUMMARISING_CHAPTERS, PHASE_END_SECTIONS,
                "Summarising " + totalChapters + " chapters");
        int chaptersDone = 0;
        for (ParsedChapter chapter : parsed.chapters()) {
            List<String> belowSummaries = chapter.sections().stream()
                    .map(s -> sectionSummaries.get(sectionIds.get(s)))
                    .toList();
            chapterSummaries.put(chapterIds.get(chapter),
                    summariser.summariseChapter(chapter.title(), belowSummaries));
            chaptersDone++;
            int pct = phasePercent(PHASE_END_SECTIONS, PHASE_END_CHAPTERS, chaptersDone, totalChapters);
            progress.report(IngestPhase.SUMMARISING_CHAPTERS, pct,
                    "Summarised " + chaptersDone + "/" + totalChapters + " chapters");
        }

        // Document summary — only chapter summaries. Author-abstract path is a v1
        // refinement; for v0 we always GENERATE since no abstract-quality check exists.
        progress.report(IngestPhase.SUMMARISING_DOCUMENT, PHASE_END_CHAPTERS, "Summarising document");
        List<String> chapterSummaryList = parsed.chapters().stream()
                .map(c -> chapterSummaries.get(chapterIds.get(c)))
                .toList();
        String documentSummary = summariser.summariseDocument(parsed.title(), chapterSummaryList);
        progress.report(IngestPhase.SUMMARISING_DOCUMENT, PHASE_END_DOC_SUMMARY, "Document summary done");

        // Embeddings — batched per paragraph to keep the embedding pool busy.
        // Embed the doc_summary alongside the chunks so /documents/search and
        // /validate/quick can rank by cosine on the summary directly without
        // a per-query LLM call.
        List<ParsedChunk> allChunks = new ArrayList<>(chunkIds.keySet());
        progress.report(IngestPhase.EMBEDDING, PHASE_END_DOC_SUMMARY,
                "Embedding " + allChunks.size() + " chunks");
        Map<ParsedChunk, Embedding> chunkEmbeddings = embedAllChunks(allChunks);
        float[] summaryEmbedding = embedSingle(documentSummary);
        progress.report(IngestPhase.EMBEDDING, PHASE_END_EMBEDDING, "Embeddings done");

        TokenLedger.Snapshot tokens = ledger.snapshotAndReset();

        progress.report(IngestPhase.PERSISTING, PHASE_END_EMBEDDING, "Persisting to database");
        Counts counts = transactionTemplate.execute(status ->
                persistAll(documentId, sourcePath, parsed, documentSummary, summaryEmbedding,
                        chapterIds, sectionIds, paragraphIds, chunkIds,
                        chapterSummaries, sectionSummaries, paragraphSummaries,
                        chunkEmbeddings));
        progress.report(IngestPhase.PERSISTING, PHASE_END_PERSIST, "Persisted");

        log.info("Ingested {} → {} chapters, {} sections, {} paragraphs, {} chunks ({} sum-in / {} sum-out tokens)",
                sourcePath, counts.chapters, counts.sections, counts.paragraphs, counts.chunks,
                tokens.summaryInput(), tokens.summaryOutput());

        return new IngestResult(documentId, parsed.title(), sourcePath,
                counts.chapters, counts.sections, counts.paragraphs, counts.chunks,
                Instant.now(), tokens);
    }

    private float[] embedSingle(String text) {
        if (text == null || text.isBlank()) return new float[0];
        List<Embedding> result = embedder.embedAll(List.of(text));
        return result.isEmpty() ? new float[0] : result.get(0).vector();
    }

    private Map<ParsedChunk, Embedding> embedAllChunks(List<ParsedChunk> allChunks) {
        if (allChunks.isEmpty()) return Map.of();
        List<String> texts = allChunks.stream().map(ParsedChunk::text).toList();
        List<Embedding> embeddings = embedder.embedAll(texts);
        if (embeddings.size() != texts.size()) {
            throw new IngestException(
                    "Embedding count mismatch: expected " + texts.size() + " got " + embeddings.size());
        }
        Map<ParsedChunk, Embedding> result = new HashMap<>();
        for (int i = 0; i < allChunks.size(); i++) result.put(allChunks.get(i), embeddings.get(i));
        return result;
    }

    private Counts persistAll(UUID documentId, String sourcePath, ParsedDocument parsed, String documentSummary,
                                float[] summaryEmbedding,
                                Map<ParsedChapter, UUID> chapterIds, Map<ParsedSection, UUID> sectionIds,
                                Map<ParsedParagraph, UUID> paragraphIds, Map<ParsedChunk, UUID> chunkIds,
                                Map<UUID, String> chapterSummaries, Map<UUID, String> sectionSummaries,
                                Map<UUID, String> paragraphSummaries,
                                Map<ParsedChunk, Embedding> chunkEmbeddings) {
        // Idempotent re-ingest: cascade delete via the FK ON DELETE CASCADE rules
        // wipes chapters → sections → paragraphs → chunks before we re-insert.
        documents.deleteById(documentId);
        documents.flush();

        DocumentDbo docDbo = new DocumentDbo();
        docDbo.setId(documentId);
        docDbo.setTitle(parsed.title());
        docDbo.setSourcePath(sourcePath);
        docDbo.setDocSummary(documentSummary);
        docDbo.setDocSummarySource(DocSummarySource.GENERATED);
        docDbo.setIngestedAt(Instant.now());
        // top_level_label feeds the deliberation prompts so the model uses
        // the source's own terminology ("section" / "chapter" / "part")
        // instead of always saying "chapter". Stored in the existing JSONB
        // metadata blob — no migration needed.
        docDbo.setMetadata(Map.of(
                "content_hash", parsed.sourcePathHash(),
                "top_level_label", parsed.topLevelVocabulary().singular()));
        if (summaryEmbedding != null && summaryEmbedding.length > 0) {
            docDbo.setSummaryEmbedding(summaryEmbedding);
        }
        documents.save(docDbo);

        int chapterCount = 0, sectionCount = 0, paragraphCount = 0, chunkCount = 0;
        for (ParsedChapter chapter : parsed.chapters()) {
            ChapterDbo chapterDbo = new ChapterDbo();
            chapterDbo.setId(chapterIds.get(chapter));
            chapterDbo.setDocumentId(documentId);
            chapterDbo.setOrdinal(chapter.orderIndex());
            chapterDbo.setTitle(chapter.title());
            chapterDbo.setSummary(chapterSummaries.get(chapterIds.get(chapter)));
            chapterDbo.setSynthetic(chapter.isSynthetic());
            chapters.save(chapterDbo);
            chapterCount++;

            for (ParsedSection section : chapter.sections()) {
                SectionDbo sectionDbo = new SectionDbo();
                sectionDbo.setId(sectionIds.get(section));
                sectionDbo.setChapterId(chapterIds.get(chapter));
                sectionDbo.setOrdinal(section.orderIndex());
                sectionDbo.setTitle(section.title());
                sectionDbo.setSummary(sectionSummaries.get(sectionIds.get(section)));
                sections.save(sectionDbo);
                sectionCount++;

                for (ParsedParagraph paragraph : section.paragraphs()) {
                    ParagraphDbo paragraphDbo = new ParagraphDbo();
                    paragraphDbo.setId(paragraphIds.get(paragraph));
                    paragraphDbo.setSectionId(sectionIds.get(section));
                    paragraphDbo.setOrdinal(paragraph.orderIndex());
                    paragraphDbo.setRawText(paragraphRawText(paragraph));
                    paragraphDbo.setSummary(paragraphSummaries.get(paragraphIds.get(paragraph)));
                    paragraphs.save(paragraphDbo);
                    paragraphCount++;

                    for (ParsedChunk chunk : paragraph.chunks()) {
                        ChunkDbo chunkDbo = new ChunkDbo();
                        chunkDbo.setId(chunkIds.get(chunk));
                        chunkDbo.setParagraphId(paragraphIds.get(paragraph));
                        chunkDbo.setOrdinal(chunk.orderIndex());
                        chunkDbo.setText(chunk.text());
                        Embedding embedding = chunkEmbeddings.get(chunk);
                        chunkDbo.setEmbedding(embedding == null ? new float[0] : embedding.vector());
                        chunks.save(chunkDbo);
                        chunkCount++;
                    }
                }
            }
        }
        return new Counts(chapterCount, sectionCount, paragraphCount, chunkCount);
    }

    /** Linear interpolation inside a phase slice: maps (done/total) into [from, to]. */
    private static int phasePercent(int from, int to, int done, int total) {
        if (total <= 0) return to;
        int span = to - from;
        return from + (int) Math.round(span * (Math.min(done, total) / (double) total));
    }

    private String paragraphRawText(ParsedParagraph paragraph) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < paragraph.chunks().size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(paragraph.chunks().get(i).text());
        }
        return sb.toString();
    }

    private UUID stableDocumentId(String contentHash) {
        // Name-based UUID v3: deterministic given the content hash, so re-ingesting
        // the same file produces the same id and replaces the same row.
        byte[] seed = (NAMESPACE + ":" + contentHash).getBytes(StandardCharsets.UTF_8);
        return UUID.nameUUIDFromBytes(seed);
    }

    public record IngestResult(
            UUID documentId, String title, String sourcePath,
            int chapterCount, int sectionCount, int paragraphCount, int chunkCount,
            Instant ingestedAt, TokenLedger.Snapshot tokens) {}

    private record Counts(int chapters, int sections, int paragraphs, int chunks) {}
}
