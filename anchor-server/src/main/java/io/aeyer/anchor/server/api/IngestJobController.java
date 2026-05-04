package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.ingest.IngestJobResponse;
import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.jobs.IngestJobStore;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Progress envelope for async ingest jobs. Mirrors {@code GET /jobs/{id}} for
 * deliberations: the client polls this until {@code status} flips to a
 * terminal state, then reads {@code result} (the same body the old sync
 * endpoint used to return inline).
 */
@RestController
public class IngestJobController {

    private final IngestJobStore store;
    private final IngestApiMapper apiMapper;

    public IngestJobController(IngestJobStore store, IngestApiMapper apiMapper) {
        this.store = store;
        this.apiMapper = apiMapper;
    }

    @GetMapping("/ingest/jobs/{jobId}")
    public ResponseEntity<IngestJobResponse> get(@PathVariable UUID jobId) {
        IngestJob job = store.get(jobId).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(apiMapper.toJobResponse(job));
    }
}
