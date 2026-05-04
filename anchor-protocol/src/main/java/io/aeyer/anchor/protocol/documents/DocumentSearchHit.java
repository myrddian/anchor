package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record DocumentSearchHit(
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("title") String title,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("doc_summary") String docSummary,
        @JsonProperty("ingested_at") Instant ingestedAt,
        @JsonProperty("score") double score) {}
