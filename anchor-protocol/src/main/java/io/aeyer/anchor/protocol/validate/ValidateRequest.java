package io.aeyer.anchor.protocol.validate;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record ValidateRequest(
        @JsonProperty("chunk_id") UUID chunkId,
        @JsonProperty("query") String query) {}
