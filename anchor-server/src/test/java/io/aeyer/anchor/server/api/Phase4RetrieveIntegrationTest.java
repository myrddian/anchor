package io.aeyer.anchor.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.anchor.server.AnchorServerApplication;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.llm.LMStudioClient;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = AnchorServerApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${anchor.test.postgres.url:jdbc:postgresql://localhost:5433/anchor}",
        "spring.datasource.username=anchor",
        "spring.datasource.password=anchor"
})
@EnabledIf("io.aeyer.anchor.server.api.Phase4RetrieveIntegrationTest#postgresIsReachable")
class Phase4RetrieveIntegrationTest {

    private static final String JDBC_URL = resolved();

    private static String resolved() {
        String fromProp = System.getProperty("anchor.test.postgres.url");
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        String fromEnv = System.getenv("ANCHOR_TEST_POSTGRES_URL");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return "jdbc:postgresql://localhost:5433/anchor";
    }

    static boolean postgresIsReachable() {
        try (var c = DriverManager.getConnection(JDBC_URL, "anchor", "anchor");
             var st = c.createStatement();
             var rs = st.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'vector'")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    @MockBean LMStudioClient llm;
    @Autowired WebApplicationContext webApp;
    @Autowired DocumentRepository documents;
    @Autowired ChapterRepository chapters;
    @Autowired SectionRepository sections;
    @Autowired ParagraphRepository paragraphs;
    @Autowired ChunkRepository chunks;

    private MockMvc mvc;
    private UUID seededDocumentId;
    private UUID closeChunkId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApp).build();
        seedData();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        if (seededDocumentId != null) documents.deleteById(seededDocumentId);
    }

    @Test
    void retrieve_returns_chunks_wrapped_with_full_ancestor_stack() throws Exception {
        when(llm.embedBatch(any())).thenReturn(List.of(new Embedding(unit(1.0f))));

        mvc.perform(post("/retrieve")
                        .contentType("application/json")
                        .content("{\"query\":\"selectivity\",\"document_id\":\"" + seededDocumentId
                                + "\",\"k\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.k").value(3))
                .andExpect(jsonPath("$.hits[0].chunk_id").value(closeChunkId.toString()))
                .andExpect(jsonPath("$.hits[0].section_title").value("Methods"))
                .andExpect(jsonPath("$.hits[0].chapter_title").value("Chapter 1"))
                .andExpect(jsonPath("$.hits[0].document_title").value("Phase 4 retrieve paper"));
    }

    @Test
    void retrieve_works_corpus_wide_when_document_id_omitted() throws Exception {
        when(llm.embedBatch(any())).thenReturn(List.of(new Embedding(unit(1.0f))));

        mvc.perform(post("/retrieve")
                        .contentType("application/json")
                        .content("{\"query\":\"selectivity\",\"k\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits[0].document_title").exists());
    }

    @Test
    void retrieve_400s_on_blank_query() throws Exception {
        mvc.perform(post("/retrieve")
                        .contentType("application/json")
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private float[] unit(float bias) {
        float[] v = new float[768];
        v[0] = bias;
        return v;
    }

    private void seedData() {
        DocumentDbo doc = new DocumentDbo();
        doc.setId(UUID.randomUUID());
        doc.setTitle("Phase 4 retrieve paper");
        doc.setSourcePath("/tmp/phase4");
        doc.setDocSummary("Document on selective catalysis.");
        doc.setDocSummarySource(DocSummarySource.GENERATED);
        doc.setIngestedAt(Instant.now());
        doc.setMetadata(Map.of());
        documents.save(doc);
        seededDocumentId = doc.getId();

        ChapterDbo ch = new ChapterDbo();
        ch.setId(UUID.randomUUID());
        ch.setDocumentId(doc.getId());
        ch.setOrdinal(0);
        ch.setTitle("Chapter 1");
        ch.setSummary("Catalyst introduction.");
        ch.setSynthetic(false);
        chapters.save(ch);

        SectionDbo sec = new SectionDbo();
        sec.setId(UUID.randomUUID());
        sec.setChapterId(ch.getId());
        sec.setOrdinal(0);
        sec.setTitle("Methods");
        sec.setSummary("Selective conditions.");
        sections.save(sec);

        ParagraphDbo para = new ParagraphDbo();
        para.setId(UUID.randomUUID());
        para.setSectionId(sec.getId());
        para.setOrdinal(0);
        para.setRawText("text");
        para.setSummary("para summary");
        paragraphs.save(para);

        ChunkDbo close = new ChunkDbo();
        close.setId(UUID.randomUUID());
        close.setParagraphId(para.getId());
        close.setOrdinal(0);
        close.setText("the catalyst is selective");
        close.setEmbedding(unit(1.0f));
        chunks.save(close);
        closeChunkId = close.getId();

        ChunkDbo far = new ChunkDbo();
        far.setId(UUID.randomUUID());
        far.setParagraphId(para.getId());
        far.setOrdinal(1);
        far.setText("unrelated content");
        far.setEmbedding(unit(-1.0f));
        chunks.save(far);
    }
}
