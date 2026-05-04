package io.aeyer.anchor.client;

import io.aeyer.anchor.client.exceptions.AnchorClientException;
import io.aeyer.anchor.client.internal.HttpTransport;
import io.aeyer.anchor.protocol.ask.AskJobResponse;
import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.protocol.sse.JobEvent;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * Interactive handle for a deliberation in flight. Provides three orthogonal
 * ways to consume progress:
 *   - {@link #status()} / {@link #snapshot()} for polling
 *   - {@link #subscribe} for live SSE token streaming
 *   - {@link #await} for blocking-until-done
 *
 * Plus {@link #cancel()} for best-effort termination.
 */
public final class AskHandle {

    private final UUID jobId;
    private final UUID documentId;
    private final HttpTransport transport;
    private final AtomicReference<EventSource> activeStream = new AtomicReference<>();

    AskHandle(UUID jobId, UUID documentId, HttpTransport transport) {
        this.jobId = jobId;
        this.documentId = documentId;
        this.transport = transport;
    }

    public UUID jobId() { return jobId; }
    public UUID documentId() { return documentId; }

    /** Current status (one network call). */
    public JobStatus status() {
        return snapshot().status();
    }

    /** Full envelope (one network call). */
    public AskJobResponse snapshot() {
        return transport.get("/jobs/" + jobId, AskJobResponse.class);
    }

    /**
     * Subscribe to the SSE event stream. The handler runs on OkHttp's dispatcher
     * thread per event. Returns immediately; cancel via {@link #cancel()} or by
     * letting the deliberation finish (server emits COMPLETED then closes).
     */
    public void subscribe(Consumer<JobEvent> handler) {
        Request.Builder builder = new Request.Builder()
                .url(transport.baseUrl() + "/jobs/" + jobId + "/stream")
                .header("Accept", "text/event-stream");
        // SSE goes around HttpTransport's helpers, so apply API auth here too.
        if (transport.apiToken() != null && !transport.apiToken().isBlank()) {
            builder.header("Authorization", "Bearer " + transport.apiToken());
        }
        Request request = builder.build();
        EventSource source = EventSources.createFactory(transport.httpClient())
                .newEventSource(request, new EventSourceListener() {
                    @Override
                    public void onEvent(EventSource src, String id, String type, String data) {
                        try {
                            JobEvent event = transport.mapper().readValue(data, JobEvent.class);
                            handler.accept(event);
                        } catch (IOException ignored) {
                            // Ignore malformed events — the snapshot endpoint is the source of truth.
                        }
                    }

                    @Override
                    public void onClosed(EventSource src) { activeStream.compareAndSet(src, null); }

                    @Override
                    public void onFailure(EventSource src, Throwable t, Response response) {
                        if (response != null) response.close();
                        activeStream.compareAndSet(src, null);
                    }
                });
        activeStream.set(source);
    }

    /**
     * Block until the job reaches a terminal state or {@code timeout} elapses.
     * Polls {@code GET /jobs/{id}} every 250ms — fine for the v0 polling path;
     * use {@link #subscribe} for token-level streaming.
     */
    public AskJobResponse await(Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            AskJobResponse snap = snapshot();
            if (snap.status() == JobStatus.COMPLETED
                    || snap.status() == JobStatus.FAILED
                    || snap.status() == JobStatus.CANCELLED) {
                return snap;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AnchorClientException("Interrupted while awaiting deliberation", e);
            }
        }
        throw new AnchorClientException("Deliberation did not complete within " + timeout);
    }

    /** Best-effort cancel. Server flips status; in-flight model call still completes. */
    public void cancel() {
        EventSource source = activeStream.getAndSet(null);
        if (source != null) source.cancel();
        transport.delete("/jobs/" + jobId);
    }
}
