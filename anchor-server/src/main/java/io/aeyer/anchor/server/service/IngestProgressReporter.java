package io.aeyer.anchor.server.service;

import io.aeyer.anchor.protocol.ingest.IngestPhase;
import java.util.UUID;

/**
 * Callback the {@link IngestService} fires as it moves through pipeline
 * phases. Implementations live outside the service so progress can either
 * be persisted to {@code IngestJobStore} (async path) or no-op'd (sync
 * path keeps existing behaviour).
 *
 * - {@code phase} — current pipeline step.
 * - {@code percent} — best-effort 0..100. Phase weights are chosen by the
 *   service; see {@link IngestService} for the budget.
 * - {@code message} — optional human-readable line ("Embedding 142/300
 *   chunks"); may be null.
 *
 * {@link #attachDocument} is fired exactly once per ingest, as soon as the
 * content hash → stable document id is known and the title has been lifted
 * from the extractor. Lets the UI show "Ingesting: <title>" before the
 * heavy LLM work starts.
 */
public interface IngestProgressReporter {

    IngestProgressReporter NOOP = new IngestProgressReporter() {
        @Override public void report(IngestPhase phase, int percent, String message) {}
        @Override public void attachDocument(UUID documentId, String title) {}
    };

    void report(IngestPhase phase, int percent, String message);

    void attachDocument(UUID documentId, String title);
}
