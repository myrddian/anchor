package io.aeyer.anchor.protocol.documents;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(Include.NON_NULL)
public record DocumentSearchResponse(
        @JsonProperty("query") String query,
        @JsonProperty("k") int k,
        @JsonProperty("hits") List<DocumentSearchHit> hits) {}
