package io.aeyer.anchor.server.workers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkerPoolsTest {

    private WorkerPools pools;

    @BeforeEach
    void setUp() {
        WorkerPoolProperties props = new WorkerPoolProperties();
        // Override defaults to match SPEC §7.9 explicitly here, matching application.yml.
        props.setChat(new WorkerPoolProperties.PoolConfig(1));
        props.setEmbedding(new WorkerPoolProperties.PoolConfig(2));
        props.setDeliberation(new WorkerPoolProperties.PoolConfig(4));
        props.setIngest(new WorkerPoolProperties.PoolConfig(1));
        props.setShutdownTimeoutSeconds(5);
        pools = new WorkerPools(props);
        pools.init();
    }

    @AfterEach
    void tearDown() {
        if (pools != null) pools.shutdown();
    }

    @Test
    void thread_names_match_pool_for_log_correlation() throws Exception {
        assertThreadNamePrefix(pools.chatPool(), "chat-worker-");
        assertThreadNamePrefix(pools.embeddingPool(), "embedding-worker-");
        assertThreadNamePrefix(pools.deliberationPool(), "deliberation-worker-");
        assertThreadNamePrefix(pools.ingestPool(), "ingest-worker-");
    }

    @Test
    void pool_sizes_come_from_properties() {
        assertThat(((ThreadPoolExecutor) pools.chatPool()).getMaximumPoolSize()).isEqualTo(1);
        assertThat(((ThreadPoolExecutor) pools.embeddingPool()).getMaximumPoolSize()).isEqualTo(2);
        assertThat(((ThreadPoolExecutor) pools.deliberationPool()).getMaximumPoolSize()).isEqualTo(4);
        assertThat(((ThreadPoolExecutor) pools.ingestPool()).getMaximumPoolSize()).isEqualTo(1);
    }

    @Test
    void shutdown_drains_orchestration_pools_before_inference_pools() throws Exception {
        CountDownLatch chatStarted = new CountDownLatch(1);
        CountDownLatch releaseChat = new CountDownLatch(1);

        // Hold a chat task open so the inference pool cannot terminate immediately.
        pools.chatPool().submit(() -> {
            chatStarted.countDown();
            releaseChat.await();
            return null;
        });
        assertThat(chatStarted.await(2, TimeUnit.SECONDS)).isTrue();

        Thread shutdownThread = new Thread(() -> pools.shutdown(), "shutdown-runner");
        shutdownThread.start();

        // Orchestration pools should reach isShutdown() while chat is still blocked.
        // Use a tight poll because shutdown order is the contract under test.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline
                && !(pools.deliberationPool().isShutdown() && pools.ingestPool().isShutdown())) {
            Thread.sleep(5);
        }
        assertThat(pools.deliberationPool().isShutdown())
                .as("deliberation pool drained first")
                .isTrue();
        assertThat(pools.ingestPool().isShutdown())
                .as("ingest pool drained first")
                .isTrue();
        assertThat(pools.chatPool().isTerminated())
                .as("chat (inference) pool still running while orchestration drains")
                .isFalse();

        releaseChat.countDown();
        shutdownThread.join(TimeUnit.SECONDS.toMillis(10));
        assertThat(pools.chatPool().isTerminated()).isTrue();
        assertThat(pools.embeddingPool().isTerminated()).isTrue();
    }

    @Test
    void duplicate_ingest_for_same_document_returns_same_future() throws Exception {
        UUID docId = UUID.randomUUID();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        Future<String> first = pools.submitIngest(docId, () -> {
            executions.incrementAndGet();
            release.await();
            return "done";
        });
        Future<String> second = pools.submitIngest(docId, () -> {
            executions.incrementAndGet();
            return "should-not-run";
        });

        assertThat(second).isSameAs(first);
        assertThat(pools.isIngestInFlight(docId)).isTrue();

        release.countDown();
        assertThat(first.get(2, TimeUnit.SECONDS)).isEqualTo("done");
        assertThat(executions.get()).isEqualTo(1);

        // After completion the dedup key is released so a fresh submission runs again.
        long settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pools.isIngestInFlight(docId) && System.nanoTime() < settleDeadline) {
            Thread.sleep(5);
        }
        assertThat(pools.isIngestInFlight(docId)).isFalse();

        Future<String> third = pools.submitIngest(docId, () -> "fresh");
        assertThat(third).isNotSameAs(first);
        assertThat(third.get(2, TimeUnit.SECONDS)).isEqualTo("fresh");
    }

    @Test
    void ingest_failure_releases_dedup_slot() throws Exception {
        UUID docId = UUID.randomUUID();
        Future<String> failing = pools.submitIngest(docId, () -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> failing.get(2, TimeUnit.SECONDS))
                .isInstanceOfAny(ExecutionException.class, CancellationException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        long settleDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (pools.isIngestInFlight(docId) && System.nanoTime() < settleDeadline) {
            Thread.sleep(5);
        }
        assertThat(pools.isIngestInFlight(docId)).isFalse();
    }

    private static void assertThreadNamePrefix(ExecutorService pool, String prefix) throws Exception {
        String name = pool.submit(() -> Thread.currentThread().getName()).get(2, TimeUnit.SECONDS);
        assertThat(name).startsWith(prefix);
    }
}
