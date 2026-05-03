package io.aeyer.anchor.server.persistence.entity;

import io.aeyer.anchor.server.domain.DocSummarySource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "documents")
public class DocumentDbo {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    @Column(name = "source_path", nullable = false)
    private String sourcePath;

    @Column(name = "doc_summary", nullable = false)
    private String docSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_summary_source", nullable = false, length = 20)
    private DocSummarySource docSummarySource;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getDocSummary() { return docSummary; }
    public void setDocSummary(String docSummary) { this.docSummary = docSummary; }

    public DocSummarySource getDocSummarySource() { return docSummarySource; }
    public void setDocSummarySource(DocSummarySource s) { this.docSummarySource = s; }

    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }
}
