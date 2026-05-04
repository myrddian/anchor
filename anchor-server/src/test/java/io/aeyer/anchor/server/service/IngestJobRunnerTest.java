package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.jobs.IngestJobStore;
import io.aeyer.anchor.server.persistence.entity.IngestJobDbo;
import io.aeyer.anchor.server.persistence.repo.IngestJobRepository;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit test for IngestJobRunner with a stateful repository fake that
 * simulates the V4 unique partial index — second non-terminal save with the
 * same content_hash throws DataIntegrityViolationException, like Postgres
 * does. Tests the runner against the real DB-backed dedup semantics without
 * needing a Spring context or Testcontainers.
 */
class IngestJobRunnerTest {

    private static final EnumSet<IngestJobStatus> TERMINAL = EnumSet.of(
            IngestJobStatus.COMPLETED, IngestJobStatus.FAILED, IngestJobStatus.CANCELLED);

    @TempDir Path tempDir;

    private IngestService ingest;
    private IngestJobRepository repo;
    private IngestJobStore store;
    private WorkerPools pools;
    private IngestJobRunner runner;
    private ConcurrentHashMap<UUID, IngestJobDbo> rows;
    private ConcurrentHashMap<String, UUID> activeByHash;

    @BeforeEach
    void setUp() {
        ingest = mock(IngestService.class);
        repo = mock(IngestJobRepository.class);

        // In-memory state for the fake. rows is the table; activeByHash is
        // the unique partial index (non-terminal rows only).
        rows = new ConcurrentHashMap<>();
        activeByHash = new ConcurrentHashMap<>();

        // save() — enforce the partial index: if a different non-terminal
        // row already claims this hash, throw, just like Postgres would.
        when(repo.save(any(IngestJobDbo.class))).thenAnswer(inv -> {
            IngestJobDbo dbo = inv.getArgument(0);
            if (dbo.getContentHash() != null) {
                if (TERMINAL.contains(dbo.getStatus())) {
                    activeByHash.remove(dbo.getContentHash(), dbo.getJobId());
                } else {
                    UUID prior = activeByHash.putIfAbsent(dbo.getContentHash(), dbo.getJobId());
                    if (prior != null && !prior.equals(dbo.getJobId())) {
                        throw new DataIntegrityViolationException(
                                "simulated unique partial index violation on content_hash="
                                        + dbo.getContentHash());
                    }
                }
            }
            rows.put(dbo.getJobId(), dbo);
            return dbo;
        });

        when(repo.findActiveByContentHash(anyString(), anyList())).thenAnswer(inv -> {
            String hash = inv.getArgument(0);
            UUID activeJob = activeByHash.get(hash);
            if (activeJob == null) return Optional.empty();
            IngestJobDbo dbo = rows.get(activeJob);
            if (dbo == null || TERMINAL.contains(dbo.getStatus())) return Optional.empty();
            return Optional.of(dbo);
        });

        when(repo.findByStatusNotIn(anyList())).thenReturn(java.util.List.of());
        when(repo.findAll()).thenReturn(java.util.List.of());

        store = new IngestJobStore(repo);
        ReflectionTestUtils.setField(store, "retention", Duration.ofHours(2));
        store.recoverFromDb();

        // Single-threaded executor masquerading as the ingest pool — keeps
        // ordering deterministic and lets the dedup fast path run before the
        // worker drains the first job.
        pools = mock(WorkerPools.class);
        when(pools.ingestPool()).thenReturn(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-ingest-worker");
            t.setDaemon(true);
            return t;
        }));

        runner = new IngestJobRunner(ingest, store, pools,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    }

    @Test
    void two_submits_of_identical_file_share_one_job() throws Exception {
        Path file = tempDir.resolve("paper.pdf");
        Files.writeString(file, "%PDF-1.4 same bytes");

        // Hold the worker on a latch so the second submit lands while the
        // first is still in flight — the window dedup actually defends.
        CountDownLatch release = new CountDownLatch(1);
        lenient().when(ingest.runOnCurrentThread(anyString(), any())).thenAnswer(inv -> {
            release.await();
            return new IngestService.IngestResult(
                    UUID.randomUUID(), "Paper", inv.getArgument(0),
                    1, 1, 1, 1, Instant.now(),
                    new TokenLedger.Snapshot(0, 0, 0));
        });

        IngestJob first = runner.submit(file.toString());
        IngestJob second = runner.submit(file.toString());

        assertThat(second.jobId()).isEqualTo(first.jobId());
        release.countDown();
        waitUntil(first::isTerminal, Duration.ofSeconds(2));
        assertThat(first.status()).isEqualTo(IngestJobStatus.COMPLETED);
        verify(ingest, times(1)).runOnCurrentThread(anyString(), any());
    }

    @Test
    void distinct_files_get_distinct_jobs() throws Exception {
        Path a = tempDir.resolve("a.pdf");
        Path b = tempDir.resolve("b.pdf");
        Files.writeString(a, "%PDF-1.4 file a");
        Files.writeString(b, "%PDF-1.4 file b");

        when(ingest.runOnCurrentThread(anyString(), any())).thenAnswer(inv -> new IngestService.IngestResult(
                UUID.randomUUID(), "x", inv.getArgument(0),
                1, 1, 1, 1, Instant.now(),
                new TokenLedger.Snapshot(0, 0, 0)));

        IngestJob ja = runner.submit(a.toString());
        IngestJob jb = runner.submit(b.toString());

        assertThat(jb.jobId()).isNotEqualTo(ja.jobId());
        waitUntil(() -> ja.isTerminal() && jb.isTerminal(), Duration.ofSeconds(2));
        verify(ingest, atLeastOnce()).runOnCurrentThread(anyString(), any());
    }

    @Test
    void resubmit_after_terminal_starts_a_new_job() throws Exception {
        Path file = tempDir.resolve("paper.pdf");
        Files.writeString(file, "%PDF-1.4 once");

        when(ingest.runOnCurrentThread(anyString(), any())).thenAnswer(inv -> new IngestService.IngestResult(
                UUID.randomUUID(), "Paper", inv.getArgument(0),
                1, 1, 1, 1, Instant.now(),
                new TokenLedger.Snapshot(0, 0, 0)));

        IngestJob first = runner.submit(file.toString());
        waitUntil(first::isTerminal, Duration.ofSeconds(2));

        IngestJob second = runner.submit(file.toString());
        assertThat(second.jobId())
                .as("dedup must not return a finished job — caller wants fresh ingest")
                .isNotEqualTo(first.jobId());
    }

    @Test
    void losing_the_insert_race_converges_on_the_winner() throws Exception {
        // Models the multi-replica case: replica B's findActiveByContentHash
        // misses (the row didn't exist when B looked), B's insert then loses
        // to replica A on the unique index, B catches the violation and
        // returns A's job. Verifies the catch-and-converge path that the
        // happy-path test never exercises.
        Path file = tempDir.resolve("racey.pdf");
        Files.writeString(file, "%PDF-1.4 race condition");

        // Pre-populate the table as if replica A had just inserted, but
        // intercept findActiveByContentHash for the FIRST call only —
        // simulates the timing window where B's lookup misses but A's row
        // is in fact there for the INSERT to collide with.
        IngestJobDbo aDbo = new IngestJobDbo();
        UUID aJobId = UUID.randomUUID();
        aDbo.setJobId(aJobId);
        aDbo.setSourcePath("/path/from/replica-a.pdf");
        // Same hash that B will compute (SHA-256 of the file bytes).
        aDbo.setContentHash(io.aeyer.anchor.server.service.IngestJobRunnerTest.sha256(file));
        aDbo.setStatus(IngestJobStatus.RUNNING);
        aDbo.setPhase(io.aeyer.anchor.protocol.ingest.IngestPhase.EXTRACTING);
        aDbo.setStartedAt(Instant.now());
        aDbo.setUpdatedAt(Instant.now());
        rows.put(aJobId, aDbo);

        // Make findActiveByContentHash return empty on the first call (B's
        // fast-path lookup races and misses), then real on the second call
        // (after B's INSERT throws and B looks up the winner).
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        when(repo.findActiveByContentHash(anyString(), anyList())).thenAnswer(inv -> {
            if (calls.getAndIncrement() == 0) return Optional.empty();
            return Optional.of(aDbo);
        });
        // Now register A in the activeByHash so B's INSERT collides.
        activeByHash.put(aDbo.getContentHash(), aJobId);

        IngestJob converged = runner.submit(file.toString());

        assertThat(converged.jobId())
                .as("losing replica must converge on the winning job, not its own")
                .isEqualTo(aJobId);
    }

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return java.util.HexFormat.of().formatHex(md.digest());
    }

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}
