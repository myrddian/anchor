package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Counts of immediate children, used by the /documents list response so the
 * caller doesn't have to fetch the whole hierarchy to know its rough shape.
 */

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

    List<Document> findPageAsDomain(int limit, int offset, String titleSubstring);

    long countMatching(String titleSubstring);

    DocumentCounts countsFor(UUID documentId);

    List<ChunkSearchHit> findSimilarChunksInDocument(UUID documentId, float[] queryEmbedding, int limit);

    /**
     * /retrieve search. {@code documentId} optional — null means corpus-wide.
     * Returns each hit with the full ancestor stack joined so the response
     * envelope is one row per chunk with no follow-up reads.
     */
    List<RetrieveSearchRow> findChunksForRetrieve(UUID documentId, float[] queryEmbedding, int limit);

    record DocumentCounts(int chapters, int sections, int chunks) {}

    record ChunkSearchHit(
            UUID chunkId,
            UUID paragraphId,
            String chunkText,
            String paragraphSummary,
            String sectionTitle,
            double similarity) {}

    record RetrieveSearchRow(
            UUID chunkId,
            String chunkText,
            double similarity,
            UUID paragraphId,
            String paragraphSummary,
            UUID sectionId,
            String sectionTitle,
            String sectionSummary,
            UUID chapterId,
            String chapterTitle,
            String chapterSummary,
            UUID documentId,
            String documentTitle,
            String documentSummary) {}
}
