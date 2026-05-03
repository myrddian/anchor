package io.aeyer.anchor.server.sse;

import io.aeyer.anchor.protocol.sse.JobEvent;
import io.aeyer.anchor.protocol.sse.JobEventType;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SPEC §5.4 / §7.7 — per-job event log + emitter fan-out.
 *
 * Each job has:
 *   - A monotonic sequence counter for events
 *   - A bounded log of recent events (for reconnect-replay)
 *   - A list of currently-subscribed SSE emitters
 *
 * Backpressure: SSE delivery is fire-and-forget. If an emitter throws on send
 * (the consumer dropped the connection or the buffer filled) we evict it. The
 * deliberation orchestrator never blocks on a slow consumer.
 *
 * Lifecycle: events accumulate while the job is in flight; {@link #close} is
 * called by the orchestrator on terminal status. The {@link io.aeyer.anchor.server.jobs.JobStore}
 * watchdog evicts the job's log alongside the job itself after the retention
 * window expires.
 */
@Component
public class JobStreamRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(JobStreamRegistry.class);
    private static final int MAX_LOG_SIZE = 5_000;

    private final ConcurrentHashMap<UUID, JobChannel> channels = new ConcurrentHashMap<>();

    /** Subscribe a fresh emitter. Replays the existing log first, then forwards live events. */
    public SseEmitter subscribe(UUID jobId, SseEmitter emitter) {
        JobChannel channel = channels.computeIfAbsent(jobId, JobChannel::new);
        channel.attach(emitter);
        return emitter;
    }

    public void emit(UUID jobId, JobEventType type, String token, String response, String error, String status) {
        JobChannel channel = channels.computeIfAbsent(jobId, JobChannel::new);
        long seq = channel.nextSequence();
        JobEvent event = new JobEvent(jobId, seq, type, status, token, response, error, Instant.now());
        channel.append(event);
        channel.fanOut(event);
    }

    public void emitStatus(UUID jobId, String status) {
        emit(jobId, JobEventType.STATUS, null, null, null, status);
    }

    public void emitToken(UUID jobId, JobEventType type, String token) {
        emit(jobId, type, token, null, null, null);
    }

    public void emitAgentComplete(UUID jobId, JobEventType type, String response) {
        emit(jobId, type, null, response, null, null);
    }

    public void emitFinal(UUID jobId, String response) {
        emit(jobId, JobEventType.COMPLETED, null, response, null, null);
    }

    public void emitFailure(UUID jobId, String message) {
        emit(jobId, JobEventType.FAILED, null, null, message, null);
    }

    /** Close all emitters for the job. Log stays until {@link #remove}. */
    public void close(UUID jobId) {
        JobChannel channel = channels.get(jobId);
        if (channel != null) channel.close();
    }

    /** Drop both emitters and event log (called by the JobStore watchdog). */
    public void remove(UUID jobId) {
        JobChannel channel = channels.remove(jobId);
        if (channel != null) channel.close();
    }

    int activeEmitters(UUID jobId) {
        JobChannel channel = channels.get(jobId);
        return channel == null ? 0 : channel.emitterCount();
    }

    int eventCount(UUID jobId) {
        JobChannel channel = channels.get(jobId);
        return channel == null ? 0 : channel.eventCount();
    }

    /** Encapsulates one job's emitter list + bounded event log. */
    private static class JobChannel {
        private final UUID jobId;
        private final AtomicLong sequence = new AtomicLong(0);
        private final List<JobEvent> log = new CopyOnWriteArrayList<>();
        private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

        JobChannel(UUID jobId) { this.jobId = jobId; }

        long nextSequence() { return sequence.incrementAndGet(); }

        void append(JobEvent event) {
            log.add(event);
            // Trim oldest if the log balloons (e.g. a runaway streaming response).
            while (log.size() > MAX_LOG_SIZE) {
                try { log.remove(0); } catch (IndexOutOfBoundsException ignored) { break; }
            }
        }

        void attach(SseEmitter emitter) {
            // Replay existing log to the new subscriber. After this, the emitter is
            // registered; subsequent fanOut() calls reach it too.
            for (JobEvent event : new ArrayList<>(log)) {
                if (!sendOrDrop(emitter, event)) return;
            }
            emitter.onCompletion(() -> emitters.remove(emitter));
            emitter.onTimeout(() -> emitters.remove(emitter));
            emitter.onError(t -> emitters.remove(emitter));
            emitters.add(emitter);
        }

        void fanOut(JobEvent event) {
            for (SseEmitter emitter : emitters) {
                if (!sendOrDrop(emitter, event)) {
                    emitters.remove(emitter);
                }
            }
        }

        void close() {
            for (SseEmitter emitter : emitters) {
                try { emitter.complete(); } catch (Exception ignored) { /* already gone */ }
            }
            emitters.clear();
        }

        int emitterCount() { return emitters.size(); }
        int eventCount() { return log.size(); }

        private boolean sendOrDrop(SseEmitter emitter, JobEvent event) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name().toLowerCase())
                        .id(String.valueOf(event.sequence()))
                        .data(event));
                return true;
            } catch (IOException | IllegalStateException e) {
                LOG.debug("Dropping SSE subscriber for job {} (sequence {}): {}",
                        jobId, event.sequence(), e.getMessage());
                try { emitter.complete(); } catch (Exception ignored) {}
                return false;
            }
        }
    }
}
