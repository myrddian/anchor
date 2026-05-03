package io.aeyer.anchor.protocol.ask;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

public record JobAcceptedResponse(
        @JsonProperty("job_id") UUID jobId,
        @JsonProperty("document_id") UUID documentId,
        @JsonProperty("status") JobStatus status,
        @JsonProperty("stream_url") String streamUrl,
        @JsonProperty("result_url") String resultUrl,
        @JsonProperty("estimated_duration_seconds") int estimatedDurationSeconds) {}
