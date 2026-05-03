package io.aeyer.anchor.server.domain;

import java.util.UUID;

public record Chunk(
        UUID id,
        UUID paragraphId,
        int ordinal,
        String text,
        float[] embedding) {}
