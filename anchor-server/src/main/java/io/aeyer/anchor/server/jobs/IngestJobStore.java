package io.aeyer.anchor.server.jobs;

import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import io.aeyer.anchor.server.persistence.entity.IngestJobDbo;
import io.aeyer.anchor.server.persistence.repo.IngestJobRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hybrid job store for ingest jobs. Same shape as {@link JobStore}: in-memory
 * map for the live mutable surface (the orchestrator thread updates volatile
 * fields without going through JPA), Postgres as the durable mirror written
 * through at every meaningful state change.
 *
 * Ingest fires progress updates every paragraph during the long
 * SUMMARISING_PARAGRAPHS phase — that would be hundreds of DB writes per
 * book, so the runner only persists on phase transitions and at terminal
 * status. Sub-phase percent ticks remain in-memory only and are lost on
 * restart, which is acceptable: the running ingest job itself can't survive
 * the restart anyway.
 */
@Component
public class IngestJobStore {

    private static final Logger log = LoggerFactory.getLogger(IngestJobStore.class);
    private static final List<IngestJobStatus> TERMINAL = List.of(
            IngestJobStatus.COMPLETED, IngestJobStatus.FAILED, IngestJobStatus.CANCELLED);

    private final ConcurrentHashMap<UUID, IngestJob> jobs = new ConcurrentHashMap<>();
    private final IngestJobRepository repository;

    @Value("${jobs.retention-after-completion:PT2H}")
    private Duration retention;

    public IngestJobStore(IngestJobRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    @Transactional
    public void recoverFromDb() {
        List<IngestJobDbo> orphaned = repository.findByStatusNotIn(TERMINAL);
        Instant now = Instant.now();
        for (IngestJobDbo dbo : orphaned) {
            dbo.setStatus(IngestJobStatus.FAILED);
            String prev = dbo.getError();
            dbo.setError("Interrupted by server restart"
                    + (prev == null || prev.isEmpty() ? "" : "; previous error: " + prev));
            dbo.setCompletedAt(now);
            dbo.setUpdatedAt(now);
        }
        if (!orphaned.isEmpty()) {
            repository.saveAll(orphaned);
            log.warn("Marked {} ingest job(s) as FAILED on restart (interrupted by server restart)",
                    orphaned.size());
        }
        for (IngestJobDbo dbo : repository.findAll()) {
            jobs.put(dbo.getJobId(), fromDbo(dbo));
        }
        if (!jobs.isEmpty()) {
            log.info("Hydrated {} ingest job(s) from Postgres", jobs.size());
        }
    }

    /**
     * Insert + initial persist. Throws on DB write failure — in particular,
     * the V4 unique-partial-index violation when another in-flight job already
     * owns this content_hash. Callers (IngestJobRunner) catch the exception
     * and converge on the winning job. The in-memory map is rolled back if
     * the persist fails so we don't keep an orphan with no DB row.
     */
    @Transactional
    public void put(IngestJob job) {
        jobs.put(job.jobId(), job);
        try {
            repository.save(toDbo(job));
        } catch (RuntimeException e) {
            jobs.remove(job.jobId());
            throw e;
        }
    }

    /**
     * Write the job's current state through to Postgres. Best-effort — log on
     * failure. Used for ongoing state-update writes (status transitions,
     * progress reports, terminal completion) where a transient DB hiccup
     * shouldn't crash the orchestrator. Initial inserts go through
     * {@link #put} which DOES throw.
     */
    @Transactional
    public void persist(IngestJob job) {
        try {
            repository.save(toDbo(job));
        } catch (Exception e) {
            log.warn("Failed to persist ingest job {}: {}", job.jobId(), e.getMessage());
        }
    }

    public Optional<IngestJob> get(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    /**
     * Cross-replica dedup lookup. Hits the DB rather than the in-memory map
     * because the whole point is to converge across processes — the
     * in-memory map only sees jobs born locally.
     */
    @Transactional(readOnly = true)
    public Optional<IngestJob> findActiveByContentHash(String contentHash) {
        if (contentHash == null) return Optional.empty();
        return repository.findActiveByContentHash(contentHash, TERMINAL)
                .map(dbo -> {
                    // Prefer the in-memory copy if present (live mutable state),
                    // otherwise hydrate from the DB row.
                    IngestJob inMem = jobs.get(dbo.getJobId());
                    return inMem != null ? inMem : fromDbo(dbo);
                });
    }

    public void remove(UUID jobId) {
        jobs.remove(jobId);
        try {
            repository.deleteById(jobId);
        } catch (Exception e) {
            log.debug("Failed to delete ingest job {} from DB: {}", jobId, e.getMessage());
        }
    }

    int size() { return jobs.size(); }

    @Scheduled(fixedDelayString = "${jobs.watchdog-interval:PT10M}", initialDelay = 60_000)
    @Transactional
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = repository.deleteTerminalOlderThan(TERMINAL, cutoff);
        int beforeMem = jobs.size();
        jobs.entrySet().removeIf(entry -> {
            IngestJob job = entry.getValue();
            if (!job.isTerminal()) return false;
            Instant completed = job.completedAt();
            return completed != null && completed.isBefore(cutoff);
        });
        int evictedMem = beforeMem - jobs.size();
        if (deleted > 0 || evictedMem > 0) {
            log.info("Ingest job watchdog evicted {} from DB / {} from memory (retention {})",
                    deleted, evictedMem, retention);
        }
    }

    // ---- DBO ↔ runtime conversion ---------------------------------------

    private static IngestJobDbo toDbo(IngestJob job) {
        IngestJobDbo dbo = new IngestJobDbo();
        dbo.setJobId(job.jobId());
        dbo.setSourcePath(job.sourcePath());
        dbo.setContentHash(job.contentHash());
        dbo.setStatus(job.status());
        dbo.setPhase(job.phase());
        dbo.setPercentComplete(job.percentComplete());
        dbo.setMessage(job.message());
        dbo.setDocumentId(job.documentId());
        dbo.setTitle(job.title());
        dbo.setStartedAt(job.startedAt());
        dbo.setUpdatedAt(job.updatedAt() == null ? Instant.now() : job.updatedAt());
        dbo.setCompletedAt(job.completedAt());
        dbo.setError(job.error());
        dbo.setResult(job.result());
        return dbo;
    }

    private static IngestJob fromDbo(IngestJobDbo dbo) {
        IngestJob job = new IngestJob(dbo.getJobId(), dbo.getSourcePath(),
                dbo.getContentHash(), dbo.getStartedAt());
        // Re-apply the persisted state by walking the same mutators the live
        // path uses — keeps the in-memory and DB shapes consistent.
        if (dbo.getDocumentId() != null) {
            job.attachDocument(dbo.getDocumentId(), dbo.getTitle());
        }
        if (dbo.getStatus() == IngestJobStatus.RUNNING) {
            job.start();
        }
        job.updateProgress(dbo.getPhase(), dbo.getPercentComplete(), dbo.getMessage());
        switch (dbo.getStatus()) {
            case COMPLETED -> {
                if (dbo.getResult() != null) {
                    job.complete(dbo.getResult(), dbo.getCompletedAt());
                }
            }
            case FAILED -> job.fail(dbo.getError(), dbo.getCompletedAt());
            case CANCELLED -> job.cancel(dbo.getCompletedAt());
            default -> { /* QUEUED / RUNNING — no terminal mutation needed */ }
        }
        return job;
    }
}
