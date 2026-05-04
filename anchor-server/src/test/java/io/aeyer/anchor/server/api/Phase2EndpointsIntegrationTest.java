package io.aeyer.anchor.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.anchor.server.AnchorServerApplication;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.llm.ChatCompletion;
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
@EnabledIf("io.aeyer.anchor.server.api.Phase2EndpointsIntegrationTest#postgresIsReachable")
class Phase2EndpointsIntegrationTest {

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
    private UUID seededChunkId;
    private UUID otherChunkId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApp).build();
        seedData();
    }

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        // Cascade FK takes care of chapters → sections → paragraphs → chunks.
        if (seededDocumentId != null) documents.deleteById(seededDocumentId);
    }

    @Test
    void list_documents_returns_summaries_with_counts() throws Exception {
        mvc.perform(get("/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents").isArray())
                .andExpect(jsonPath("$.total").exists())
                .andExpect(jsonPath("$.documents[?(@.document_id == '" + seededDocumentId + "')].chapter_count").value(1))
                .andExpect(jsonPath("$.documents[?(@.document_id == '" + seededDocumentId + "')].section_count").value(1))
                .andExpect(jsonPath("$.documents[?(@.document_id == '" + seededDocumentId + "')].chunk_count").value(2));
    }

    @Test
    void list_documents_filters_by_query_substring() throws Exception {
        mvc.perform(get("/documents").param("q", "Phase 2 paper"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.documents[0].document_id").value(seededDocumentId.toString()));
    }

    @Test
    void detail_returns_full_chapter_section_hierarchy_without_raw_text() throws Exception {
        mvc.perform(get("/documents/" + seededDocumentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Phase 2 paper"))
                .andExpect(jsonPath("$.chapters[0].title").value("Chapter 1"))
                .andExpect(jsonPath("$.chapters[0].sections[0].title").value("Methods"));
    }

    @Test
    void detail_returns_404_for_unknown_document() throws Exception {
        mvc.perform(get("/documents/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void chunk_endpoint_returns_full_ancestor_chain() throws Exception {
        mvc.perform(get("/chunks/" + seededChunkId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("the catalyst supports the reaction"))
                .andExpect(jsonPath("$.section_title").value("Methods"))
                .andExpect(jsonPath("$.chapter_title").value("Chapter 1"))
                .andExpect(jsonPath("$.document_title").value("Phase 2 paper"));
    }

    @Test
    void chunk_endpoint_returns_404_for_unknown_chunk() throws Exception {
        mvc.perform(get("/chunks/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void validate_returns_judgment_without_alternatives_when_stance_supports() throws Exception {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("""
                        {"is_load_bearing": true,
                         "argumentative_role": "AUTHOR_POSITION",
                         "document_stance_on_query": "SUPPORTS",
                         "qualifying_context": "",
                         "reasoning": "matches the central claim"}
                        """, "stop", null));

        mvc.perform(post("/validate")
                        .contentType("application/json")
                        .content("{\"chunk_id\":\"" + seededChunkId + "\",\"query\":\"does the catalyst work?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.is_load_bearing").value(true))
                .andExpect(jsonPath("$.argumentative_role").value("AUTHOR_POSITION"))
                .andExpect(jsonPath("$.document_stance_on_query").value("SUPPORTS"))
                .andExpect(jsonPath("$.alternative_chunks").isEmpty());
    }

    @Test
    void validate_runs_alternative_discovery_when_role_is_steelman_refuted_later() throws Exception {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("""
                        {"is_load_bearing": true,
                         "argumentative_role": "STEELMAN_REFUTED_LATER",
                         "document_stance_on_query": "REJECTS",
                         "qualifying_context": "Refuted in Methods.",
                         "reasoning": "Author later rejects this position."}
                        """, "stop", null));
        // Embedding for "not <query>" — return a vector close to the OTHER chunk's
        // embedding so similarity-search ranks it first.
        when(llm.embedBatch(any())).thenAnswer(inv -> {
            List<String> inputs = inv.getArgument(0);
            return inputs.stream().map(s -> new Embedding(nearOther())).toList();
        });

        mvc.perform(post("/validate")
                        .contentType("application/json")
                        .content("{\"chunk_id\":\"" + seededChunkId + "\",\"query\":\"does the catalyst work?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.argumentative_role").value("STEELMAN_REFUTED_LATER"))
                .andExpect(jsonPath("$.alternative_chunks").isArray())
                .andExpect(jsonPath("$.alternative_chunks[0].chunk_id").value(otherChunkId.toString()));
    }

    @Test
    void validate_returns_404_for_unknown_chunk() throws Exception {
        mvc.perform(post("/validate")
                        .contentType("application/json")
                        .content("{\"chunk_id\":\"" + UUID.randomUUID() + "\",\"query\":\"q\"}"))
                .andExpect(status().isNotFound());
    }

    private void seedData() {
        DocumentDbo doc = new DocumentDbo();
        doc.setId(UUID.randomUUID());
        doc.setTitle("Phase 2 paper");
        doc.setSourcePath("/tmp/phase2");
        doc.setDocSummary("Document claims the catalyst is selective.");
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
        ch.setSummary("chapter claim");
        ch.setSynthetic(false);
        chapters.save(ch);

        SectionDbo sec = new SectionDbo();
        sec.setId(UUID.randomUUID());
        sec.setChapterId(ch.getId());
        sec.setOrdinal(0);
        sec.setTitle("Methods");
        sec.setSummary("section claim");
        sections.save(sec);

        ParagraphDbo para = new ParagraphDbo();
        para.setId(UUID.randomUUID());
        para.setSectionId(sec.getId());
        para.setOrdinal(0);
        para.setRawText("Some raw paragraph text");
        para.setSummary("paragraph claim");
        paragraphs.save(para);

        ChunkDbo target = new ChunkDbo();
        target.setId(UUID.randomUUID());
        target.setParagraphId(para.getId());
        target.setOrdinal(0);
        target.setText("the catalyst supports the reaction");
        target.setEmbedding(makeVector(1.0f));
        chunks.save(target);
        seededChunkId = target.getId();

        ChunkDbo other = new ChunkDbo();
        other.setId(UUID.randomUUID());
        other.setParagraphId(para.getId());
        other.setOrdinal(1);
        other.setText("the catalyst poisons after one cycle");
        other.setEmbedding(makeVector(-1.0f));
        chunks.save(other);
        otherChunkId = other.getId();
    }

    private float[] makeVector(float bias) {
        float[] v = new float[768];
        v[0] = bias;
        return v;
    }

    private float[] nearOther() {
        // Match the "other" chunk's embedding so it ranks first in the cosine search.
        return makeVector(-1.0f);
    }
}
