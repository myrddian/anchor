package io.aeyer.anchor.protocol.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** Full deliberation envelope per SPEC §5.4. Returned from GET /jobs/{id}. */
@JsonInclude(Include.NON_NULL)
public record AskJobResponse(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("query") String query,
        @JsonProperty("status") JobStatus status,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("proposer") AgentEnvelope proposer,
        @JsonProperty("critic") AgentEnvelope critic,
        @JsonProperty("synthesiser") AgentEnvelope synthesiser,
        @JsonProperty("final_response") String finalResponse,
        @JsonProperty("error") String error) {}
