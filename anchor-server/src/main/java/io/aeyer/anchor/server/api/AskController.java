package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.ask.AskJobResponse;
import io.aeyer.anchor.protocol.ask.AskRequest;
import io.aeyer.anchor.protocol.ask.JobAcceptedResponse;
import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.server.jobs.AskJob;
import io.aeyer.anchor.server.jobs.JobStore;
import io.aeyer.anchor.server.service.AskService;
import io.aeyer.anchor.server.service.IngestException;
import io.aeyer.anchor.server.sse.JobStreamRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SPEC §5.4 — async deliberation surface.
 *
 * POST /documents/{id}/ask returns 202 immediately with a job_id. The work runs
 * on the deliberation pool and the caller polls GET /jobs/{id} (or, in a
 * future ticket, subscribes to GET /jobs/{id}/stream for SSE).
 */
@RestController
public class AskController {

    private static final int ESTIMATED_DURATION_SECONDS = 30;

    private final AskService asks;
    private final JobStore jobs;
    private final JobStreamRegistry stream;

    public AskController(AskService asks, JobStore jobs, JobStreamRegistry stream) {
        this.asks = asks;
        this.jobs = jobs;
        this.stream = stream;
    }

    @PostMapping("/documents/{documentId}/ask")
    public ResponseEntity<JobAcceptedResponse> ask(@PathVariable UUID documentId,
                                                   @RequestBody AskRequest request) {
        if (request == null || request.query() == null || request.query().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            AskJob job = asks.startAsk(documentId, request.query());
            JobAcceptedResponse body = new JobAcceptedResponse(
                    job.jobId(),
                    job.documentId(),
                    job.status(),
                    "/jobs/" + job.jobId() + "/stream",
                    "/jobs/" + job.jobId(),
                    ESTIMATED_DURATION_SECONDS);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        } catch (IngestException notFound) {
            // startAsk throws this when the document doesn't exist.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<AskJobResponse> get(@PathVariable UUID jobId) {
        return jobs.get(jobId)
                .map(this::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @DeleteMapping("/jobs/{jobId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID jobId) {
        AskJob job = jobs.get(jobId).orElse(null);
        if (job == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        if (!job.isTerminal()) {
            // v0: best-effort cancel — flip status; in-flight model call still completes,
            // result just gets discarded by the orchestrator's terminal-status check.
            job.cancel(Instant.now());
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/jobs/{jobId}/stream", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable UUID jobId) {
        // Long timeout — deliberation can take a while; 0L means "no timeout".
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(15).toMillis());
        return stream.subscribe(jobId, emitter);
    }

    private AskJobResponse toResponse(AskJob job) {
        return new AskJobResponse(
                job.jobId(),
                job.documentId(),
                job.query(),
                job.status(),
                job.startedAt(),
                job.completedAt(),
                job.proposer(),
                job.critic(),
                job.synthesiser(),
                job.status() == JobStatus.COMPLETED ? job.finalResponse() : null,
                job.error());
    }
}
