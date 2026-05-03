package io.aeyer.anchor.protocol.sse;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * SSE event from /jobs/{id}/stream. Wire shape: each event has a sequence
 * number (so reconnect-replay can resume mid-stream), a type, and a payload
 * specific to that type. Token-bearing events (proposer_thought etc.) carry
 * {@code token}; lifecycle events carry the new status; the {@code completed}
 * event carries the final response.
 */
@JsonInclude(Include.NON_NULL)
public record JobEvent(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("sequence") long sequence,
        @JsonProperty("type") JobEventType type,
        @JsonProperty("status") String status,
        @JsonProperty("token") String token,
        @JsonProperty("response") String response,
        @JsonProperty("error") String error,
        @JsonProperty("timestamp") Instant timestamp) {}
