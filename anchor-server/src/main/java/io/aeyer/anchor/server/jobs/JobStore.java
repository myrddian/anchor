package io.aeyer.anchor.server.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory deliberation job store (SPEC §7.6). v0 only — durable Postgres-
 * backed jobs are a v1 problem; server restart wipes in-flight deliberations.
 *
 * Lifecycle: jobs hang around for {@code jobs.retention-after-completion}
 * after entering a terminal state, then the watchdog evicts them.
 */
@Component
public class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);

    private final ConcurrentHashMap<UUID, AskJob> jobs = new ConcurrentHashMap<>();

    @Value("${jobs.retention-after-completion:2h}")
    private Duration retention;

    public void put(AskJob job) {
        jobs.put(job.jobId(), job);
    }

    public Optional<AskJob> get(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void update(UUID jobId, UnaryOperator<AskJob> mutator) {
        AskJob job = jobs.get(jobId);
        if (job != null) mutator.apply(job); // mutator mutates the job in-place
    }

    public void remove(UUID jobId) {
        jobs.remove(jobId);
    }

    int size() { return jobs.size(); }

    @Scheduled(fixedDelayString = "${jobs.watchdog-interval:10m}", initialDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(retention);
        int before = jobs.size();
        jobs.entrySet().removeIf(entry -> {
            AskJob job = entry.getValue();
            if (!job.isTerminal()) return false;
            Instant completed = job.completedAt();
            return completed != null && completed.isBefore(cutoff);
        });
        int evicted = before - jobs.size();
        if (evicted > 0) {
            log.info("Job watchdog evicted {} expired jobs (retention {})", evicted, retention);
        }
    }
}
