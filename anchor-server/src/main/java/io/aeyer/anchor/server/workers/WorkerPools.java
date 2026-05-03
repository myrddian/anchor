package io.aeyer.anchor.server.workers;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Named worker pools per SPEC §7.9. Pools segment by inference resource, not role:
 * one chat slot (single Gemma instance), two embedding slots (nomic), plus
 * orchestration pools `deliberation` and `ingest` that submit into the inference
 * pools and block on .get(). Orchestrator threads are cheap and mostly waiting on
 * chat — orchestration concurrency and inference concurrency tune separately.
 *
 * Shutdown drains orchestration pools first so in-flight orchestrators stop
 * submitting before inference pools close.
 */
@Service
public class WorkerPools {

    private static final Logger log = LoggerFactory.getLogger(WorkerPools.class);

    static final String CHAT = "chat";
    static final String EMBEDDING = "embedding";
    static final String DELIBERATION = "deliberation";
    static final String INGEST = "ingest";

    private static final List<String> ORCHESTRATION_POOLS = List.of(DELIBERATION, INGEST);
    private static final List<String> INFERENCE_POOLS = List.of(CHAT, EMBEDDING);

    private final WorkerPoolProperties properties;
    private final Map<String, ExecutorService> pools = new LinkedHashMap<>();
    private final ConcurrentHashMap<UUID, Future<?>> inFlightIngests = new ConcurrentHashMap<>();

    public WorkerPools(WorkerPoolProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        pools.put(CHAT, createPool(CHAT, properties.getChat().getPoolSize()));
        pools.put(EMBEDDING, createPool(EMBEDDING, properties.getEmbedding().getPoolSize()));
        pools.put(DELIBERATION, createPool(DELIBERATION, properties.getDeliberation().getPoolSize()));
        pools.put(INGEST, createPool(INGEST, properties.getIngest().getPoolSize()));
    }

    private ExecutorService createPool(String name, int size) {
        int poolSize = Math.max(1, size);
        AtomicInteger counter = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, name + "-worker-" + counter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        log.info("Worker pool '{}' initialised with {} threads", name, poolSize);
        return pool;
    }

    public ExecutorService chatPool() { return pools.get(CHAT); }
    public ExecutorService embeddingPool() { return pools.get(EMBEDDING); }
    public ExecutorService deliberationPool() { return pools.get(DELIBERATION); }
    public ExecutorService ingestPool() { return pools.get(INGEST); }

    /**
     * Submit an ingest task keyed by document id. If an ingest is already in
     * flight for the same document, returns the existing Future — duplicate
     * submissions converge on the same result rather than re-running work.
     */
    @SuppressWarnings("unchecked")
    public <T> Future<T> submitIngest(UUID documentId, Callable<T> task) {
        return (Future<T>) inFlightIngests.computeIfAbsent(documentId, id -> {
            FutureTask<T> ft = new FutureTask<>(() -> {
                try {
                    return task.call();
                } finally {
                    inFlightIngests.remove(id);
                }
            });
            ingestPool().execute(ft);
            return ft;
        });
    }

    boolean isIngestInFlight(UUID documentId) {
        return inFlightIngests.containsKey(documentId);
    }

    @PreDestroy
    public void shutdown() {
        // Orchestration pools first: stop new orchestrators submitting into chat/embedding.
        ORCHESTRATION_POOLS.forEach(this::drain);
        // Then inference pools: any in-flight LLM/embedding calls finish or time out.
        INFERENCE_POOLS.forEach(this::drain);
    }

    private void drain(String name) {
        ExecutorService pool = pools.get(name);
        if (pool == null) return;
        pool.shutdown();
        try {
            int timeout = properties.getShutdownTimeoutSeconds();
            if (!pool.awaitTermination(timeout, TimeUnit.SECONDS)) {
                int dropped = pool.shutdownNow().size();
                log.warn("Worker pool '{}' did not drain in {}s; forced shutdown, {} tasks dropped",
                        name, timeout, dropped);
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker pool '{}' shut down", name);
    }
}
