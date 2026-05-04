package io.aeyer.anchor.server.apimapper;

import io.aeyer.anchor.protocol.ingest.IngestJobAcceptedResponse;
import io.aeyer.anchor.protocol.ingest.IngestJobResponse;
import io.aeyer.anchor.protocol.ingest.IngestResponse;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.service.IngestService;
import io.aeyer.anchor.server.service.TokenLedger;
import org.springframework.stereotype.Component;

@Component
public class IngestApiMapper {

    public IngestJobAcceptedResponse toAccepted(IngestJob job) {
        return new IngestJobAcceptedResponse(
                job.jobId(),
                job.sourcePath(),
                job.status(),
                "/ingest/jobs/" + job.jobId());
    }

    public IngestJobResponse toJobResponse(IngestJob job) {
        return new IngestJobResponse(
                job.jobId(),
                job.sourcePath(),
                job.status(),
                job.phase(),
                job.percentComplete(),
                job.message(),
                job.documentId(),
                job.title(),
                job.startedAt(),
                job.updatedAt(),
                job.completedAt(),
                job.error(),
                job.result() == null ? null : toResponse(job.result()));
    }

    public IngestResponse toResponse(IngestService.IngestResult result) {
        TokenLedger.Snapshot tokens = result.tokens();
        return new IngestResponse(
                result.documentId(),
                result.title(),
                result.sourcePath(),
                result.chapterCount(),
                result.sectionCount(),
                result.paragraphCount(),
                result.chunkCount(),
                result.ingestedAt(),
                new IngestResponse.TokenUsageSummary(
                        tokens.summaryInput(),
                        tokens.summaryOutput(),
                        tokens.embeddingInputs()));
    }
}
