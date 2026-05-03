package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import io.aeyer.anchor.server.AnchorServerApplication;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = AnchorServerApplication.class)
@TestPropertySource(properties = {
        "spring.datasource.url=${anchor.test.postgres.url:jdbc:postgresql://localhost:5433/anchor}",
        "spring.datasource.username=anchor",
        "spring.datasource.password=anchor"
})
@EnabledIf("io.aeyer.anchor.server.service.IngestServiceIntegrationTest#postgresIsReachable")
class IngestServiceIntegrationTest {

    private static final String JDBC_URL = resolvedJdbcUrl();

    private static String resolvedJdbcUrl() {
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
    @Autowired IngestService ingest;
    @Autowired DocumentRepository documents;

    @Test
    void ingest_round_trip_persists_full_hierarchy_and_assigns_stable_id(@TempDir Path tempDir) throws IOException {
        // The summariser cascades; mocking each level separately would be brittle.
        // Just return a deterministic stub for any chat call.
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("test summary", "stop", null));
        // Embed each input as a 768-dim zero vector; the assertion only cares that
        // counts agree, not vector semantics.
        when(llm.embedBatch(any())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            List<Embedding> result = new ArrayList<>(inputs.size());
            for (int i = 0; i < inputs.size(); i++) result.add(new Embedding(new float[768]));
            return result;
        });

        Path pdf = writePdf(tempDir.resolve("paper.pdf"));

        IngestService.IngestResult first = ingest.ingest(pdf.toString());

        assertThat(first.title()).isEqualTo("paper");
        assertThat(first.chapterCount()).isGreaterThanOrEqualTo(1);
        assertThat(first.sectionCount()).isGreaterThanOrEqualTo(1);
        assertThat(first.paragraphCount()).isGreaterThanOrEqualTo(1);
        assertThat(first.chunkCount()).isGreaterThanOrEqualTo(1);

        Document loaded = documents.findAsDomain(first.documentId()).orElseThrow();
        assertThat(loaded.docSummary()).isEqualTo("test summary");

        DocumentContext ctx = documents.findDocumentContextAsDomain(first.documentId()).orElseThrow();
        assertThat(ctx.chapters()).hasSize(first.chapterCount());

        // Re-ingest the same file → same stable id, replaces row, no orphaned chapters.
        IngestService.IngestResult second = ingest.ingest(pdf.toString());
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(documents.findAllAsDomain().stream()
                .filter(d -> d.id().equals(first.documentId())).count()).isEqualTo(1);
    }

    private Path writePdf(Path path) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.beginText();
                cs.newLineAtOffset(72, 750);
                cs.showText("Chapter 1 Synthesis");
                cs.newLineAtOffset(0, -20);
                cs.showText("Abstract");
                cs.newLineAtOffset(0, -20);
                cs.showText("We report a new ruthenium catalyst.");
                cs.newLineAtOffset(0, -20);
                cs.showText("Methods");
                cs.newLineAtOffset(0, -20);
                cs.showText("Reactions ran under inert atmosphere.");
                cs.endText();
            }
            doc.save(path.toFile());
        }
        return path;
    }
}
