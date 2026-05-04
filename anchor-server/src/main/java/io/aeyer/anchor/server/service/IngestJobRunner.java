package io.aeyer.anchor.server.service;

import io.aeyer.anchor.protocol.ingest.IngestPhase;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.jobs.IngestJobStore;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates async ingest jobs. The HTTP controllers call {@link #submit}
 * which mints a job, schedules the pipeline on {@link WorkerPools#ingestPool()},
 * and returns immediately so the request thread doesn't block on a long
 * book ingest. The polling client watches progress via
 * {@code GET /ingest/jobs/{id}}.
 *
 * Pipeline state lives on the {@link IngestJob} itself, mutated through a
 * {@link IngestProgressReporter} that wraps the job — same pattern the
 * deliberation code uses for {@link io.aeyer.anchor.server.jobs.AskJob}.
 */
@Service
public class IngestJobRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestJobRunner.class);

    private final IngestService ingest;
    private final IngestJobStore store;
    private final WorkerPools pools;

    public IngestJobRunner(IngestService ingest, IngestJobStore store, WorkerPools pools) {
        this.ingest = ingest;
        this.store = store;
        this.pools = pools;
    }

    /**
     * Mint a job for {@code sourcePath}, schedule it on the ingest pool, and
     * return the job (already in QUEUED → RUNNING). The caller should respond
     * 202 with the job id; the work continues on the pool independent of the
     * HTTP request lifetime.
     */
    public IngestJob submit(String sourcePath) {
        IngestJob job = new IngestJob(UUID.randomUUID(), sourcePath, Instant.now());
        store.put(job);
        IngestProgressReporter reporter = new JobBackedReporter(job);

        CompletableFuture.runAsync(() -> {
            try {
                job.start();
                IngestService.IngestResult result = ingest.runOnCurrentThread(sourcePath, reporter);
                job.complete(result, Instant.now());
                log.info("Ingest job {} completed → document {}", job.jobId(), result.documentId());
            } catch (Throwable t) {
                log.warn("Ingest job {} failed: {}", job.jobId(), t.getMessage(), t);
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                job.fail(msg, Instant.now());
            }
        }, pools.ingestPool());

        return job;
    }

    /** Adapter — funnels reporter callbacks straight into the job. */
    private static final class JobBackedReporter implements IngestProgressReporter {
        private final IngestJob job;

        JobBackedReporter(IngestJob job) { this.job = job; }

        @Override
        public void report(IngestPhase phase, int percent, String message) {
            job.updateProgress(phase, percent, message);
        }

        @Override
        public void attachDocument(UUID documentId, String title) {
            job.attachDocument(documentId, title);
        }
    }
}
