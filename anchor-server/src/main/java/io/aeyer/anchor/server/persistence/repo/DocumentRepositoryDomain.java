package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain-returning surface — every method here completes inside one transaction
 * and hands back fully-eager records, never DBOs or proxies. SPEC §7.1: nothing
 * lazy crosses the persistence boundary.
 */
public interface DocumentRepositoryDomain {

    Optional<Document> findAsDomain(UUID documentId);

    List<Document> findAllAsDomain();

    Optional<ChunkWithAncestors> findChunkWithAncestorsAsDomain(UUID chunkId);

    Optional<DocumentContext> findDocumentContextAsDomain(UUID documentId);
}
