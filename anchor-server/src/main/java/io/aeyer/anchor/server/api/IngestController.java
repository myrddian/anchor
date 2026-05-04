package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.ingest.IngestJobAcceptedResponse;
import io.aeyer.anchor.protocol.ingest.IngestRequest;
import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.service.IngestJobRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server-side-path ingest. Returns 202 with a job id — clients poll
 * {@code GET /ingest/jobs/{id}} for progress. Long books take minutes; a
 * blocking endpoint left HTTP timeouts and the browser spinner hanging
 * with no signal.
 */
@RestController
public class IngestController {

    private final IngestJobRunner runner;
    private final IngestApiMapper apiMapper;

    public IngestController(IngestJobRunner runner, IngestApiMapper apiMapper) {
        this.runner = runner;
        this.apiMapper = apiMapper;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestJobAcceptedResponse> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.sourcePath() == null || request.sourcePath().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        IngestJob job = runner.submit(request.sourcePath());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(apiMapper.toAccepted(job));
    }
}
