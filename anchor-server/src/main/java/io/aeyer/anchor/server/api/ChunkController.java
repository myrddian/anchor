package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.documents.ChunkDetailResponse;
import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chunks")
public class ChunkController {

    private final DocumentRepository documents;

    public ChunkController(DocumentRepository documents) {
        this.documents = documents;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ChunkDetailResponse> chunk(@PathVariable UUID id) {
        ChunkWithAncestors c = documents.findChunkWithAncestorsAsDomain(id).orElse(null);
        if (c == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        return ResponseEntity.ok(new ChunkDetailResponse(
                c.chunk().id(),
                c.chunk().text(),
                c.paragraph().id(),
                c.paragraph().summary(),
                c.section().id(),
                c.section().title(),
                c.section().summary(),
                c.chapter().id(),
                c.chapter().title(),
                c.chapter().summary(),
                c.document().id(),
                c.document().title(),
                c.document().docSummary()));
    }
}
