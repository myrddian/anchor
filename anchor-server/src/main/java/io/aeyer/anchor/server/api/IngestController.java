package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.ingest.IngestRequest;
import io.aeyer.anchor.protocol.ingest.IngestResponse;
import io.aeyer.anchor.server.apimapper.IngestApiMapper;
import io.aeyer.anchor.server.service.IngestService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestController {

    private final IngestService ingest;
    private final IngestApiMapper apiMapper;

    public IngestController(IngestService ingest, IngestApiMapper apiMapper) {
        this.ingest = ingest;
        this.apiMapper = apiMapper;
    }

    @PostMapping("/ingest")
    public ResponseEntity<IngestResponse> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.sourcePath() == null || request.sourcePath().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        IngestService.IngestResult result = ingest.ingest(request.sourcePath());
        return ResponseEntity.status(HttpStatus.CREATED).body(apiMapper.toResponse(result));
    }
}
