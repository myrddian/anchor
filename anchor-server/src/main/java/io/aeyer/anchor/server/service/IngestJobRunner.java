package io.aeyer.anchor.server.service;

import io.aeyer.anchor.protocol.ingest.IngestPhase;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.jobs.IngestJobStore;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * In-flight dedup: SHA-256(file bytes) → active IngestJob. Two concurrent
     * uploads of the same file see the same hash and the second caller gets
     * the first job back instead of starting parallel ingest work that would
     * just cascade-delete each other's rows. Cleared on terminal state.
     *
     * Not foolproof — there's a race between the hash lookup and the
     * {@code putIfAbsent}, and a job that finishes after this lookup but
     * before the caller polls won't be deduped. v0 acceptable: the chemist
     * double-clicking upload is the realistic case, not concurrent
     * machine-driven retries.
     */
    private final ConcurrentHashMap<String, IngestJob> inFlightByHash = new ConcurrentHashMap<>();

    public IngestJobRunner(IngestService ingest, IngestJobStore store, WorkerPools pools) {
        this.ingest = ingest;
        this.store = store;
        this.pools = pools;
    }

    /**
     * Mint a job for {@code sourcePath}, schedule it on the ingest pool, and
     * return the job (already in QUEUED → RUNNING). If another job is already
     * processing the exact same file bytes, returns that existing job
     * instead — see {@link #inFlightByHash}.
     */
    public IngestJob submit(String sourcePath) {
        String contentHash = sha256OrNull(Paths.get(sourcePath));
        if (contentHash != null) {
            IngestJob existing = inFlightByHash.get(contentHash);
            if (existing != null && !existing.isTerminal()) {
                log.info("Dedup ingest: {} matches in-flight job {}", sourcePath, existing.jobId());
                return existing;
            }
        }

        IngestJob job = new IngestJob(UUID.randomUUID(), sourcePath, Instant.now());
        store.put(job);
        if (contentHash != null) {
            // Last-writer-wins is fine — both jobs are equivalent work, and
            // the cleanup below removes whichever finishes first.
            inFlightByHash.put(contentHash, job);
        }
        IngestProgressReporter reporter = new JobBackedReporter(job);
        final String hashForCleanup = contentHash;

        CompletableFuture.runAsync(() -> {
            try {
                job.start();
                store.persist(job);
                IngestService.IngestResult result = ingest.runOnCurrentThread(sourcePath, reporter);
                job.complete(result, Instant.now());
                store.persist(job);
                log.info("Ingest job {} completed → document {}", job.jobId(), result.documentId());
            } catch (Throwable t) {
                log.warn("Ingest job {} failed: {}", job.jobId(), t.getMessage(), t);
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                job.fail(msg, Instant.now());
                store.persist(job);
            } finally {
                if (hashForCleanup != null) {
                    // Only clear if WE'RE still the registered job — a later
                    // submit() that bumped the entry should keep its slot.
                    inFlightByHash.remove(hashForCleanup, job);
                }
            }
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
