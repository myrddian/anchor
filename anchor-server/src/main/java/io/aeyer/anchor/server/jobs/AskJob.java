package io.aeyer.anchor.server.jobs;

import io.aeyer.anchor.protocol.ask.AgentEnvelope;
import io.aeyer.anchor.protocol.ask.JobStatus;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable server-side state for a deliberation. The wire envelope
 * ({@code AskJobResponse}) is produced via a one-shot conversion at the API
 * boundary — this stays in {@code jobs/} and never crosses it.
 *
 * Fields are {@code volatile} / {@link AtomicReference} so the orchestrator
 * thread (deliberation pool) can update progress while the request thread
 * reads it without locks. There's no compound invariant — each agent slot is
 * written exactly once on completion.
 */
public class AskJob {

    private final UUID jobId;
    private final UUID documentId;
    private final String query;
    private final Instant startedAt;
    private volatile Instant completedAt;
    private volatile JobStatus status;
    private volatile String finalResponse;
    private volatile String error;
    private final AtomicReference<AgentEnvelope> proposer = new AtomicReference<>();
    private final AtomicReference<AgentEnvelope> critic = new AtomicReference<>();
    private final AtomicReference<AgentEnvelope> synthesiser = new AtomicReference<>();

    public AskJob(UUID jobId, UUID documentId, String query, Instant startedAt) {
        this.jobId = jobId;
        this.documentId = documentId;
        this.query = query;
        this.startedAt = startedAt;
        this.status = JobStatus.QUEUED;
    }

    public UUID jobId() { return jobId; }
    public UUID documentId() { return documentId; }
    public String query() { return query; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public JobStatus status() { return status; }
    public String finalResponse() { return finalResponse; }
    public String error() { return error; }
    public AgentEnvelope proposer() { return proposer.get(); }
    public AgentEnvelope critic() { return critic.get(); }
    public AgentEnvelope synthesiser() { return synthesiser.get(); }

    public void transition(JobStatus next) { this.status = next; }
    public void setProposer(AgentEnvelope env) { proposer.set(env); }
    public void setCritic(AgentEnvelope env) { critic.set(env); }
    public void setSynthesiser(AgentEnvelope env) { synthesiser.set(env); }

    public void complete(String response, Instant completedAt) {
        this.finalResponse = response;
        this.completedAt = completedAt;
        this.status = JobStatus.COMPLETED;
    }

    public void fail(String message, Instant completedAt) {
        this.error = message;
        this.completedAt = completedAt;
        this.status = JobStatus.FAILED;
    }

    public void cancel(Instant completedAt) {
        this.completedAt = completedAt;
        this.status = JobStatus.CANCELLED;
    }

    public boolean isTerminal() {
        return status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }
}
