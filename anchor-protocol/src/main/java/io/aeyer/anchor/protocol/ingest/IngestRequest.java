package io.aeyer.anchor.protocol.ingest;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /ingest body — server-side path to a PDF to ingest. Multipart upload is
 * a v1 concern; v0 assumes the caller can place files where the server reads.
 */
public record IngestRequest(@JsonProperty("source_path") String sourcePath) {}
