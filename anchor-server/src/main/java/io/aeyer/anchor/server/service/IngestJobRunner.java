package io.aeyer.anchor.server.service;

import io.aeyer.anchor.protocol.ingest.IngestPhase;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.jobs.IngestJobStore;
import io.aeyer.anchor.server.workers.WorkerPools;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final MeterRegistry meters;

    public IngestJobRunner(IngestService ingest, IngestJobStore store,
                           WorkerPools pools, MeterRegistry meters) {
        this.ingest = ingest;
        this.store = store;
        this.pools = pools;
        this.meters = meters;
    }

    /**
     * Mint a job for {@code sourcePath}, schedule it on the ingest pool, and
     * return the job (already in QUEUED → RUNNING).
     *
     * Cross-replica dedup via Postgres: V4__ingest_dedup.sql defines a unique
     * partial index on {@code ingest_jobs.content_hash} where the row is
     * non-terminal. Two concurrent submits of the same file bytes — whether
     * on the same JVM or two replicas pointed at the same DB — collide on
     * that index; the loser catches DataIntegrityViolationException and
     * looks up the winner's job_id. Old per-process ConcurrentHashMap is
     * gone — the DB is the single source of truth.
     *
     * Hash-failure path (unreadable file etc.): we skip dedup, mint a fresh
     * job, and let the downstream extractor surface the real error. Better
     * than failing the whole request just because we couldn't fingerprint.
     */
    public IngestJob submit(String sourcePath) {
        String contentHash = sha256OrNull(Paths.get(sourcePath));

        if (contentHash != null) {
            // Fast path: the active job already exists, return it without an
            // INSERT attempt. Cheap because the index makes this a 1-row read.
            Optional<IngestJob> existing = store.findActiveByContentHash(contentHash);
            if (existing.isPresent()) {
                log.info("Dedup ingest: {} matches in-flight job {}", sourcePath, existing.get().jobId());
                return existing.get();
            }
        }

        IngestJob job = new IngestJob(UUID.randomUUID(), sourcePath, contentHash, Instant.now());
        try {
            store.put(job);
        } catch (DataIntegrityViolationException race) {
            // Lost the race to another concurrent submitter (same JVM via
            // a different request thread, or a sibling replica). The winner
            // owns the active row keyed by content_hash; fetch and return it.
            if (contentHash == null) throw race; // dedup wasn't engaged → real bug, propagate
            Optional<IngestJob> winner = store.findActiveByContentHash(contentHash);
            if (winner.isPresent()) {
                log.info("Dedup ingest race: {} converged on winning job {}",
                        sourcePath, winner.get().jobId());
                return winner.get();
            }
            // The winner finished and got cleaned up between our INSERT and
            // our follow-up SELECT — vanishingly rare; surface the original.
            throw race;
        }

        meters.counter("anchor.ingest.jobs.started").increment();
        IngestProgressReporter reporter = new JobBackedReporter(job);
        Timer.Sample sample = Timer.start(meters);
        CompletableFuture.runAsync(() -> {
            String outcome = "failed";
            try {
                job.start();
                store.persist(job);
                IngestService.IngestResult result = ingest.runOnCurrentThread(sourcePath, reporter);
                job.complete(result, Instant.now());
                store.persist(job);
                outcome = "completed";
                log.info("Ingest job {} completed → document {}", job.jobId(), result.documentId());
            } catch (Throwable t) {
                log.warn("Ingest job {} failed: {}", job.jobId(), t.getMessage(), t);
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                job.fail(msg, Instant.now());
                store.persist(job);
            } finally {
                sample.stop(meters.timer("anchor.ingest.duration", "outcome", outcome));
                meters.counter("anchor.ingest.jobs.completed", "outcome", outcome).increment();
            }
            // No in-memory cleanup needed — the unique partial index
            // automatically frees the content_hash slot when status flips
            // to a terminal value.
        }, pools.ingestPool());

        return job;
    }

    /**
     * Best-effort SHA-256 of file bytes. Returns null on I/O error so we fall
     * back to the no-dedup path rather than failing the whole request — the
     * downstream extractor will surface a more useful error if the file is
     * actually unreadable.
     */
    private static String sha256OrNull(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) {
            log.debug("SHA-256 dedup hash failed for {}: {}", file, e.getMessage());
            return null;
        }
    }

    /**
     * Adapter — funnels reporter callbacks straight into the job, and
     * persists on phase transitions only. Per-percent ticks remain in
     * memory only: paragraph summarisation can fire 100+ progress events
     * per book, persisting every one would burn DB writes for state that
     * can't survive a server restart anyway.
     */
    private final class JobBackedReporter implements IngestProgressReporter {
        private final IngestJob job;
        private volatile IngestPhase lastPersistedPhase;

        JobBackedReporter(IngestJob job) {
            this.job = job;
            this.lastPersistedPhase = job.phase();
        }

        @Override
        public void report(IngestPhase phase, int percent, String message) {
            job.updateProgress(phase, percent, message);
            if (phase != null && phase != lastPersistedPhase) {
                lastPersistedPhase = phase;
                store.persist(job);
            }
        }

        @Override
        public void attachDocument(UUID documentId, String title) {
            job.attachDocument(documentId, title);
            store.persist(job);
        }
    }
}
