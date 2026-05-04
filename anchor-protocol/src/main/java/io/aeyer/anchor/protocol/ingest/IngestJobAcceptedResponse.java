package io.aeyer.anchor.protocol.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * 202 response from the async ingest endpoints — gives the client a job id
 * to poll {@code GET /ingest/jobs/{id}} for progress. {@code documentId} is
 * null at this point because we don't know the content hash until the file
 * has been read; it's filled in on the progress envelope once available.
 */
@JsonInclude(Include.NON_NULL)
public record IngestJobAcceptedResponse(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("source_path") String sourcePath,
        @JsonProperty("status") IngestJobStatus status,
        @JsonProperty("progress_url") String progressUrl) {}
