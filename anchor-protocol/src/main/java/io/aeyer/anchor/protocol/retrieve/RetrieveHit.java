package io.aeyer.anchor.protocol.retrieve;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * One retrieved chunk wrapped with its full ancestor summary stack
 * (SPEC §5.3 Shape 1) — the caller never has to make a follow-up read to
 * understand context.
 */
public record RetrieveHit(
        @JsonProperty("chunk_id") UUID chunkId,
        @JsonProperty("text") String text,
        @JsonProperty("score") double score,
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
