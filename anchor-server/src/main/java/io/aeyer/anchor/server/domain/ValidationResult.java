package io.aeyer.anchor.server.domain;

import io.aeyer.anchor.protocol.validate.ArgumentativeRole;
import io.aeyer.anchor.protocol.validate.DocumentStance;
import java.util.List;
import java.util.UUID;

/**
 * Service-side validation outcome before the controller maps it to a wire
 * envelope. Domain reuses the protocol enums (which are pure POJOs and have
 * no Jackson coupling at the value level) — saves a parallel pair just for
 * the layering's sake.
 */
public record ValidationResult(
        UUID chunkId,
        UUID documentId,
        String query,
        boolean isLoadBearing,
        ArgumentativeRole argumentativeRole,
        DocumentStance documentStanceOnQuery,
        String qualifyingContext,
        String reasoning,
        List<AlternativeChunk> alternativeChunks) {

    public record AlternativeChunk(
            UUID chunkId,
            String text,
            String paragraphSummary,
            String sectionTitle,
            double similarity) {}
}
