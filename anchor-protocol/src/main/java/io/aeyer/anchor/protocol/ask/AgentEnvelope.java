package io.aeyer.anchor.protocol.ask;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Per-agent slice of the deliberation envelope. {@code response} holds the
 * agent's text output (proposer + synthesiser final response, critic JSON
 * pretty-printed). {@code grounding} is whatever structured output the agent
 * produced (critic challenges, synthesiser grounding) — null if the agent
 * doesn't return structured output or hasn't run yet.
 */
@JsonInclude(Include.NON_NULL)
public record AgentEnvelope(
        @JsonProperty("agent") String agent,
        @JsonProperty("evidence_access") EvidenceAccess evidenceAccess,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("response") String response,
        @JsonProperty("grounding") Map<String, Object> grounding,
        @JsonProperty("challenges") List<String> challenges,
        @JsonProperty("error") String error) {}
