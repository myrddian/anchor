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
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    public DocumentRepositoryImpl(EntityToDomainMapper mapper) {
        this.mapper = mapper;
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
}
