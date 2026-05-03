package io.aeyer.anchor.server.domain;

import java.util.UUID;

public record Chapter(
        UUID id,
        UUID documentId,
        int ordinal,
        String title,
        String summary,
        boolean isSynthetic) {}
