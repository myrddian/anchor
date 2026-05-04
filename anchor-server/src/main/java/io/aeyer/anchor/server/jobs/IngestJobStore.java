package io.aeyer.anchor.server.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory ingest job store. Same shape as {@link JobStore} for
 * deliberations: terminal jobs hang around for the configured retention
 * window so a slow polling client still sees the final progress envelope,
 * then the watchdog evicts them. Non-durable on purpose for v0; survives
 * neither restart nor multi-instance deployment.
 */
@Component
public class IngestJobStore {

    private static final Logger log = LoggerFactory.getLogger(IngestJobStore.class);

    private final ConcurrentHashMap<UUID, IngestJob> jobs = new ConcurrentHashMap<>();

    @Value("${jobs.retention-after-completion:PT2H}")
    private Duration retention;

    public void put(IngestJob job) {
        jobs.put(job.jobId(), job);
    }

    public Optional<IngestJob> get(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void remove(UUID jobId) {
        jobs.remove(jobId);
    }

    int size() { return jobs.size(); }

    @Scheduled(fixedDelayString = "${jobs.watchdog-interval:PT10M}", initialDelay = 60_000)
    public void evictExpired() {
        Instant cutoff = Instant.now().minus(retention);
        int before = jobs.size();
        jobs.entrySet().removeIf(entry -> {
            IngestJob job = entry.getValue();
            if (!job.isTerminal()) return false;
            Instant completed = job.completedAt();
            return completed != null && completed.isBefore(cutoff);
        });
        int evicted = before - jobs.size();
        if (evicted > 0) {
            log.info("Ingest job watchdog evicted {} expired jobs (retention {})", evicted, retention);
        }
    }
}
