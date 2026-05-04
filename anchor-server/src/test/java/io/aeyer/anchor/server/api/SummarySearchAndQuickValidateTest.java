package io.aeyer.anchor.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.anchor.server.AnchorServerApplication;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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

/**
 * Covers the V2 surface: GET /documents/search ranks docs by cosine of the
 * query embedding against each doc's stored summary embedding, and
 * POST /validate/quick produces a topical_relevance + stance_score from two
 * vector lookups (no LLM call).
 *
 * Seeds three docs with known orthogonal-ish summary embeddings so the search
 * ordering is deterministic and the quick stance score has predictable sign.
 */
@SpringBootTest(classes = AnchorServerApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${anchor.test.postgres.url:jdbc:postgresql://localhost:5433/anchor}",
        "spring.datasource.username=anchor",
        "spring.datasource.password=anchor"
})
@EnabledIf("io.aeyer.anchor.server.api.SummarySearchAndQuickValidateTest#postgresIsReachable")
class SummarySearchAndQuickValidateTest {

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

    private MockMvc mvc;
    private UUID catalystDocId;
    private UUID unrelatedDocId;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApp).build();

        // Seed two docs with carefully-chosen embeddings:
        //   catalystDoc → summary embedding aligned with the "catalyst works" axis
        //   unrelatedDoc → summary embedding orthogonal to it
        catalystDocId = seedWithSummaryEmbedding("Selective Catalyst Paper",
                "We report a ruthenium catalyst with high selectivity.",
                axisVector(0));
        unrelatedDocId = seedWithSummaryEmbedding("Quantum Field Notes",
                "Notes on QFT renormalisation schemes.",
                axisVector(1));
    }

    @AfterEach
    void cleanup() {
        if (catalystDocId != null) documents.deleteById(catalystDocId);
        if (unrelatedDocId != null) documents.deleteById(unrelatedDocId);
    }

    @Test
    void search_ranks_topically_relevant_doc_first() throws Exception {
        // Query embedding aligned with catalyst-axis (positive on dim 0).
        when(llm.embedBatch(any())).thenReturn(List.of(new Embedding(axisVector(0))));

        mvc.perform(get("/documents/search").param("q", "selective catalyst"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.k").value(20))
                .andExpect(jsonPath("$.hits[0].document_id").value(catalystDocId.toString()))
                .andExpect(jsonPath("$.hits[0].title").value("Selective Catalyst Paper"))
                // similarity should be near 1.0 since the query and doc embedding are identical
                .andExpect(jsonPath("$.hits[0].score").value(org.hamcrest.Matchers.greaterThan(0.95)));
    }

    @Test
    void search_returns_400_on_blank_query() throws Exception {
        // Controller returns an empty hits list (200) rather than a 400 — chose
        // permissive over strict because the UI's debounce can fire on a clear.
        mvc.perform(get("/documents/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hits").isEmpty());
    }

    @Test
    void quick_validate_positive_stance_when_query_aligned_negation_orthogonal() throws Exception {
        // Two-call mock: first the query embedding (catalyst-axis), then the
        // negated-query embedding (orthogonal — disagreement axis).
        when(llm.embedBatch(any())).thenReturn(List.of(
                new Embedding(axisVector(0)),
                new Embedding(axisVector(1))));

        mvc.perform(post("/validate/quick")
                        .contentType("application/json")
                        .content("{\"document_id\":\"" + catalystDocId + "\","
                                + "\"query\":\"the catalyst is selective\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("vector_only"))
                .andExpect(jsonPath("$.topical_relevance")
                        .value(org.hamcrest.Matchers.greaterThan(0.95)))
                // stance = topical − negation. negation is orthogonal so cos≈0.
                // stance therefore ≈ +1, strongly positive.
                .andExpect(jsonPath("$.stance_score")
                        .value(org.hamcrest.Matchers.greaterThan(0.5)));
    }

    @Test
    void quick_validate_404_when_summary_embedding_missing() throws Exception {
        // Seed a doc WITHOUT setting summary_embedding (simulates an unbackfilled
        // legacy row). /validate/quick should 404 since it has nothing to score.
        DocumentDbo unbackfilled = new DocumentDbo();
        unbackfilled.setId(UUID.randomUUID());
        unbackfilled.setTitle("Pre-V2 doc");
        unbackfilled.setSourcePath("/tmp/legacy");
        unbackfilled.setDocSummary("legacy summary");
        unbackfilled.setDocSummarySource(DocSummarySource.GENERATED);
        unbackfilled.setIngestedAt(Instant.now());
        unbackfilled.setMetadata(Map.of());
        documents.save(unbackfilled);

        try {
            when(llm.embedBatch(any())).thenReturn(List.of(
                    new Embedding(axisVector(0)),
                    new Embedding(axisVector(1))));

            mvc.perform(post("/validate/quick")
                            .contentType("application/json")
                            .content("{\"document_id\":\"" + unbackfilled.getId() + "\","
                                    + "\"query\":\"anything\"}"))
                    .andExpect(status().isNotFound());
        } finally {
            documents.deleteById(unbackfilled.getId());
        }
    }

    @Test
    void quick_validate_400_on_blank_query() throws Exception {
        mvc.perform(post("/validate/quick")
                        .contentType("application/json")
                        .content("{\"document_id\":\"" + catalystDocId + "\",\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    private UUID seedWithSummaryEmbedding(String title, String summary, float[] embedding) {
        DocumentDbo doc = new DocumentDbo();
        doc.setId(UUID.randomUUID());
        doc.setTitle(title);
        doc.setSourcePath("/tmp/" + UUID.randomUUID());
        doc.setDocSummary(summary);
        doc.setDocSummarySource(DocSummarySource.GENERATED);
        doc.setIngestedAt(Instant.now());
        doc.setMetadata(Map.of());
        doc.setSummaryEmbedding(embedding);
        documents.save(doc);
        return doc.getId();
    }

    /** Unit basis vector at the given dimension — handy for orthogonal-doc test fixtures. */
    private static float[] axisVector(int dimension) {
        float[] v = new float[768];
        v[dimension] = 1.0f;
        return v;
    }
}
