package io.aeyer.anchor.protocol.ingest;

/**
 * Fine-grained "what is the ingest worker doing right now" signal. Surfaces in
 * {@code IngestJobResponse.phase} alongside an integer percent so a UI can
 * show both a phase label and a progress bar without inferring one from the
 * other. Order matches the SPEC §4 pipeline.
 */
public enum IngestPhase {
    QUEUED,
    EXTRACTING,
    PARSING,
    SUMMARISING_PARAGRAPHS,
    SUMMARISING_SECTIONS,
    SUMMARISING_CHAPTERS,
    SUMMARISING_DOCUMENT,
    EMBEDDING,
    PERSISTING,
    DONE
}
