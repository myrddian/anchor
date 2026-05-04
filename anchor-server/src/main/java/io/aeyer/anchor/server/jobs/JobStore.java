package io.aeyer.anchor.server.jobs;

import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.server.persistence.entity.AskJobDbo;
import io.aeyer.anchor.server.persistence.repo.AskJobRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hybrid job store for deliberations.
 *
 * - In-memory map is the live mutable surface — orchestrator threads update
 *   {@link AskJob} fields directly without going through JPA, which keeps the
 *   write path lock-free.
 * - Postgres is the durable mirror — every state transition is written
 *   through via {@link #persist(AskJob)} so a server crash doesn't black-hole
 *   in-flight jobs.
 *
 * On startup we reload terminal jobs as-is and rewrite any non-terminal jobs
 * (PROPOSING / CRITIQUING / SYNTHESISING) as FAILED with an "interrupted by
 * server restart" message — the orchestrator that owned them is dead, the
 * LLM context is gone, but a polling client still gets a real terminal
 * status instead of a 404.
 *
 * Watchdog evicts terminal jobs from both memory and DB after the configured
 * retention window (SPEC §7.6, two hours by default).
 */
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private static final List<JobStatus> TERMINAL = List.of(
            JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED);

    private final ConcurrentHashMap<UUID, AskJob> jobs = new ConcurrentHashMap<>();
    private final AskJobRepository repository;

    @Value("${jobs.retention-after-completion:PT2H}")
    private Duration retention;

    public JobStore(AskJobRepository repository) {
        this.repository = repository;
    }

    /**
     * Reload state from Postgres. Terminal jobs come back as-is so polling
     * clients can still read them; running jobs are rewritten as FAILED
     * because the worker that owned them is gone.
     */
    @PostConstruct
    @Transactional
    public void recoverFromDb() {
        List<AskJobDbo> orphaned = repository.findByStatusNotIn(TERMINAL);
        Instant now = Instant.now();
        for (AskJobDbo dbo : orphaned) {
            dbo.setStatus(JobStatus.FAILED);
            String prev = dbo.getError();
            dbo.setError("Interrupted by server restart"
                    + (prev == null || prev.isEmpty() ? "" : "; previous error: " + prev));
            dbo.setCompletedAt(now);
            dbo.setUpdatedAt(now);
        }
        if (!orphaned.isEmpty()) {
            repository.saveAll(orphaned);
            log.warn("Marked {} ask job(s) as FAILED on restart (interrupted by server restart)",
                    orphaned.size());
        }
        // Hydrate the live map from terminal rows so polling clients hit the
        // map (cheap) before the slower DB lookup fallback.
        for (AskJobDbo dbo : repository.findAll()) {
            jobs.put(dbo.getJobId(), fromDbo(dbo));
        }
        if (!jobs.isEmpty()) {
            log.info("Hydrated {} ask job(s) from Postgres", jobs.size());
        }
    }

    /** Insert + initial persist. Subsequent transitions go through {@link #persist}. */
    public void put(AskJob job) {
        jobs.put(job.jobId(), job);
        persist(job);
    }

    /**
     * Write the job's current state through to Postgres. Called by AskService
     * at every state transition (status flip, agent envelope set, complete /
     * fail). In-memory map is the source of truth between calls.
     */
    @Transactional
    public void persist(AskJob job) {
        try {
            AskJobDbo dbo = toDbo(job);
            repository.save(dbo);
        } catch (Exception e) {
            // Persistence failures must not crash the orchestrator — the
            // worst case is a stale DB row. Log and continue.
            log.warn("Failed to persist ask job {}: {}", job.jobId(), e.getMessage());
        }
    }

    public Optional<AskJob> get(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void update(UUID jobId, UnaryOperator<AskJob> mutator) {
        AskJob job = jobs.get(jobId);
        if (job != null) mutator.apply(job); // mutator mutates in place
    }

    public void remove(UUID jobId) {
        jobs.remove(jobId);
        try {
            repository.deleteById(jobId);
        } catch (Exception e) {
            log.debug("Failed to delete ask job {} from DB: {}", jobId, e.getMessage());
        }
    }

    int size() { return jobs.size(); }

    @Scheduled(fixedDelayString = "${jobs.watchdog-interval:PT10M}", initialDelay = 60_000)
    @Transactional
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(retention);
        // DB first — the source of truth — then mirror in memory so a
        // surviving in-memory entry doesn't outlive its DB row.
        int deleted = repository.deleteTerminalOlderThan(TERMINAL, cutoff);
        int beforeMem = jobs.size();
        jobs.entrySet().removeIf(entry -> {
            AskJob job = entry.getValue();
            if (!job.isTerminal()) return false;
            Instant completed = job.completedAt();
            return completed != null && completed.isBefore(cutoff);
        });
        int evictedMem = beforeMem - jobs.size();
        if (deleted > 0 || evictedMem > 0) {
            log.info("Ask job watchdog evicted {} from DB / {} from memory (retention {})",
                    deleted, evictedMem, retention);
        }
    }

    // ---- DBO ↔ runtime conversion ---------------------------------------

    private static AskJobDbo toDbo(AskJob job) {
        AskJobDbo dbo = new AskJobDbo();
        dbo.setJobId(job.jobId());
        dbo.setDocumentId(job.documentId());
        dbo.setQuery(job.query());
        dbo.setStatus(job.status());
        dbo.setStartedAt(job.startedAt());
        dbo.setCompletedAt(job.completedAt());
        dbo.setUpdatedAt(Instant.now());
        dbo.setFinalResponse(job.finalResponse());
        dbo.setError(job.error());
        dbo.setProposer(job.proposer());
        dbo.setCritic(job.critic());
        dbo.setSynthesiser(job.synthesiser());
        return dbo;
    }

    private static AskJob fromDbo(AskJobDbo dbo) {
        AskJob job = new AskJob(dbo.getJobId(), dbo.getDocumentId(), dbo.getQuery(), dbo.getStartedAt());
        job.transition(dbo.getStatus());
        if (dbo.getProposer() != null) job.setProposer(dbo.getProposer());
        if (dbo.getCritic() != null) job.setCritic(dbo.getCritic());
        if (dbo.getSynthesiser() != null) job.setSynthesiser(dbo.getSynthesiser());
        if (dbo.getStatus() == JobStatus.COMPLETED) {
            job.complete(dbo.getFinalResponse(), dbo.getCompletedAt());
        } else if (dbo.getStatus() == JobStatus.FAILED) {
            job.fail(dbo.getError(), dbo.getCompletedAt());
        } else if (dbo.getStatus() == JobStatus.CANCELLED) {
            job.cancel(dbo.getCompletedAt());
        }
        return job;
    }
}
