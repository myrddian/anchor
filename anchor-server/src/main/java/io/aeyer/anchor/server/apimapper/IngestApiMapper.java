package io.aeyer.anchor.server.apimapper;

import io.aeyer.anchor.protocol.ingest.IngestResponse;
import io.aeyer.anchor.server.service.IngestService;
import io.aeyer.anchor.server.service.TokenLedger;
import org.springframework.stereotype.Component;

@Component
public class IngestApiMapper {

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
