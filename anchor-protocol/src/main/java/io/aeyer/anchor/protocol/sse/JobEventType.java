package io.aeyer.anchor.protocol.sse;

/** SPEC §5.4 — SSE event type vocabulary. */
public enum JobEventType {
    STATUS,
    PROPOSER_THOUGHT,
    PROPOSER_COMPLETE,
    CRITIC_THOUGHT,
    CRITIC_COMPLETE,
    SYNTHESISER_THOUGHT,
    SYNTHESISER_COMPLETE,
    COMPLETED,
    FAILED
}
