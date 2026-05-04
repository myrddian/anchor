package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.ingest.IngestResponse;
import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.service.IngestException;
import io.aeyer.anchor.server.service.IngestService;
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
 * Browser-friendly ingest. The plain POST /ingest endpoint takes a
 * server-side path — fine for scripts and the shell, useless for the web UI
 * since the chemist has no filesystem access. This sibling endpoint accepts
 * a multipart file, saves it under {@code anchor.upload-dir}, then delegates
 * to the existing {@link IngestService} so the rest of the pipeline
 * (parse → summarise → embed → persist) is identical.
 *
 * Each upload lands in its own UUID directory so original filenames are
 * preserved (and collisions can't truncate or rename), but two uploads of
 * the same paper produce the same {@code sha256(bytes)} → same stable
 * document id → idempotent replace via the existing /ingest path. So no
 * dedup logic needed here.
 */
@RestController
public class IngestUploadController {

    private static final Logger log = LoggerFactory.getLogger(IngestUploadController.class);

    private final IngestService ingest;
    private final IngestApiMapper apiMapper;
    private final Path uploadRoot;

    public IngestUploadController(IngestService ingest, IngestApiMapper apiMapper,
                                  @Value("${anchor.upload-dir:#{systemProperties['user.home']}/.anchor/uploads}")
                                  String uploadDir) {
        this.ingest = ingest;
        this.apiMapper = apiMapper;
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    @PostConstruct
    void ensureUploadDir() throws IOException {
        Files.createDirectories(uploadRoot);
        log.info("Ingest upload directory: {}", uploadRoot);
    }

    @PostMapping(value = "/ingest/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<IngestResponse> upload(@RequestParam("file") MultipartFile file) {
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

        IngestService.IngestResult result = ingest.ingest(saved.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(apiMapper.toResponse(result));
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
