package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.validate.AlternativeChunk;
import io.aeyer.anchor.protocol.validate.ArgumentativeRole;
import io.aeyer.anchor.protocol.validate.DocumentStance;
import io.aeyer.anchor.protocol.validate.ValidateRequest;
import io.aeyer.anchor.protocol.validate.ValidateResponse;
import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.ValidationResult;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepositoryDomain.ChunkSearchHit;
import io.aeyer.anchor.server.service.EmbeddingService;
import io.aeyer.anchor.server.service.ValidationService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /validate. Hands the chunk + ancestors to {@link ValidationService} and,
 * when the model flags steelman / reject patterns, runs alternative-chunk
 * discovery: embed "not " + query and similarity-search inside the same
 * document for the passages doing the work.
 */
@RestController
public class ValidateController {

    private static final int ALTERNATIVE_LIMIT = 2;

    private final DocumentRepository documents;
    private final ValidationService validator;
    private final EmbeddingService embedder;

    public ValidateController(DocumentRepository documents,
                              ValidationService validator,
                              EmbeddingService embedder) {
        this.documents = documents;
        this.validator = validator;
        this.embedder = embedder;
    }

    @PostMapping("/validate")
    public ResponseEntity<ValidateResponse> validate(@RequestBody ValidateRequest request) {
        if (request == null || request.chunkId() == null
                || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        ChunkWithAncestors evidence = documents
                .findChunkWithAncestorsAsDomain(request.chunkId())
                .orElse(null);
        if (evidence == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        ValidationResult judgment = validator.validate(evidence, request.query());

        List<AlternativeChunk> alternatives = needsAlternatives(judgment)
                ? discoverAlternatives(evidence, request.query())
                : List.of();

        return ResponseEntity.ok(new ValidateResponse(
                judgment.chunkId(),
                judgment.documentId(),
                judgment.query(),
                judgment.isLoadBearing(),
                judgment.argumentativeRole(),
                judgment.documentStanceOnQuery(),
                judgment.qualifyingContext(),
                judgment.reasoning(),
                alternatives));
    }

    private boolean needsAlternatives(ValidationResult judgment) {
        return judgment.argumentativeRole() == ArgumentativeRole.STEELMAN_REFUTED_LATER
                || judgment.documentStanceOnQuery() == DocumentStance.REJECTS;
    }

    /**
     * SPEC §7.4: embed "not " + query — the negation typically lands close (in
     * cosine space) to the passages the document uses to argue against the
     * original claim. Restrict to the same document so we surface where the
     * refutation lives, not where some other paper says the same thing.
     */
    private List<AlternativeChunk> discoverAlternatives(ChunkWithAncestors evidence, String query) {
        List<Embedding> embeddings = embedder.embedAll(List.of("not " + query));
        if (embeddings.isEmpty()) return List.of();
        List<ChunkSearchHit> hits = documents.findSimilarChunksInDocument(
                evidence.document().id(), embeddings.get(0).vector(), ALTERNATIVE_LIMIT + 1);
        List<AlternativeChunk> out = new ArrayList<>(ALTERNATIVE_LIMIT);
        for (ChunkSearchHit hit : hits) {
            if (hit.chunkId().equals(evidence.chunk().id())) continue; // skip the input chunk
            out.add(new AlternativeChunk(
                    hit.chunkId(),
                    hit.chunkText(),
                    hit.paragraphSummary(),
                    hit.sectionTitle(),
                    hit.similarity()));
            if (out.size() >= ALTERNATIVE_LIMIT) break;
        }
        return out;
    }
}
