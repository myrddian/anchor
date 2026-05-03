package io.aeyer.anchor.server.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.server.AnchorServerApplication;
import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import io.aeyer.anchor.server.persistence.entity.ChapterDbo;
import io.aeyer.anchor.server.persistence.entity.ChunkDbo;
import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import io.aeyer.anchor.server.persistence.entity.ParagraphDbo;
import io.aeyer.anchor.server.persistence.entity.SectionDbo;
import io.aeyer.anchor.server.persistence.repo.ChapterRepository;
import io.aeyer.anchor.server.persistence.repo.ChunkRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.ParagraphRepository;
import io.aeyer.anchor.server.persistence.repo.SectionRepository;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Hits a real pgvector running on {@code localhost:5432} (the docker-compose
 * stack at the repo root). Skipped when the port is unreachable so CI without
 * the stack — or local sessions where the dev hasn't run
 * {@code docker compose up -d postgres} — don't fail spuriously.
 *
 * Testcontainers has a long-standing flakey-discovery bug with Docker Desktop's
 * proxy socket on macOS (the {@code ~/.docker/run/docker.sock} returns a stub
 * {@code /info} that fails docker-java's validator), which is why this test
 * doesn't use {@code @Testcontainers}. The local docker-compose stack is the
 * authoritative pgvector test target.
 */
@SpringBootTest(classes = AnchorServerApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${anchor.test.postgres.url:jdbc:postgresql://localhost:5432/anchor}",
        "spring.datasource.username=anchor",
        "spring.datasource.password=anchor"
})
@EnabledIf("io.aeyer.anchor.server.persistence.PersistenceIntegrationTest#postgresIsReachable")
class PersistenceIntegrationTest {

    private static final String JDBC_URL = resolvedJdbcUrl();

    private static String resolvedJdbcUrl() {
        String fromProp = System.getProperty("anchor.test.postgres.url");
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        String fromEnv = System.getenv("ANCHOR_TEST_POSTGRES_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return "jdbc:postgresql://localhost:5432/anchor";
    }

    static boolean postgresIsReachable() {
        // Probe with the actual JDBC creds — port 5432 may be holding an unrelated
        // postgres (e.g. another project's docker-compose), in which case auth will
        // fail and we should skip rather than report a misleading test failure.
        try (var c = DriverManager.getConnection(JDBC_URL, "anchor", "anchor")) {
            try (var st = c.createStatement(); var rs = st.executeQuery(
                    "SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
                return rs.next();
            }
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired DocumentRepository documents;
    @Autowired ChapterRepository chapters;
    @Autowired SectionRepository sections;
    @Autowired ParagraphRepository paragraphs;
    @Autowired ChunkRepository chunks;

    @Test
    void roundtrips_full_hierarchy_through_jpa_and_returns_eager_domain_records() {
        DocumentDbo docDbo = newDocumentDbo("Roundtrip paper " + UUID.randomUUID());
        documents.save(docDbo);

        ChapterDbo chapterDbo = newChapterDbo(docDbo.getId(), 0, "Chapter 1");
        chapters.save(chapterDbo);

        SectionDbo sectionDbo = newSectionDbo(chapterDbo.getId(), 0, "Abstract");
        sections.save(sectionDbo);

        ParagraphDbo paragraphDbo = newParagraphDbo(sectionDbo.getId(), 0, "We report a new catalyst.");
        paragraphs.save(paragraphDbo);

        float[] embedding = new float[768];
        for (int i = 0; i < 768; i++) embedding[i] = (i % 7) * 0.01f;
        ChunkDbo chunkDbo = newChunkDbo(paragraphDbo.getId(), 0, "We report a new catalyst.", embedding);
        chunks.save(chunkDbo);

        Document loaded = documents.findAsDomain(docDbo.getId()).orElseThrow();
        assertThat(loaded.title()).startsWith("Roundtrip paper");
        assertThat(loaded.docSummarySource()).isEqualTo(DocSummarySource.GENERATED);
        assertThat(loaded.metadata()).containsEntry("source", "test");

        ChunkWithAncestors withAncestors =
                documents.findChunkWithAncestorsAsDomain(chunkDbo.getId()).orElseThrow();
        assertThat(withAncestors.chunk().text()).isEqualTo("We report a new catalyst.");
        assertThat(withAncestors.chunk().embedding()).hasSize(768);
        assertThat(withAncestors.chunk().embedding()[7]).isZero();
        assertThat(withAncestors.paragraph().rawText()).isEqualTo("We report a new catalyst.");
        assertThat(withAncestors.section().title()).isEqualTo("Abstract");
        assertThat(withAncestors.chapter().title()).isEqualTo("Chapter 1");
        assertThat(withAncestors.document().title()).startsWith("Roundtrip paper");
    }

    @Test
    void document_context_walks_full_hierarchy_in_order() {
        DocumentDbo docDbo = newDocumentDbo("Multi-chapter " + UUID.randomUUID());
        documents.save(docDbo);
        for (int c = 0; c < 2; c++) {
            ChapterDbo chapterDbo = newChapterDbo(docDbo.getId(), c, "Chapter " + (c + 1));
            chapters.save(chapterDbo);
            for (int s = 0; s < 2; s++) {
                SectionDbo sectionDbo = newSectionDbo(chapterDbo.getId(), s, "Section " + s);
                sections.save(sectionDbo);
                ParagraphDbo paragraphDbo = newParagraphDbo(sectionDbo.getId(), 0, "Paragraph c" + c + "s" + s);
                paragraphs.save(paragraphDbo);
            }
        }

        DocumentContext ctx = documents.findDocumentContextAsDomain(docDbo.getId()).orElseThrow();

        assertThat(ctx.document().id()).isEqualTo(docDbo.getId());
        assertThat(ctx.chapters()).hasSize(2);
        assertThat(ctx.chapters().get(0).chapter().ordinal()).isEqualTo(0);
        assertThat(ctx.chapters().get(1).chapter().ordinal()).isEqualTo(1);
        assertThat(ctx.chapters().get(0).sections()).hasSize(2);
        assertThat(ctx.chapters().get(0).sections().get(0).paragraphs()).hasSize(1);
        assertThat(ctx.chapters().get(0).sections().get(0).paragraphs().get(0).paragraph().rawText())
                .isEqualTo("Paragraph c0s0");
    }

    @Test
    void domain_records_can_be_passed_across_threads_with_no_session() throws Exception {
        // Materialise on the JPA-bound thread, then read everything from a worker thread —
        // SPEC §7.1: domain records cross thread boundaries. If anything is lazy this throws.
        DocumentDbo docDbo = newDocumentDbo("Thread test " + UUID.randomUUID());
        documents.save(docDbo);
        ChapterDbo chapterDbo = newChapterDbo(docDbo.getId(), 0, "C1");
        chapters.save(chapterDbo);
        SectionDbo sectionDbo = newSectionDbo(chapterDbo.getId(), 0, "S1");
        sections.save(sectionDbo);
        ParagraphDbo paragraphDbo = newParagraphDbo(sectionDbo.getId(), 0, "para text");
        paragraphs.save(paragraphDbo);
        ChunkDbo chunkDbo = newChunkDbo(paragraphDbo.getId(), 0, "chunk text", new float[768]);
        chunks.save(chunkDbo);

        ChunkWithAncestors withAncestors =
                documents.findChunkWithAncestorsAsDomain(chunkDbo.getId()).orElseThrow();

        Thread reader = new Thread(() -> {
            // Touch every field — proxies would throw LazyInitializationException here.
            withAncestors.document().title();
            withAncestors.chapter().title();
            withAncestors.section().title();
            withAncestors.paragraph().rawText();
            withAncestors.chunk().text();
            float[] vec = withAncestors.chunk().embedding();
            assertThat(vec).hasSize(768);
        });
        reader.start();
        reader.join(5000);
        assertThat(reader.isAlive()).isFalse();
    }

    private DocumentDbo newDocumentDbo(String title) {
        DocumentDbo d = new DocumentDbo();
        d.setId(UUID.randomUUID());
        d.setTitle(title);
        d.setSourcePath("/tmp/" + title);
        d.setDocSummary("placeholder summary");
        d.setDocSummarySource(DocSummarySource.GENERATED);
        d.setIngestedAt(Instant.now());
        d.setMetadata(Map.of("source", "test"));
        return d;
    }

    private ChapterDbo newChapterDbo(UUID docId, int ord, String title) {
        ChapterDbo c = new ChapterDbo();
        c.setId(UUID.randomUUID());
        c.setDocumentId(docId);
        c.setOrdinal(ord);
        c.setTitle(title);
        c.setSummary("chapter summary");
        c.setSynthetic(false);
        return c;
    }

    private SectionDbo newSectionDbo(UUID chapterId, int ord, String title) {
        SectionDbo s = new SectionDbo();
        s.setId(UUID.randomUUID());
        s.setChapterId(chapterId);
        s.setOrdinal(ord);
        s.setTitle(title);
        s.setSummary("section summary");
        return s;
    }

    private ParagraphDbo newParagraphDbo(UUID sectionId, int ord, String text) {
        ParagraphDbo p = new ParagraphDbo();
        p.setId(UUID.randomUUID());
        p.setSectionId(sectionId);
        p.setOrdinal(ord);
        p.setRawText(text);
        p.setSummary("paragraph summary");
        return p;
    }

    private ChunkDbo newChunkDbo(UUID paragraphId, int ord, String text, float[] embedding) {
        ChunkDbo c = new ChunkDbo();
        c.setId(UUID.randomUUID());
        c.setParagraphId(paragraphId);
        c.setOrdinal(ord);
        c.setText(text);
        c.setEmbedding(embedding);
        return c;
    }
}
