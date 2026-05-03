package io.aeyer.anchor.server.domain;

import java.util.UUID;

public record Section(
        UUID id,
        UUID chapterId,
        int ordinal,
        String title,
        String summary) {}
