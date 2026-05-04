package io.aeyer.anchor.client;

import io.aeyer.anchor.client.exceptions.AnchorClientException;
import io.aeyer.anchor.client.internal.HttpTransport;
import io.aeyer.anchor.protocol.ingest.IngestJobResponse;
import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Live handle for an async ingest job. Mirrors {@link AskHandle}: poll via
 * {@link #snapshot()} / {@link #status()}, block via
 * {@link #awaitCompletion(Duration)}, watch progress with
 * {@link #awaitCompletion(Duration, Consumer)}.
 *
 * Long books take minutes; the server runs the pipeline on the ingest pool
 * and exposes percentage progress through {@code GET /ingest/jobs/{id}}.
 */
public final class IngestHandle {

    private final UUID jobId;
    private final HttpTransport transport;

    IngestHandle(UUID jobId, HttpTransport transport) {
        this.jobId = jobId;
        this.transport = transport;
    }

    public UUID jobId() { return jobId; }

    /** One network call — returns the full progress envelope. */
    public IngestJobResponse snapshot() {
        return transport.get("/ingest/jobs/" + jobId, IngestJobResponse.class);
    }

    public IngestJobStatus status() {
        return snapshot().status();
    }

    /** Block until terminal or timeout. No progress callback. */
    public IngestJobResponse awaitCompletion(Duration timeout) {
        return awaitCompletion(timeout, null);
    }

    /**
     * Block until terminal or timeout. Fires {@code onProgress} after each
     * poll so callers can update a progress bar / log line. Polls every 1s
     * — ingest is minutes-long, faster polling just hammers the server.
     */
    public IngestJobResponse awaitCompletion(Duration timeout, Consumer<IngestJobResponse> onProgress) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            IngestJobResponse snap = snapshot();
            if (onProgress != null) onProgress.accept(snap);
            if (snap.status() == IngestJobStatus.COMPLETED
                    || snap.status() == IngestJobStatus.FAILED
                    || snap.status() == IngestJobStatus.CANCELLED) {
                return snap;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnchorClientException("Interrupted while awaiting ingest", e);
            }
        }
        throw new AnchorClientException("Ingest did not complete within " + timeout);
    }
}
