package io.aeyer.anchor.protocol.validate;

/** SPEC §6.5: the document's overall position on the caller's query. */
public enum DocumentStance {
    SUPPORTS,
    REJECTS,
    NEUTRAL,
    MIXED,
    OFF_TOPIC
}
