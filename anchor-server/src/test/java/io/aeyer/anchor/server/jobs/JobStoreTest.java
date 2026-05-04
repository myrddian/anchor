package io.aeyer.anchor.server.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.server.persistence.entity.AskJobDbo;
import io.aeyer.anchor.server.persistence.repo.AskJobRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JobStoreTest {

    private AskJobRepository repository;
    private JobStore store;

    @BeforeEach
    void setUp() {
        repository = mock(AskJobRepository.class);
        when(repository.findByStatusNotIn(anyList())).thenReturn(List.of());
        when(repository.findAll()).thenReturn(List.of());
        store = new JobStore(repository);
        ReflectionTestUtils.setField(store, "retention", Duration.ofMillis(50));
        store.recoverFromDb(); // would normally fire via @PostConstruct
    }

    @Test
    void put_then_get_round_trips() {
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        store.put(job);
        assertThat(store.get(job.jobId())).contains(job);
        verify(repository).save(any(AskJobDbo.class));
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
    void persist_writes_through_to_repository() {
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        store.put(job);
        job.transition(JobStatus.PROPOSING);
        store.persist(job);
        // Once for put(), once for persist() after transition.
        verify(repository, times(2)).save(any(AskJobDbo.class));
    }

    @Test
    void persist_failures_do_not_throw() {
        lenient().when(repository.save(any(AskJobDbo.class)))
                .thenThrow(new RuntimeException("DB exploded"));
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        // Must not propagate — orchestrator threads can't be killed by a bad write.
        store.put(job);
        store.persist(job);
        assertThat(store.get(job.jobId())).contains(job);
    }

    @Test
    void watchdog_evicts_terminal_jobs_past_retention_in_memory_and_db() throws Exception {
        AskJob old = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q1", Instant.now());
        old.complete("done", Instant.now().minusSeconds(10));
        store.put(old);

        AskJob fresh = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q2", Instant.now());
        fresh.complete("done", Instant.now());
        store.put(fresh);

        AskJob inFlight = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q3", Instant.now());
        store.put(inFlight);

        Thread.sleep(60);
        when(repository.deleteTerminalOlderThan(anyList(), any())).thenReturn(2);
        store.evictExpired();

        assertThat(store.get(old.jobId())).isEmpty();
        assertThat(store.get(fresh.jobId())).isEmpty();
        assertThat(store.get(inFlight.jobId())).isPresent();
        verify(repository).deleteTerminalOlderThan(anyList(), any());
    }

    @Test
    void watchdog_keeps_in_flight_jobs_indefinitely() {
        AskJob job = new AskJob(UUID.randomUUID(), UUID.randomUUID(), "q", Instant.now());
        store.put(job);
        store.evictExpired();
        assertThat(store.get(job.jobId())).isPresent();
    }

    @Test
    void recovery_marks_orphaned_running_jobs_as_failed() {
        AskJobDbo orphaned = new AskJobDbo();
        orphaned.setJobId(UUID.randomUUID());
        orphaned.setDocumentId(UUID.randomUUID());
        orphaned.setQuery("orphan");
        orphaned.setStatus(JobStatus.PROPOSING);
        orphaned.setStartedAt(Instant.now().minusSeconds(60));
        orphaned.setUpdatedAt(Instant.now().minusSeconds(60));

        AskJobRepository freshRepo = mock(AskJobRepository.class);
        when(freshRepo.findByStatusNotIn(anyList())).thenReturn(new java.util.ArrayList<>(List.of(orphaned)));
        when(freshRepo.findAll()).thenReturn(List.of(orphaned));

        JobStore freshStore = new JobStore(freshRepo);
        ReflectionTestUtils.setField(freshStore, "retention", Duration.ofHours(2));
        freshStore.recoverFromDb();

        assertThat(orphaned.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(orphaned.getError()).contains("Interrupted by server restart");
        assertThat(orphaned.getCompletedAt()).isNotNull();
        verify(freshRepo).saveAll(anyList());
    }
}
