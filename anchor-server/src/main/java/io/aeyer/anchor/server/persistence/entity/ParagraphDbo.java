package io.aeyer.anchor.server.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "paragraphs")
public class ParagraphDbo {

    @Id
    private UUID id;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "raw_text", nullable = false)
    private String rawText;

    @Column(nullable = false)
    private String summary;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getSectionId() { return sectionId; }
    public void setSectionId(UUID sectionId) { this.sectionId = sectionId; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public String getRawText() { return rawText; }
    public void setRawText(String rawText) { this.rawText = rawText; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}
