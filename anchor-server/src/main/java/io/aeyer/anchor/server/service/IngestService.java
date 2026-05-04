package io.aeyer.anchor.server.service;

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
        Path documentPath = Paths.get(sourcePath);
        if (!Files.isRegularFile(documentPath)) {
            throw new IngestException("Source path is not a readable file: " + sourcePath);
        }

        ExtractedDocument extracted = parseDocument(documentPath);
        UUID documentId = stableDocumentId(extracted.contentHash());

        // Reset the ledger so the snapshot we return covers only this run.
        ledger.snapshotAndReset();

        try {
            return pools.submitIngest(documentId, () -> runIngest(documentId, sourcePath, extracted)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during ingest", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Ingest failed", cause);
        }
    }

    private ExtractedDocument parseDocument(Path documentPath) {
        try {
            return extractor.extract(documentPath);
        } catch (IOException e) {
            throw new IngestException("Could not parse document: " + documentPath, e);
        }
    }

    private IngestResult runIngest(UUID documentId, String sourcePath, ExtractedDocument extracted) {
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

        // Paragraph summaries — only layer that sees raw text.
        List<ParsedParagraph> allParagraphs = new ArrayList<>(paragraphIds.keySet());
        for (ParsedParagraph paragraph : allParagraphs) {
            String text = paragraphRawText(paragraph);
            paragraphSummaries.put(paragraphIds.get(paragraph), summariser.summariseParagraph(text));
        }

        // Section summaries — see only paragraph summaries.
        for (ParsedChapter chapter : parsed.chapters()) {
            for (ParsedSection section : chapter.sections()) {
                List<String> belowSummaries = section.paragraphs().stream()
                        .map(p -> paragraphSummaries.get(paragraphIds.get(p)))
                        .toList();
                sectionSummaries.put(sectionIds.get(section),
                        summariser.summariseSection(section.title(), belowSummaries));
            }
        }

        // Chapter summaries — see only section summaries.
        for (ParsedChapter chapter : parsed.chapters()) {
            List<String> belowSummaries = chapter.sections().stream()
                    .map(s -> sectionSummaries.get(sectionIds.get(s)))
                    .toList();
            chapterSummaries.put(chapterIds.get(chapter),
                    summariser.summariseChapter(chapter.title(), belowSummaries));
        }

        // Document summary — only chapter summaries. Author-abstract path is a v1
        // refinement; for v0 we always GENERATE since no abstract-quality check exists.
        List<String> chapterSummaryList = parsed.chapters().stream()
                .map(c -> chapterSummaries.get(chapterIds.get(c)))
                .toList();
        String documentSummary = summariser.summariseDocument(parsed.title(), chapterSummaryList);

        // Embeddings — batched per paragraph to keep the embedding pool busy.
        // Embed the doc_summary alongside the chunks so /documents/search and
        // /validate/quick can rank by cosine on the summary directly without
        // a per-query LLM call.
        List<ParsedChunk> allChunks = new ArrayList<>(chunkIds.keySet());
        Map<ParsedChunk, Embedding> chunkEmbeddings = embedAllChunks(allChunks);
        float[] summaryEmbedding = embedSingle(documentSummary);

        TokenLedger.Snapshot tokens = ledger.snapshotAndReset();

        Counts counts = transactionTemplate.execute(status ->
                persistAll(documentId, sourcePath, parsed, documentSummary, summaryEmbedding,
                        chapterIds, sectionIds, paragraphIds, chunkIds,
                        chapterSummaries, sectionSummaries, paragraphSummaries,
                        chunkEmbeddings));

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
        docDbo.setMetadata(Map.of("content_hash", parsed.sourcePathHash()));
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
