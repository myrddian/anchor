package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.ingest.IngestJobAcceptedResponse;
import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.jobs.IngestJob;
import io.aeyer.anchor.server.service.IngestException;
import io.aeyer.anchor.server.service.IngestJobRunner;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Browser-friendly ingest. Saves the uploaded file to {@code anchor.upload-dir},
 * mints an async ingest job, and returns 202 with the job id so the UI can
 * poll progress without hanging on a multi-minute book ingest.
 *
 * Each upload lands in its own UUID directory so original filenames are
 * preserved. Two uploads of the same paper still produce the same content
 * hash → same stable document id → idempotent replace inside the pipeline.
 */
@RestController
public class IngestUploadController {

    private static final Logger log = LoggerFactory.getLogger(IngestUploadController.class);

    private final IngestJobRunner runner;
    private final IngestApiMapper apiMapper;
    private final Path uploadRoot;

    public IngestUploadController(IngestJobRunner runner, IngestApiMapper apiMapper,
                                  @Value("${anchor.upload-dir:#{systemProperties['user.home']}/.anchor/uploads}")
                                  String uploadDir) {
        this.runner = runner;
        this.apiMapper = apiMapper;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureUploadDir() throws IOException {
        Files.createDirectories(uploadRoot);
        log.info("Ingest upload directory: {}", uploadRoot);
    }

    @PostMapping(value = "/ingest/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestJobAcceptedResponse> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Path saved;
        try {
            saved = saveToUploadDir(file);
        } catch (IOException e) {
            log.warn("Failed to save uploaded file {}", file.getOriginalFilename(), e);
            throw new IngestException("Could not save upload: " + e.getMessage(), e);
        }

        IngestJob job = runner.submit(saved.toString());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(apiMapper.toAccepted(job));
    }

    private Path saveToUploadDir(MultipartFile file) throws IOException {
        // Per-upload UUID dir keeps the original filename intact while making
        // collisions impossible. Two uploads of the same content land in
        // different dirs but produce the same content-hash → same document id,
        // so the existing idempotency in IngestService handles the dedup.
        String original = file.getOriginalFilename();
        String safeName = sanitiseFilename(original == null || original.isBlank()
                ? "upload.pdf"
                : original);
        Path subdir = uploadRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(subdir);
        Path destination = subdir.resolve(safeName);
        file.transferTo(destination);
        return destination;
    }

    /**
     * Strip path separators, control chars, and anything that'd let a malicious
     * filename break out of the upload directory. Spring's MultipartFile already
     * normalises most of this, but defence in depth: always derive the saved
     * filename ourselves rather than trusting the client.
     */
    private String sanitiseFilename(String raw) {
        String stripped = raw
                .replaceAll("[\\\\/]", "_")          // path separators
                .replaceAll("[\\p{Cntrl}]", "")     // control chars
                .replaceAll("\\.\\.+", ".")         // collapse path-traversal dots
                .trim();
        if (stripped.isEmpty()) return "upload.pdf";
        if (stripped.length() > 200) stripped = stripped.substring(0, 200);
        return stripped;
    }
}
