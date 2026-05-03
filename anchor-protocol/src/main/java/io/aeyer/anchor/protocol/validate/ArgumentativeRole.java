package io.aeyer.anchor.protocol.validate;

/** SPEC §6.5: how a chunk participates in the document's argument. */
public enum ArgumentativeRole {
    AUTHOR_POSITION,
    STEELMAN_REFUTED_LATER,
    CITED_EXTERNAL_VIEW,
    QUALIFIED_CLAIM,
    BACKGROUND_FACTUAL,
    UNCLEAR
}
