package io.aeyer.anchor.server.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.protocol.ask.JobStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JobStoreTest {

    private JobStore store;

    @BeforeEach
    void setUp() {
        store = new JobStore();
        ReflectionTestUtils.setField(store, "retention", Duration.ofMillis(50));
    }

    @Test
    void put_then_get_round_trips() {
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        store.put(job);
        assertThat(store.get(job.jobId())).contains(job);
    }

    @Test
    void get_unknown_returns_empty() {
        assertThat(store.get(UUID.randomUUID())).isEmpty();
    }

    @Test
    void terminal_state_transitions_freeze_status() {
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        job.complete("done", Instant.now());
        assertThat(job.status()).isEqualTo(JobStatus.COMPLETED);
        assertThat(job.isTerminal()).isTrue();
    }

    @Test
    void watchdog_evicts_terminal_jobs_past_retention() throws Exception {
        AskJob old = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q1", Instant.now());
        old.complete("done", Instant.now().minusSeconds(10)); // completed long ago
        store.put(old);

        AskJob fresh = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q2", Instant.now());
        fresh.complete("done", Instant.now()); // just completed
        store.put(fresh);

        AskJob inFlight = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q3", Instant.now());
        store.put(inFlight); // QUEUED — should never be evicted regardless of age

        Thread.sleep(60); // sleep past retention
        store.evictExpired();

        assertThat(store.get(old.jobId())).isEmpty();
        assertThat(store.get(fresh.jobId())).isEmpty(); // also past 50ms retention
        assertThat(store.get(inFlight.jobId())).isPresent();
    }

    @Test
    void watchdog_keeps_in_flight_jobs_indefinitely() {
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        store.put(job);
        store.evictExpired();
        assertThat(store.get(job.jobId())).isPresent();
    }
}
