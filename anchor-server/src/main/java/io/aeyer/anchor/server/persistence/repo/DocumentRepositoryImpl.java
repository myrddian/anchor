package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.domain.Chapter;
import io.aeyer.anchor.server.domain.Chunk;
import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import io.aeyer.anchor.server.domain.Paragraph;
import io.aeyer.anchor.server.domain.Section;
import io.aeyer.anchor.server.persistence.entity.ChapterDbo;
import io.aeyer.anchor.server.persistence.entity.ChunkDbo;
import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import io.aeyer.anchor.server.persistence.entity.ParagraphDbo;
import io.aeyer.anchor.server.persistence.entity.SectionDbo;
import io.aeyer.anchor.server.persistence.mapper.EntityToDomainMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Custom impl backing {@link DocumentRepositoryDomain}. Spring Data JPA picks
 * up beans named {@code XxxImpl} as the implementation for custom interfaces on
 * a repository — no extra wiring needed.
 *
 * Every method is {@code @Transactional(readOnly = true)} so the multi-table
 * walks (chunk → ancestors, document → full context) materialise inside one
 * Hibernate session, then mapper.toDomain copies values into immutable
 * records before the session closes — guaranteeing no proxies leak.
 */
public class DocumentRepositoryImpl implements DocumentRepositoryDomain {

    @PersistenceContext
    private EntityManager em;

    private final EntityToDomainMapper mapper;
    private final JdbcTemplate jdbc;

    @Autowired
    public DocumentRepositoryImpl(EntityToDomainMapper mapper, DataSource dataSource) {
        this.mapper = mapper;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> findAsDomain(UUID documentId) {
        return Optional.ofNullable(em.find(DocumentDbo.class, documentId)).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> findAllAsDomain() {
        return em.createQuery("SELECT d FROM DocumentDbo d ORDER BY d.ingestedAt DESC", DocumentDbo.class)
                .getResultList()
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ChunkWithAncestors> findChunkWithAncestorsAsDomain(UUID chunkId) {
        ChunkDbo chunkDbo = em.find(ChunkDbo.class, chunkId);
        if (chunkDbo == null) return Optional.empty();
        ParagraphDbo paragraphDbo = em.find(ParagraphDbo.class, chunkDbo.getParagraphId());
        SectionDbo sectionDbo = em.find(SectionDbo.class, paragraphDbo.getSectionId());
        ChapterDbo chapterDbo = em.find(ChapterDbo.class, sectionDbo.getChapterId());
        DocumentDbo documentDbo = em.find(DocumentDbo.class, chapterDbo.getDocumentId());
        return Optional.of(new ChunkWithAncestors(
                mapper.toDomain(chunkDbo),
                mapper.toDomain(paragraphDbo),
                mapper.toDomain(sectionDbo),
                mapper.toDomain(chapterDbo),
                mapper.toDomain(documentDbo)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DocumentContext> findDocumentContextAsDomain(UUID documentId) {
        DocumentDbo documentDbo = em.find(DocumentDbo.class, documentId);
        if (documentDbo == null) return Optional.empty();
        Document document = mapper.toDomain(documentDbo);

        List<ChapterDbo> chapterDbos = em.createQuery(
                "SELECT c FROM ChapterDbo c WHERE c.documentId = :did ORDER BY c.ordinal", ChapterDbo.class)
                .setParameter("did", documentId)
                .getResultList();
        List<DocumentContext.ChapterContext> chapters = new ArrayList<>(chapterDbos.size());
        for (ChapterDbo chapterDbo : chapterDbos) {
            Chapter chapter = mapper.toDomain(chapterDbo);
            List<SectionDbo> sectionDbos = em.createQuery(
                    "SELECT s FROM SectionDbo s WHERE s.chapterId = :cid ORDER BY s.ordinal", SectionDbo.class)
                    .setParameter("cid", chapter.id())
                    .getResultList();
            List<DocumentContext.SectionContext> sections = new ArrayList<>(sectionDbos.size());
            for (SectionDbo sectionDbo : sectionDbos) {
                Section section = mapper.toDomain(sectionDbo);
                List<ParagraphDbo> paragraphDbos = em.createQuery(
                        "SELECT p FROM ParagraphDbo p WHERE p.sectionId = :sid ORDER BY p.ordinal", ParagraphDbo.class)
                        .setParameter("sid", section.id())
                        .getResultList();
                List<DocumentContext.ParagraphContext> paragraphs = new ArrayList<>(paragraphDbos.size());
                for (ParagraphDbo paragraphDbo : paragraphDbos) {
                    Paragraph paragraph = mapper.toDomain(paragraphDbo);
                    List<ChunkDbo> chunkDbos = em.createQuery(
                            "SELECT k FROM ChunkDbo k WHERE k.paragraphId = :pid ORDER BY k.ordinal", ChunkDbo.class)
                            .setParameter("pid", paragraph.id())
                            .getResultList();
                    List<Chunk> chunks = chunkDbos.stream().map(mapper::toDomain).toList();
                    paragraphs.add(new DocumentContext.ParagraphContext(paragraph, chunks));
                }
                sections.add(new DocumentContext.SectionContext(section, paragraphs));
            }
            chapters.add(new DocumentContext.ChapterContext(chapter, sections));
        }
        return Optional.of(new DocumentContext(document, chapters));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> findPageAsDomain(int limit, int offset, String titleSubstring) {
        String jpql;
        var params = new java.util.HashMap<String, Object>();
        if (titleSubstring == null || titleSubstring.isBlank()) {
            jpql = "SELECT d FROM DocumentDbo d ORDER BY d.ingestedAt DESC";
        } else {
            jpql = "SELECT d FROM DocumentDbo d WHERE LOWER(d.title) LIKE :q ORDER BY d.ingestedAt DESC";
            params.put("q", "%" + titleSubstring.toLowerCase() + "%");
        }
        var query = em.createQuery(jpql, DocumentDbo.class)
                .setFirstResult(Math.max(0, offset))
                .setMaxResults(Math.max(1, Math.min(500, limit)));
        params.forEach(query::setParameter);
        return query.getResultList().stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countMatching(String titleSubstring) {
        if (titleSubstring == null || titleSubstring.isBlank()) {
            return em.createQuery("SELECT COUNT(d) FROM DocumentDbo d", Long.class).getSingleResult();
        }
        return em.createQuery("SELECT COUNT(d) FROM DocumentDbo d WHERE LOWER(d.title) LIKE :q", Long.class)
                .setParameter("q", "%" + titleSubstring.toLowerCase() + "%")
                .getSingleResult();
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentCounts countsFor(UUID documentId) {
        Long chapters = em.createQuery(
                "SELECT COUNT(c) FROM ChapterDbo c WHERE c.documentId = :did", Long.class)
                .setParameter("did", documentId).getSingleResult();
        Long sections = em.createQuery(
                "SELECT COUNT(s) FROM SectionDbo s WHERE s.chapterId IN " +
                        "(SELECT c.id FROM ChapterDbo c WHERE c.documentId = :did)", Long.class)
                .setParameter("did", documentId).getSingleResult();
        Long chunks = em.createQuery(
                "SELECT COUNT(k) FROM ChunkDbo k WHERE k.paragraphId IN " +
                        "(SELECT p.id FROM ParagraphDbo p WHERE p.sectionId IN " +
                        "(SELECT s.id FROM SectionDbo s WHERE s.chapterId IN " +
                        "(SELECT c.id FROM ChapterDbo c WHERE c.documentId = :did)))", Long.class)
                .setParameter("did", documentId).getSingleResult();
        return new DocumentCounts(chapters.intValue(), sections.intValue(), chunks.intValue());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChunkSearchHit> findSimilarChunksInDocument(UUID documentId, float[] queryEmbedding, int limit) {
        // pgvector cosine search restricted to one document. JdbcTemplate path because
        // JPA native parameter binding for `vector` is awkward — pgvector text format
        // sidesteps the issue.
        String vectorLiteral = toPgVectorText(queryEmbedding);
        String sql = """
                SELECT k.id AS chunk_id,
                       k.paragraph_id,
                       k.text AS chunk_text,
                       p.summary AS paragraph_summary,
                       s.title AS section_title,
                       s.is_synthetic AS section_synthetic,
                       1 - (k.embedding <=> ?::vector) AS similarity
                FROM chunks k
                JOIN paragraphs p ON p.id = k.paragraph_id
                JOIN sections s ON s.id = p.section_id
                JOIN chapters c ON c.id = s.chapter_id
                WHERE c.document_id = ?
                ORDER BY k.embedding <=> ?::vector
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new ChunkSearchHit(
                        (UUID) rs.getObject("chunk_id"),
                        (UUID) rs.getObject("paragraph_id"),
                        rs.getString("chunk_text"),
                        rs.getString("paragraph_summary"),
                        rs.getString("section_title"),
                        rs.getBoolean("section_synthetic"),
                        rs.getDouble("similarity")),
                vectorLiteral, documentId, vectorLiteral, Math.max(1, Math.min(50, limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetrieveSearchRow> findChunksForRetrieve(UUID documentId, float[] queryEmbedding, int limit) {
        String vectorLiteral = toPgVectorText(queryEmbedding);
        StringBuilder sql = new StringBuilder("""
                SELECT k.id AS chunk_id,
                       k.text AS chunk_text,
                       1 - (k.embedding <=> ?::vector) AS similarity,
                       p.id AS paragraph_id, p.summary AS paragraph_summary,
                       s.id AS section_id, s.title AS section_title,
                       s.is_synthetic AS section_synthetic, s.summary AS section_summary,
                       c.id AS chapter_id, c.title AS chapter_title,
                       c.is_synthetic AS chapter_synthetic, c.summary AS chapter_summary,
                       d.id AS document_id, d.title AS document_title, d.doc_summary AS document_summary
                FROM chunks k
                JOIN paragraphs p ON p.id = k.paragraph_id
                JOIN sections s ON s.id = p.section_id
                JOIN chapters c ON c.id = s.chapter_id
                JOIN documents d ON d.id = c.document_id
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(vectorLiteral);
        if (documentId != null) {
            sql.append("WHERE d.id = ?\n");
            args.add(documentId);
        }
        sql.append("ORDER BY k.embedding <=> ?::vector\nLIMIT ?");
        args.add(vectorLiteral);
        args.add(Math.max(1, Math.min(100, limit)));
        return jdbc.query(sql.toString(), (rs, rowNum) -> new RetrieveSearchRow(
                        (UUID) rs.getObject("chunk_id"),
                        rs.getString("chunk_text"),
                        rs.getDouble("similarity"),
                        (UUID) rs.getObject("paragraph_id"),
                        rs.getString("paragraph_summary"),
                        (UUID) rs.getObject("section_id"),
                        rs.getString("section_title"),
                        rs.getBoolean("section_synthetic"),
                        rs.getString("section_summary"),
                        (UUID) rs.getObject("chapter_id"),
                        rs.getString("chapter_title"),
                        rs.getBoolean("chapter_synthetic"),
                        rs.getString("chapter_summary"),
                        (UUID) rs.getObject("document_id"),
                        rs.getString("document_title"),
                        rs.getString("document_summary")),
                args.toArray());
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentSearchRow> searchDocumentsBySummary(float[] queryEmbedding, int limit) {
        String vectorLiteral = toPgVectorText(queryEmbedding);
        String sql = """
                SELECT id, title, source_path, doc_summary, ingested_at,
                       1 - (summary_embedding <=> ?::vector) AS similarity
                FROM documents
                WHERE summary_embedding IS NOT NULL
                ORDER BY summary_embedding <=> ?::vector
                LIMIT ?
                """;
        return jdbc.query(sql, (rs, rowNum) -> new DocumentSearchRow(
                        (UUID) rs.getObject("id"),
                        rs.getString("title"),
                        rs.getString("source_path"),
                        rs.getString("doc_summary"),
                        rs.getTimestamp("ingested_at").toInstant(),
                        rs.getDouble("similarity")),
                vectorLiteral, vectorLiteral, Math.max(1, Math.min(200, limit)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Double> documentSummaryCosine(UUID documentId, float[] queryEmbedding) {
        String vectorLiteral = toPgVectorText(queryEmbedding);
        String sql = """
                SELECT 1 - (summary_embedding <=> ?::vector) AS similarity
                FROM documents
                WHERE id = ? AND summary_embedding IS NOT NULL
                """;
        List<Double> result = jdbc.query(sql,
                (rs, rowNum) -> rs.getDouble("similarity"),
                vectorLiteral, documentId);
        return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
    }

    private static String toPgVectorText(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 6 + 2);
        sb.append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
