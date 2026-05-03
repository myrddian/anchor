package io.aeyer.anchor.server.service;

/**
 * Failure during ingest. The HTTP layer maps this to 422 (PDF unparseable /
 * malformed input) by default; specific subclasses signal upstream-LLM
 * failures (503) so the controller advice can pick the right status.
 */
public class IngestException extends RuntimeException {
    public IngestException(String message) { super(message); }
    public IngestException(String message, Throwable cause) { super(message, cause); }
}
