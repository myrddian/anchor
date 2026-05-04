package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.function.BooleanSupplier;

import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.jobs.IngestJobStore;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Pure-mock unit test for IngestJobRunner — no Spring context, no
 * pgvector, no LLM. Verifies the SHA-256 dedup map: two submits of the
 * same file bytes converge on one job, two submits of distinct content
 * mint two jobs.
 */
class IngestJobRunnerTest {

    @TempDir Path tempDir;

    private IngestService ingest;
    private IngestJobStore store;
    private WorkerPools pools;
    private IngestJobRunner runner;

    @BeforeEach
    void setUp() {
        ingest = mock(IngestService.class);
        store = new IngestJobStore();
        ReflectionTestUtils.setField(store, "retention", Duration.ofHours(2));

        // Hand the runner a real ExecutorService masquerading as the ingest
        // pool — direct-execute would race with the dedup-map check.
        pools = mock(WorkerPools.class);
        when(pools.ingestPool()).thenReturn(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-ingest-worker");
            t.setDaemon(true);
            return t;
        }));

        runner = new IngestJobRunner(ingest, store, pools);
    }

    @Test
    void two_submits_of_identical_file_share_one_job() throws Exception {
        Path file = tempDir.resolve("paper.pdf");
        Files.writeString(file, "%PDF-1.4 same bytes");

        // Hold the worker on a latch so both submits land while the job is
        // still in flight — that's the window dedup actually defends.
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

    private static void waitUntil(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Condition not met within " + timeout);
    }
}
