package io.aeyer.anchor.server.persistence.entity;

import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import io.aeyer.anchor.protocol.ingest.IngestPhase;
import io.aeyer.anchor.server.service.IngestService;
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
 * JPA mirror of {@link io.aeyer.anchor.server.jobs.IngestJob}. Same write-
 * through pattern as {@link AskJobDbo}: the in-memory job is authoritative
 * during ingest (orchestrator thread mutates volatile fields), this row is
 * rewritten at phase / status transitions so a server restart can surface
 * the last known progress to a polling client.
 *
 * The completed result (chapter / chunk counts, token usage, etc.) is
 * stored as JSONB once status flips to COMPLETED — same shape as
 * {@code IngestService.IngestResult}, no separate mapping.
 */
@Entity
@Table(name = "ingest_jobs")
public class IngestJobDbo {

    @Id
    @Column(name = "job_id")
    private UUID jobId;

    @Column(name = "source_path", nullable = false, columnDefinition = "TEXT")
    private String sourcePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngestJobStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private IngestPhase phase;

    @Column(name = "percent_complete", nullable = false)
    private int percentComplete;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(columnDefinition = "TEXT")
    private String error;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private IngestService.IngestResult result;

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public IngestJobStatus getStatus() { return status; }
    public void setStatus(IngestJobStatus status) { this.status = status; }

    public IngestPhase getPhase() { return phase; }
    public void setPhase(IngestPhase phase) { this.phase = phase; }

    public int getPercentComplete() { return percentComplete; }
    public void setPercentComplete(int percentComplete) { this.percentComplete = percentComplete; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public IngestService.IngestResult getResult() { return result; }
    public void setResult(IngestService.IngestResult result) { this.result = result; }
}
