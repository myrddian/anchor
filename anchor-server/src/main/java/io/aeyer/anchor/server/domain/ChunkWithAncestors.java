package io.aeyer.anchor.server.domain;

/**
 * A chunk eagerly bundled with every ancestor in its hierarchy. SPEC §7.3
 * requires this to cross thread boundaries (validate / retrieve workers run on
 * the chat pool, not the request thread) — no lazy proxies, no Hibernate
 * sessions, just immutable data.
 */
public record ChunkWithAncestors(
        Chunk chunk,
        Paragraph paragraph,
        Section section,
        Chapter chapter,
        Document document) {}
