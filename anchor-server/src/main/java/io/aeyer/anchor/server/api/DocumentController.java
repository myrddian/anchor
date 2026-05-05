package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.documents.ChapterDetail;
import io.aeyer.anchor.protocol.documents.DocumentDetailResponse;
import io.aeyer.anchor.protocol.documents.DocumentListResponse;
import io.aeyer.anchor.protocol.documents.DocumentSearchHit;
import io.aeyer.anchor.protocol.documents.DocumentSearchResponse;
import io.aeyer.anchor.protocol.documents.DocumentSummaryResponse;
import io.aeyer.anchor.protocol.documents.SectionDetail;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepositoryDomain.DocumentCounts;
import io.aeyer.anchor.server.persistence.repo.DocumentRepositoryDomain.DocumentSearchRow;
import io.aeyer.anchor.server.service.EmbeddingService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int DEFAULT_SEARCH_K = 20;

    private final DocumentRepository documents;
    private final EmbeddingService embedder;

    public DocumentController(DocumentRepository documents, EmbeddingService embedder) {
        this.documents = documents;
        this.embedder = embedder;
    }

    /**
     * Semantic search across documents — embeds the caller's query and ranks
     * documents by cosine of that embedding against each doc's stored
     * summary embedding (populated at ingest, backfilled at startup for
     * pre-V2 docs). Topical relevance, not stance — for stance use
     * /validate/quick on a single document.
     */
    @GetMapping("/search")
    public DocumentSearchResponse search(
            @RequestParam("q") String query,
            @RequestParam(value = "k", defaultValue = "20") int k) {
        if (query == null || query.isBlank()) {
            return new DocumentSearchResponse(query, k, List.of());
        }
        int safeK = k <= 0 ? DEFAULT_SEARCH_K : k;

        List<Embedding> embeddings = embedder.embedAll(List.of(query));
        if (embeddings.isEmpty()) {
            return new DocumentSearchResponse(query, safeK, List.of());
        }
        List<DocumentSearchRow> rows = documents.searchDocumentsBySummary(
                embeddings.get(0).vector(), safeK);

        List<DocumentSearchHit> hits = rows.stream().map(r -> new DocumentSearchHit(
                r.documentId(),
                r.title(),
                r.sourcePath(),
                r.docSummary(),
                r.ingestedAt(),
                r.similarity())).toList();
        return new DocumentSearchResponse(query, safeK, hits);
    }

    @GetMapping
    public DocumentListResponse list(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "q", required = false) String q) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        int safeOffset = Math.max(0, offset);
        long total = documents.countMatching(q);
        List<Document> page = documents.findPageAsDomain(safeLimit, safeOffset, q);
        List<DocumentSummaryResponse> summaries = page.stream().map(this::toSummary).toList();
        return new DocumentListResponse(summaries, total, safeLimit, safeOffset);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetailResponse> detail(@PathVariable UUID id) {
        DocumentContext ctx = documents.findDocumentContextAsDomain(id).orElse(null);
        if (ctx == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        // Synthetic structural units carry sentinel titles in the DB; the
        // API surface returns title=null + is_synthetic=true so consumers
        // never see __SYNTHETIC_SEGMENT__ / __SYNTHETIC_HEAP__ in JSON.
        List<ChapterDetail> chapterDetails = ctx.chapters().stream().map(c -> new ChapterDetail(
                c.chapter().id(),
                c.chapter().ordinal(),
                c.chapter().isSynthetic() ? null : c.chapter().title(),
                c.chapter().summary(),
                c.chapter().isSynthetic(),
                c.sections().stream().map(s -> new SectionDetail(
                        s.section().id(),
                        s.section().ordinal(),
                        s.section().isSynthetic() ? null : s.section().title(),
                        s.section().isSynthetic(),
                        s.section().summary())).toList())).toList();

        return ResponseEntity.ok(new DocumentDetailResponse(
                ctx.document().id(),
                ctx.document().title(),
                ctx.document().sourcePath(),
                ctx.document().docSummary(),
                ctx.document().ingestedAt(),
                chapterDetails));
    }

    private DocumentSummaryResponse toSummary(Document d) {
        DocumentCounts counts = documents.countsFor(d.id());
        return new DocumentSummaryResponse(
                d.id(),
                d.title(),
                d.sourcePath(),
                d.docSummary(),
                d.ingestedAt(),
                counts.chapters(),
                counts.sections(),
                counts.chunks());
    }
}
