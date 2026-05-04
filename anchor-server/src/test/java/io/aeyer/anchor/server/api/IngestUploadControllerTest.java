package io.aeyer.anchor.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.service.IngestService;
import io.aeyer.anchor.server.service.TokenLedger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Pure MVC test — no Spring context, no pgvector. The controller's job is
 * "save the multipart to disk, hand the path to IngestService, return its
 * envelope". We mock IngestService and verify the file landed in the upload
 * directory with its original name and that the path passed to ingest()
 * matches what we just wrote.
 */
class IngestUploadControllerTest {

    @TempDir Path uploadDir;

    private IngestService ingest;
    private IngestApiMapper apiMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        ingest = Mockito.mock(IngestService.class);
        apiMapper = new IngestApiMapper();

        // Stub IngestService to return a deterministic envelope so the test can
        // assert the controller mapped through cleanly without exercising the
        // pipeline (which needs LM Studio + pgvector).
        when(ingest.ingest(any())).thenAnswer(invocation -> new IngestService.IngestResult(
                UUID.randomUUID(), "Test Paper", invocation.getArgument(0),
                1, 2, 3, 4, Instant.now(),
                new TokenLedger.Snapshot(0, 0, 0)));

        IngestUploadController controller =
                new IngestUploadController(ingest, apiMapper, uploadDir.toString());
        controller.ensureUploadDir(); // @PostConstruct doesn't fire in standalone setup
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void uploaded_pdf_lands_in_upload_dir_then_routes_through_ingest_service() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 fake content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "Smith2024.pdf", "application/pdf", pdfBytes);

        mvc.perform(multipart("/ingest/upload").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Test Paper"))
                .andExpect(jsonPath("$.chapter_count").value(1))
                .andExpect(jsonPath("$.chunk_count").value(4));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ingest).ingest(pathCaptor.capture());
        Path savedFile = Path.of(pathCaptor.getValue());
        assertThat(savedFile).exists();
        assertThat(savedFile.getFileName().toString()).isEqualTo("Smith2024.pdf");
        assertThat(savedFile.startsWith(uploadDir)).isTrue();
        assertThat(Files.readAllBytes(savedFile)).isEqualTo(pdfBytes);
    }

    @Test
    void empty_upload_returns_400_without_calling_ingest() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/ingest/upload").file(empty))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(ingest);
    }

    @Test
    void path_traversal_in_filename_is_sanitised() throws Exception {
        MockMultipartFile evil = new MockMultipartFile(
                "file", "../../etc/passwd.pdf", "application/pdf", "ok".getBytes());

        mvc.perform(multipart("/ingest/upload").file(evil)).andExpect(status().isCreated());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ingest).ingest(pathCaptor.capture());
        Path savedFile = Path.of(pathCaptor.getValue());
        assertThat(savedFile.startsWith(uploadDir))
                .as("save path must stay inside the configured upload dir")
                .isTrue();
        assertThat(savedFile.getFileName().toString())
                .as("path separators stripped, traversal dots collapsed")
                .doesNotContain("/", "\\")
                .doesNotContain("..");
    }

    @Test
    void two_uploads_with_same_filename_get_distinct_per_upload_subdirs() throws Exception {
        for (int i = 0; i < 2; i++) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "paper.pdf", "application/pdf", ("v" + i).getBytes());
            mvc.perform(multipart("/ingest/upload").file(file)).andExpect(status().isCreated());
        }

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(ingest, Mockito.times(2)).ingest(pathCaptor.capture());
        Path first = Path.of(pathCaptor.getAllValues().get(0));
        Path second = Path.of(pathCaptor.getAllValues().get(1));
        assertThat(first.getParent()).isNotEqualTo(second.getParent());
        assertThat(first.getFileName()).isEqualTo(second.getFileName()); // same filename, different dir
    }
}
