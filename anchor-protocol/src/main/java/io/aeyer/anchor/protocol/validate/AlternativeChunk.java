package io.aeyer.anchor.protocol.validate;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * A chunk surfaced by alternative-chunk discovery (SPEC §7.4) when the
 * validator flags the original chunk as steelman-refuted-later or as living
 * inside a document that rejects the query — points the caller at the
 * passage(s) doing the refuting.
 */
public record AlternativeChunk(
        @JsonProperty("chunk_id") UUID chunkId,
        @JsonProperty("text") String text,
        @JsonProperty("paragraph_summary") String paragraphSummary,
        @JsonProperty("section_title") String sectionTitle,
        @JsonProperty("section_synthetic") boolean sectionSynthetic,
        @JsonProperty("similarity") double similarity) {}
