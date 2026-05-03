package io.aeyer.anchor.protocol.ask;

/**
 * What slice of the document each agent saw. SPEC §6.6: the asymmetry is the
 * trust mechanism — exposing it on the wire so callers can audit it.
 */
public enum EvidenceAccess {
    FULL_HIERARCHY,
    MACRO_ONLY,
    FULL_HIERARCHY_PLUS_DEBATE
}
