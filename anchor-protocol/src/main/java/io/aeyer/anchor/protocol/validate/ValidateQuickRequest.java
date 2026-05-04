package io.aeyer.anchor.protocol.validate;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record ValidateQuickRequest(
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("query") String query) {}
