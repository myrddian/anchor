package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.retrieve.RetrieveHit;
import io.aeyer.anchor.protocol.retrieve.RetrieveRequest;
import io.aeyer.anchor.protocol.retrieve.RetrieveResponse;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepositoryDomain.RetrieveSearchRow;
import io.aeyer.anchor.server.service.EmbeddingService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPEC §5.3 — semantic retrieval (Shape 1). Each chunk arrives wrapped with
 * its full ancestor summary stack so the caller doesn't have to read /chunks/
 * separately.
 */
@RestController
public class RetrieveController {

    private static final int DEFAULT_K = 10;

    private final EmbeddingService embedder;
    private final DocumentRepository documents;

    public RetrieveController(EmbeddingService embedder, DocumentRepository documents) {
        this.embedder = embedder;
        this.documents = documents;
    }

    @PostMapping("/retrieve")
    public ResponseEntity<RetrieveResponse> retrieve(@RequestBody RetrieveRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        int k = request.k() == null || request.k() <= 0 ? DEFAULT_K : request.k();

        List<Embedding> embeddings = embedder.embedAll(List.of(request.query()));
        if (embeddings.isEmpty()) {
            return ResponseEntity.ok(new RetrieveResponse(request.query(), request.documentId(), k, List.of()));
        }
        List<RetrieveSearchRow> rows = documents.findChunksForRetrieve(
                request.documentId(), embeddings.get(0).vector(), k);

        List<RetrieveHit> hits = rows.stream().map(r -> new RetrieveHit(
                r.chunkId(),
                r.chunkText(),
                r.similarity(),
                r.paragraphId(),
                r.paragraphSummary(),
                r.sectionId(),
                r.sectionTitle(),
                r.sectionSummary(),
                r.chapterId(),
                r.chapterTitle(),
                r.chapterSummary(),
                r.documentId(),
                r.documentTitle(),
                r.documentSummary())).toList();

        return ResponseEntity.ok(new RetrieveResponse(request.query(), request.documentId(), k, hits));
    }
}
