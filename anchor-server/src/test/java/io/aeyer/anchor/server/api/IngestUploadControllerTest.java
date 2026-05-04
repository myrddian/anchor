package io.aeyer.anchor.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.service.IngestJobRunner;
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
 * Pure MVC test — no Spring context, no pgvector, no LLM. The controller's
 * job is "save the multipart to disk, hand the path to IngestJobRunner,
 * return 202 with the job envelope". We mock IngestJobRunner and verify
 * the file landed in the upload directory and that the path passed to
 * submit() matches what we just wrote.
 */
class IngestUploadControllerTest {

    @TempDir Path uploadDir;

    private IngestJobRunner runner;
    private IngestApiMapper apiMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws IOException {
        runner = Mockito.mock(IngestJobRunner.class);
        apiMapper = new IngestApiMapper();

        when(runner.submit(anyString())).thenAnswer(invocation -> {
            IngestJob job = new IngestJob(UUID.randomUUID(), invocation.getArgument(0), Instant.now());
            return job;
        });

        IngestUploadController controller =
                new IngestUploadController(runner, apiMapper, uploadDir.toString());
        controller.ensureUploadDir(); // @PostConstruct doesn't fire in standalone setup
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void uploaded_pdf_lands_in_upload_dir_then_routes_through_job_runner() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 fake content".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "Smith2024.pdf", "application/pdf", pdfBytes);

        mvc.perform(multipart("/ingest/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.job_id").exists())
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.progress_url").value(org.hamcrest.Matchers.startsWith("/ingest/jobs/")));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(runner).submit(pathCaptor.capture());
        Path savedFile = Path.of(pathCaptor.getValue());
        assertThat(savedFile).exists();
        assertThat(savedFile.getFileName().toString()).isEqualTo("Smith2024.pdf");
        assertThat(savedFile.startsWith(uploadDir)).isTrue();
        assertThat(Files.readAllBytes(savedFile)).isEqualTo(pdfBytes);
    }

    @Test
    void empty_upload_returns_400_without_calling_runner() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "x.pdf", "application/pdf", new byte[0]);

        mvc.perform(multipart("/ingest/upload").file(empty))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(runner);
    }

    @Test
    void path_traversal_in_filename_is_sanitised() throws Exception {
        MockMultipartFile evil = new MockMultipartFile(
                "file", "../../etc/passwd.pdf", "application/pdf", "ok".getBytes());

        mvc.perform(multipart("/ingest/upload").file(evil)).andExpect(status().isAccepted());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(runner).submit(pathCaptor.capture());
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
            mvc.perform(multipart("/ingest/upload").file(file)).andExpect(status().isAccepted());
        }

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(runner, Mockito.times(2)).submit(pathCaptor.capture());
        Path first = Path.of(pathCaptor.getAllValues().get(0));
        Path second = Path.of(pathCaptor.getAllValues().get(1));
        assertThat(first.getParent()).isNotEqualTo(second.getParent());
        assertThat(first.getFileName()).isEqualTo(second.getFileName()); // same filename, different dir
    }
}
