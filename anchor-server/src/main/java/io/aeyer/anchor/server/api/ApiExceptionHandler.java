package io.aeyer.anchor.server.api;

import io.aeyer.anchor.server.llm.LMStudioException;
import io.aeyer.anchor.server.service.IngestException;
import io.aeyer.anchor.server.service.SummariserException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps service-layer failures to HTTP status codes per SPEC §4.7.
 *
 * - PDF unparseable / structurally bad input → 422 Unprocessable Entity
 * - LM Studio unreachable → 503 Service Unavailable
 * - Summariser produced empty content twice → 502 Bad Gateway (model misbehaved)
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(LMStudioException.class)
    public ResponseEntity<Map<String, String>> lmStudioDown(LMStudioException e) {
        log.warn("LM Studio unreachable", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "lm_studio_unavailable", "detail", safeMessage(e)));
    }

    @ExceptionHandler(SummariserException.class)
    public ResponseEntity<Map<String, String>> summariserMisbehaved(SummariserException e) {
        log.warn("Summariser model misbehaved", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "summariser_failure", "detail", safeMessage(e)));
    }

    @ExceptionHandler(IngestException.class)
    public ResponseEntity<Map<String, String>> ingestFailed(IngestException e) {
        log.warn("Ingest input rejected", e);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", "ingest_failed", "detail", safeMessage(e)));
    }

    private String safeMessage(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
