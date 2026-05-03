package io.aeyer.anchor.server.domain;

import java.util.UUID;

public record Paragraph(
        UUID id,
        UUID sectionId,
        int ordinal,
        String rawText,
        String summary) {}
