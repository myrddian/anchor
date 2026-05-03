package io.aeyer.anchor.server.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record Document(
        UUID id,
        String title,
        String sourcePath,
        String docSummary,
        DocSummarySource docSummarySource,
        Instant ingestedAt,
        Map<String, Object> metadata) {}
