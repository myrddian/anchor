package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * GET /chunks/{id} response: chunk text plus the full ancestor chain
 * (paragraph, section, chapter, document IDs and summaries) so a caller has
 * the same evidence the validator would see.
 */
public record ChunkDetailResponse(
        @JsonProperty("chunk_id") UUID chunkId,
        @JsonProperty("text") String text,
        @JsonProperty("paragraph_id") UUID paragraphId,
        @JsonProperty("paragraph_summary") String paragraphSummary,
        @JsonProperty("section_id") UUID sectionId,
        @JsonProperty("section_title") String sectionTitle,
        @JsonProperty("section_synthetic") boolean sectionSynthetic,
        @JsonProperty("section_summary") String sectionSummary,
        @JsonProperty("chapter_id") UUID chapterId,
        @JsonProperty("chapter_title") String chapterTitle,
        @JsonProperty("chapter_synthetic") boolean chapterSynthetic,
        @JsonProperty("chapter_summary") String chapterSummary,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("document_title") String documentTitle,
        @JsonProperty("document_summary") String documentSummary) {}
