package io.aeyer.anchor.server.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Type;

@Entity
@Table(name = "chunks")
public class ChunkDbo {

    @Id
    private UUID id;

    @Column(name = "paragraph_id", nullable = false)
    private UUID paragraphId;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false)
    private String text;

    @Type(PgVectorType.class)
    @Column(nullable = false, columnDefinition = "vector(768)")
    private float[] embedding;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getParagraphId() { return paragraphId; }
    public void setParagraphId(UUID paragraphId) { this.paragraphId = paragraphId; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] embedding) { this.embedding = embedding; }
}
