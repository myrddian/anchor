package io.aeyer.anchor.protocol.validate;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Vector-only stance approximation for fast pre-filtering at scale.
 * NOT a substitute for full /validate — this is a heuristic the caller uses
 * to decide whether the document is worth invoking the deliberation on.
 *
 * - {@code topical_relevance}: cosine of the query against the document's
 *   summary embedding. Range [-1, 1]; high values mean the document is
 *   topically about the query, regardless of stance.
 * - {@code stance_score}: topical_relevance MINUS the same cosine for the
 *   negated query ("not " + query). Positive ≈ document leans toward the
 *   query's claim, negative ≈ leans against, near zero ≈ topical-but-mixed.
 *   Heuristic only.
 */
@JsonInclude(Include.NON_NULL)
public record ValidateQuickResponse(
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("query") String query,
        @JsonProperty("topical_relevance") double topicalRelevance,
        @JsonProperty("stance_score") double stanceScore,
        @JsonProperty("mode") String mode) {}
