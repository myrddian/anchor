package io.aeyer.anchor.protocol.ingest;

/**
 * Lifecycle status of an async ingest job. Mirrors {@code JobStatus} for
 * deliberations: lifecycle states are coarse, the {@link IngestPhase} field
 * carries the fine-grained "what is the worker doing right now" signal.
 */
public enum IngestJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
