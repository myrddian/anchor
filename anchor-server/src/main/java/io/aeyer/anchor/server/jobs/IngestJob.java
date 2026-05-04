package io.aeyer.anchor.server.jobs;

import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import io.aeyer.anchor.protocol.ingest.IngestPhase;
import io.aeyer.anchor.server.service.IngestService;
import java.time.Instant;
import java.util.UUID;

/**
 * Mutable server-side state for an async ingest job. Same pattern as
 * {@link AskJob}: orchestrator thread (ingest pool) writes progress fields,
 * request thread reads them via {@code GET /ingest/jobs/{id}}. All mutable
 * fields are {@code volatile} — no compound invariants need locking; each
 * write replaces the previous value entirely.
 *
 * The wire envelope (anchor-protocol's {@code IngestJobResponse}) is
 * produced via a one-shot conversion at the API boundary; this stays in
 * {@code jobs/} and never crosses it.
 */
public class IngestJob {

    private final UUID jobId;
    private final String sourcePath;
    private final String contentHash;     // SHA-256 of file bytes; key for cross-replica dedup
    private final Instant startedAt;
    private volatile Instant updatedAt;
    private volatile Instant completedAt;
    private volatile IngestJobStatus status;
    private volatile IngestPhase phase;
    private volatile int percentComplete;
    private volatile String message;
    private volatile UUID documentId;
    private volatile String title;
    private volatile String error;
    private volatile IngestService.IngestResult result;

    public IngestJob(UUID jobId, String sourcePath, Instant startedAt) {
        this(jobId, sourcePath, null, startedAt);
    }

    public IngestJob(UUID jobId, String sourcePath, String contentHash, Instant startedAt) {
        this.jobId = jobId;
        this.sourcePath = sourcePath;
        this.contentHash = contentHash;
        this.startedAt = startedAt;
        this.updatedAt = startedAt;
        this.status = IngestJobStatus.QUEUED;
        this.phase = IngestPhase.QUEUED;
        this.percentComplete = 0;
    }

    public UUID jobId() { return jobId; }
    public String sourcePath() { return sourcePath; }
    public String contentHash() { return contentHash; }
    public Instant startedAt() { return startedAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant completedAt() { return completedAt; }
    public IngestJobStatus status() { return status; }
    public IngestPhase phase() { return phase; }
    public int percentComplete() { return percentComplete; }
    public String message() { return message; }
    public UUID documentId() { return documentId; }
    public String title() { return title; }
    public String error() { return error; }
    public IngestService.IngestResult result() { return result; }

    /** Move to RUNNING and stamp updated-at. Idempotent. */
    public void start() {
        this.status = IngestJobStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    public void updateProgress(IngestPhase phase, int percent, String message) {
        // Clamp + monotonic guard: never let a stale progress event move the
        // bar backwards (would look broken in the UI even if the underlying
        // work re-tries within a phase).
        int clamped = Math.max(0, Math.min(100, percent));
        if (clamped > this.percentComplete) this.percentComplete = clamped;
        if (phase != null) this.phase = phase;
        if (message != null) this.message = message;
        this.updatedAt = Instant.now();
    }

    public void attachDocument(UUID documentId, String title) {
        this.documentId = documentId;
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public void complete(IngestService.IngestResult result, Instant completedAt) {
        this.result = result;
        this.documentId = result.documentId();
        this.title = result.title();
        this.percentComplete = 100;
        this.phase = IngestPhase.DONE;
        this.status = IngestJobStatus.COMPLETED;
        this.completedAt = completedAt;
        this.updatedAt = completedAt;
    }

    public void fail(String message, Instant completedAt) {
        this.error = message;
        this.status = IngestJobStatus.FAILED;
        this.completedAt = completedAt;
        this.updatedAt = completedAt;
    }

    public void cancel(Instant completedAt) {
        this.status = IngestJobStatus.CANCELLED;
        this.completedAt = completedAt;
        this.updatedAt = completedAt;
    }

    public boolean isTerminal() {
        return status == IngestJobStatus.COMPLETED
                || status == IngestJobStatus.FAILED
                || status == IngestJobStatus.CANCELLED;
    }
}
