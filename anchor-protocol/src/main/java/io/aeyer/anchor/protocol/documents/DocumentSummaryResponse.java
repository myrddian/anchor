package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record DocumentSummaryResponse(
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("title") String title,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("doc_summary") String docSummary,
        @JsonProperty("ingested_at") Instant ingestedAt,
        @JsonProperty("chapter_count") int chapterCount,
        @JsonProperty("section_count") int sectionCount,
        @JsonProperty("chunk_count") int chunkCount) {}
