package io.aeyer.anchor.server.sse;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.protocol.sse.JobEventType;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class JobStreamRegistryTest {

    private JobStreamRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new JobStreamRegistry();
    }

    @Test
    void emit_appends_to_log_and_assigns_monotonic_sequences() {
        UUID jobId = UUID.randomUUID();
        registry.emitStatus(jobId, "PROPOSING");
        registry.emitToken(jobId, JobEventType.PROPOSER_THOUGHT, "tok");
        registry.emitFinal(jobId, "done");

        assertThat(registry.eventCount(jobId)).isEqualTo(3);
    }

    @Test
    void subscribe_replays_existing_events_then_attaches_for_live_fanout() throws Exception {
        UUID jobId = UUID.randomUUID();
        registry.emitStatus(jobId, "PROPOSING");
        registry.emitToken(jobId, JobEventType.PROPOSER_THOUGHT, "tok-1");

        AtomicInteger sentCount = new AtomicInteger();
        SseEmitter emitter = countingEmitter(sentCount);
        registry.subscribe(jobId, emitter);

        // Replay should have hit the emitter twice already.
        assertThat(sentCount.get()).isEqualTo(2);

        registry.emitToken(jobId, JobEventType.PROPOSER_THOUGHT, "tok-2");
        assertThat(sentCount.get()).isEqualTo(3);
        assertThat(registry.activeEmitters(jobId)).isEqualTo(1);
    }

    @Test
    void multiple_subscribers_each_receive_live_events() throws Exception {
        UUID jobId = UUID.randomUUID();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        registry.subscribe(jobId, countingEmitter(a));
        registry.subscribe(jobId, countingEmitter(b));

        registry.emitStatus(jobId, "CRITIQUING");
        registry.emitToken(jobId, JobEventType.SYNTHESISER_THOUGHT, "tok");

        assertThat(a.get()).isEqualTo(2);
        assertThat(b.get()).isEqualTo(2);
    }

    @Test
    void slow_subscriber_dropped_on_send_failure_does_not_break_others() throws Exception {
        UUID jobId = UUID.randomUUID();
        AtomicInteger healthyCount = new AtomicInteger();
        SseEmitter healthy = countingEmitter(healthyCount);
        SseEmitter dead = throwingEmitter();

        registry.subscribe(jobId, healthy);
        registry.subscribe(jobId, dead);

        registry.emitFinal(jobId, "done");

        assertThat(healthyCount.get()).isEqualTo(1);
        assertThat(registry.activeEmitters(jobId)).isEqualTo(1); // dead one evicted
    }

    @Test
    void close_completes_emitters_but_keeps_log_for_subsequent_replay() {
        UUID jobId = UUID.randomUUID();
        AtomicInteger count = new AtomicInteger();
        registry.subscribe(jobId, countingEmitter(count));
        registry.emitStatus(jobId, "PROPOSING");

        registry.close(jobId);

        assertThat(registry.activeEmitters(jobId)).isZero();
        assertThat(registry.eventCount(jobId)).isEqualTo(1);
    }

    @Test
    void remove_drops_log_and_emitters_so_no_replay_after_eviction() throws Exception {
        UUID jobId = UUID.randomUUID();
        registry.emitStatus(jobId, "QUEUED");
        registry.remove(jobId);

        AtomicInteger count = new AtomicInteger();
        registry.subscribe(jobId, countingEmitter(count));
        // Fresh channel, empty log.
        assertThat(count.get()).isZero();
        assertThat(registry.eventCount(jobId)).isZero();
    }

    private SseEmitter countingEmitter(AtomicInteger counter) {
        return new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) {
                counter.incrementAndGet();
            }
        };
    }

    private SseEmitter throwingEmitter() {
        return new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                throw new IOException("simulated dead consumer");
            }
        };
    }
}
