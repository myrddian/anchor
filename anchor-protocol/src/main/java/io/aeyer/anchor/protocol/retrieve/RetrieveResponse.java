package io.aeyer.anchor.protocol.retrieve;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

@JsonInclude(Include.NON_NULL)
public record RetrieveResponse(
        @JsonProperty("query") String query,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("k") int k,
        @JsonProperty("hits") List<RetrieveHit> hits) {}
