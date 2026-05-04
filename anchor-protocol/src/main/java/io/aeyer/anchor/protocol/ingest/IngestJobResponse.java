package io.aeyer.anchor.protocol.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Full progress envelope for an async ingest job — returned by
 * {@code GET /ingest/jobs/{id}}.
 *
 * - {@code status}: coarse lifecycle (QUEUED / RUNNING / COMPLETED / …).
 * - {@code phase}: fine-grained pipeline step (EXTRACTING / EMBEDDING / …).
 * - {@code percentComplete}: monotonic 0..100 progress estimate. Phases get
 *   pre-allocated weights — within a phase the count of items processed
 *   linearly fills the slice.
 * - {@code message}: optional one-liner the UI can show under the bar
 *   (e.g. "Embedding 142/300 chunks"). Free-form, may be null.
 * - {@code documentId} / {@code title}: filled in once the extractor has
 *   computed the content hash and lifted the title.
 * - {@code result}: the same {@link IngestResponse} body the synchronous
 *   {@code POST /ingest} returns, populated only when {@code status =
 *   COMPLETED}. Saves the client a follow-up call.
 */
@JsonInclude(Include.NON_NULL)
public record IngestJobResponse(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("status") IngestJobStatus status,
        @JsonProperty("phase") IngestPhase phase,
        @JsonProperty("percent_complete") int percentComplete,
        @JsonProperty("message") String message,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("title") String title,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("error") String error,
        @JsonProperty("result") IngestResponse result) {}
