package io.aeyer.anchor.server.persistence.entity;

import io.aeyer.anchor.protocol.ask.AgentEnvelope;
import io.aeyer.anchor.protocol.ask.JobStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mirror of {@link io.aeyer.anchor.server.jobs.AskJob}. The in-memory job
 * remains authoritative during the deliberation (worker threads mutate volatile
 * fields without going through JPA); this row gets rewritten at every state
 * transition so a server restart can still surface the last known status to
 * polling clients via {@link io.aeyer.anchor.server.jobs.JobStore}'s reload
 * path.
 *
 * Agent envelopes are stored as JSONB blobs — the wire shape ({@link
 * AgentEnvelope}) is identical to the storage shape, no extra mapping table.
 */
@Entity
@Table(name = "ask_jobs")
public class AskJobDbo {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(nullable = false)
    private String query;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "final_response", columnDefinition = "TEXT")
    private String finalResponse;

    @Column(columnDefinition = "TEXT")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private AgentEnvelope proposer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private AgentEnvelope critic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private AgentEnvelope synthesiser;

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getFinalResponse() { return finalResponse; }
    public void setFinalResponse(String finalResponse) { this.finalResponse = finalResponse; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public AgentEnvelope getProposer() { return proposer; }
    public void setProposer(AgentEnvelope proposer) { this.proposer = proposer; }

    public AgentEnvelope getCritic() { return critic; }
    public void setCritic(AgentEnvelope critic) { this.critic = critic; }

    public AgentEnvelope getSynthesiser() { return synthesiser; }
    public void setSynthesiser(AgentEnvelope synthesiser) { this.synthesiser = synthesiser; }
}
