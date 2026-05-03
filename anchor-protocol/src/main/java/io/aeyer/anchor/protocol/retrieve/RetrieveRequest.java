package io.aeyer.anchor.protocol.retrieve;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * POST /retrieve body. {@code documentId} optional — present means restrict
 * to that document (recommended), absent means corpus-wide search (provided
 * for completeness, not what the system is optimised for; SPEC §5.3).
 */
@JsonInclude(Include.NON_NULL)
public record RetrieveRequest(
        @JsonProperty("query") String query,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("k") Integer k) {}
