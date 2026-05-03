package io.aeyer.anchor.protocol.validate;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

public record ValidateResponse(
        @JsonProperty("chunk_id") UUID chunkId,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("query") String query,
        @JsonProperty("is_load_bearing") boolean isLoadBearing,
        @JsonProperty("argumentative_role") ArgumentativeRole argumentativeRole,
        @JsonProperty("document_stance_on_query") DocumentStance documentStanceOnQuery,
        @JsonProperty("qualifying_context") String qualifyingContext,
        @JsonProperty("reasoning") String reasoning,
        @JsonProperty("alternative_chunks") List<AlternativeChunk> alternativeChunks) {}
