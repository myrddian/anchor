package io.aeyer.anchor.protocol.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record IngestResponse(
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("title") String title,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("chapter_count") int chapterCount,
        @JsonProperty("section_count") int sectionCount,
        @JsonProperty("paragraph_count") int paragraphCount,
        @JsonProperty("chunk_count") int chunkCount,
        @JsonProperty("ingested_at") Instant ingestedAt,
        @JsonProperty("token_usage") TokenUsageSummary tokenUsage) {

    public record TokenUsageSummary(
            @JsonProperty("summary_input_tokens") long summaryInputTokens,
            @JsonProperty("summary_output_tokens") long summaryOutputTokens,
            @JsonProperty("embedding_inputs") long embeddingInputs) {}
}
