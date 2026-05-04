package io.aeyer.anchor.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.server.AnchorServerApplication;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.llm.LMStudioClient;
import java.util.concurrent.CompletableFuture;
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
import java.time.Duration;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest(classes = AnchorServerApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${anchor.test.postgres.url:jdbc:postgresql://localhost:5433/anchor}",
        "spring.datasource.username=anchor",
        "spring.datasource.password=anchor"
})
@EnabledIf("io.aeyer.anchor.server.api.Phase3AskIntegrationTest#postgresIsReachable")
class Phase3AskIntegrationTest {

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
    @Autowired ObjectMapper mapper;
    @Autowired DocumentRepository documents;
    @Autowired ChapterRepository chapters;
    @Autowired SectionRepository sections;
    @Autowired ParagraphRepository paragraphs;
    @Autowired ChunkRepository chunks;

    private MockMvc mvc;
    private UUID seededDocumentId;

    @org.junit.jupiter.api.AfterEach
    void cleanup() {
        if (seededDocumentId != null) documents.deleteById(seededDocumentId);
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webApp).build();
        seedDocument();
        // Default chat stub: route by which prompt content arrives. The proposer prompt
        // contains "Respond as the document"; critic contains "macro view"; synthesiser
        // contains "RESPONSE:" / "GROUNDING:" markers.
        when(llm.complete(any(), anyString(), any(Double.class))).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(1);
            if (prompt.contains("Respond as the document")) {
                return new ChatCompletion(
                        "I, the document, claim the catalyst is selective per Methods.",
                        "stop", null);
            }
            if (prompt.contains("macro view")) {
                return new ChatCompletion("""
                        {"challenges":["proposer overclaims selectivity"],
                         "challenges_count":1,
                         "macro_view_supports_proposer":"partially"}
                        """, "stop", null);
            }
            // synthesiser
            return new ChatCompletion("""
                    RESPONSE:
                    I claim selectivity, qualified by Methods §1.

                    GROUNDING:
                    {"grounded_in_sections":["Methods"],
                     "grounded_in_chapters":["Chapter 1"],
                     "refusals":[],
                     "confidence":"medium",
                     "incorporated_critic_challenges":[0],
                     "rejected_critic_challenges":[]}
                    """, "stop", null);
        });
        when(llm.embedBatch(any())).thenAnswer(inv -> {
            List<String> inputs = inv.getArgument(0);
            return inputs.stream().map(s -> new Embedding(unitVector())).toList();
        });
        // Streaming variant — proposer + synthesiser go through completeStreaming.
        // Resolve the future with the full response and (optionally) hand a token chunk
        // to the registered handler so the *_THOUGHT path is exercised.
        when(llm.completeStreaming(any(), anyString(), any(Double.class), any())).thenAnswer(invocation -> {
            String prompt = invocation.getArgument(1);
            ChatCompletion completion = synchronousCompletion(prompt);
            java.util.function.Consumer<String> handler = invocation.getArgument(3);
            handler.accept(completion.content());
            return CompletableFuture.completedFuture(completion);
        });
    }

    private ChatCompletion synchronousCompletion(String prompt) {
        if (prompt.contains("Respond as the document")) {
            return new ChatCompletion(
                    "I, the document, claim the catalyst is selective per Methods.",
                    "stop", null);
        }
        if (prompt.contains("macro view")) {
            return new ChatCompletion("""
                    {"challenges":["proposer overclaims selectivity"],
                     "challenges_count":1,
                     "macro_view_supports_proposer":"partially"}
                    """, "stop", null);
        }
        return new ChatCompletion("""
                RESPONSE:
                I claim selectivity, qualified by Methods §1.

                GROUNDING:
                {"grounded_in_sections":["Methods"],
                 "grounded_in_chapters":["Chapter 1"],
                 "refusals":[],
                 "confidence":"medium",
                 "incorporated_critic_challenges":[0],
                 "rejected_critic_challenges":[]}
                """, "stop", null);
    }

    @Test
    void ask_runs_three_agent_deliberation_with_correct_evidence_asymmetry() throws Exception {
        UUID jobId = submitAskAndAwaitTerminal();

        MvcResult result = mvc.perform(get("/jobs/" + jobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.proposer.evidence_access").value("FULL_HIERARCHY"))
                .andExpect(jsonPath("$.critic.evidence_access").value("MACRO_ONLY"))
                .andExpect(jsonPath("$.synthesiser.evidence_access").value("FULL_HIERARCHY_PLUS_DEBATE"))
                .andExpect(jsonPath("$.final_response").exists())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        // Critic challenges parsed and surfaced
        assertThatJson(body, "$.critic.challenges[0]", "proposer overclaims selectivity");
        // Synthesiser grounding parsed
        assertThatJson(body, "$.synthesiser.grounding.confidence", "medium");
    }

    @Test
    void ask_returns_404_for_unknown_document() throws Exception {
        mvc.perform(post("/documents/" + UUID.randomUUID() + "/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"q\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ask_returns_400_for_blank_query() throws Exception {
        mvc.perform(post("/documents/" + seededDocumentId + "/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_cancels_in_flight_job_or_no_op_if_already_terminal() throws Exception {
        UUID jobId = submitAskAndAwaitTerminal();
        mvc.perform(delete("/jobs/" + jobId)).andExpect(status().isNoContent());
        // Double delete still 204 — terminal jobs accept the no-op.
        mvc.perform(delete("/jobs/" + jobId)).andExpect(status().isNoContent());
    }

    @Test
    void delete_returns_404_for_unknown_job() throws Exception {
        mvc.perform(delete("/jobs/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    private UUID submitAskAndAwaitTerminal() throws Exception {
        MvcResult accepted = mvc.perform(post("/documents/" + seededDocumentId + "/ask")
                        .contentType("application/json")
                        .content("{\"query\":\"is the catalyst selective?\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.job_id").exists())
                .andReturn();

        UUID jobId = UUID.fromString(
                mapper.readTree(accepted.getResponse().getContentAsString()).get("job_id").asText());
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            MvcResult poll = mvc.perform(get("/jobs/" + jobId)).andReturn();
            String status = mapper.readTree(poll.getResponse().getContentAsString())
                    .get("status").asText();
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
                return jobId;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Job did not reach terminal state within 10s");
    }

    private void assertThatJson(JsonNode root, String pointer, String expected) {
        // Lightweight inline JSON-pointer-ish reader because the relevant assertions
        // here aren't expressible with simple jsonPath. Format: "$.a.b[0]".
        String[] parts = pointer.replace("$.", "").split("\\.");
        JsonNode cur = root;
        for (String part : parts) {
            int bracket = part.indexOf('[');
            if (bracket > 0) {
                String name = part.substring(0, bracket);
                int idx = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
                cur = cur.path(name).path(idx);
            } else {
                cur = cur.path(part);
            }
        }
        if (!cur.asText().equals(expected)) {
            throw new AssertionError("Expected " + pointer + " = " + expected + " but was " + cur);
        }
    }

    private float[] unitVector() {
        float[] v = new float[768];
        v[0] = 1.0f;
        return v;
    }

    private void seedDocument() {
        DocumentDbo doc = new DocumentDbo();
        doc.setId(UUID.randomUUID());
        doc.setTitle("Phase 3 ask paper");
        doc.setSourcePath("/tmp/phase3");
        doc.setDocSummary("Document claims a new catalyst is selective.");
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
        ch.setSummary("Introduces a ruthenium catalyst with high selectivity.");
        ch.setSynthetic(false);
        chapters.save(ch);

        SectionDbo sec = new SectionDbo();
        sec.setId(UUID.randomUUID());
        sec.setChapterId(ch.getId());
        sec.setOrdinal(0);
        sec.setTitle("Methods");
        sec.setSummary("Reactions ran under inert atmosphere with selectivity 19:1.");
        sections.save(sec);

        ParagraphDbo para = new ParagraphDbo();
        para.setId(UUID.randomUUID());
        para.setSectionId(sec.getId());
        para.setOrdinal(0);
        para.setRawText("Selectivity reached 19 to 1.");
        para.setSummary("Selectivity reached 19:1.");
        paragraphs.save(para);

        ChunkDbo c1 = new ChunkDbo();
        c1.setId(UUID.randomUUID());
        c1.setParagraphId(para.getId());
        c1.setOrdinal(0);
        c1.setText("the catalyst achieved 19:1 selectivity");
        c1.setEmbedding(unitVector());
        chunks.save(c1);
    }
}
