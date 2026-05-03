package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record DocumentListResponse(
        @JsonProperty("documents") List<DocumentSummaryResponse> documents,
        @JsonProperty("total") long total,
        @JsonProperty("limit") int limit,
        @JsonProperty("offset") int offset) {}
