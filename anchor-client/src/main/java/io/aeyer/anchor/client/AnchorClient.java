package io.aeyer.anchor.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.aeyer.anchor.client.exceptions.AnchorClientException;
import io.aeyer.anchor.client.internal.HttpTransport;
import io.aeyer.anchor.protocol.documents.DocumentListResponse;
import io.aeyer.anchor.protocol.documents.DocumentSearchResponse;
import io.aeyer.anchor.protocol.documents.DocumentSummaryResponse;
import io.aeyer.anchor.protocol.ingest.IngestJobAcceptedResponse;
import io.aeyer.anchor.protocol.ingest.IngestRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Java SDK entry point. Build via {@code AnchorClient.builder().baseUrl(...)
 * .build()} then either bind to a document with {@link #use(UUID)} /
 * {@link #use(String)} or stay corpus-wide.
 *
 * The "select a doc, then query" UX (SPEC §15) is implemented client-side:
 * {@link #use} is pure handle construction with no network round-trip — call
 * {@link AnchorDocument#describe()} to verify the document exists.
 */
public final class AnchorClient {

    private final HttpTransport transport;

    private AnchorClient(HttpTransport transport) {
        this.transport = transport;
    }

    public static Builder builder() { return new Builder(); }

    /** List all ingested documents. Pulls every page until the server's total is exhausted. */
    public List<DocumentSummaryResponse> listDocuments() {
        DocumentListResponse first = transport.get("/documents?limit=200&offset=0", DocumentListResponse.class);
        return first == null ? List.of() : first.documents();
    }

    /** Bind to a document by id — pure client-side construction, no network. */
    public AnchorDocument use(UUID documentId) {
        return new AnchorDocument(documentId, transport);
    }

    /**
     * Bind to a document by title substring. Performs a single GET /documents?q=...
     * to resolve, then constructs the handle. Throws if zero or many matches.
     */
    public AnchorDocument use(String titleSubstring) {
        DocumentListResponse list = transport.get(
                "/documents?limit=10&offset=0&q=" + urlEncode(titleSubstring),
                DocumentListResponse.class);
        if (list == null || list.documents() == null || list.documents().isEmpty()) {
            throw new AnchorClientException("No document matched: " + titleSubstring);
        }
        if (list.documents().size() > 1) {
            throw new AnchorClientException(
                    "Ambiguous title substring \"" + titleSubstring + "\" matched " + list.documents().size()
                            + " documents — disambiguate or pass the UUID.");
        }
        return new AnchorDocument(list.documents().get(0).documentId(), transport);
    }

    /**
     * Server-side ingest: hand the server a path it can read from its own
     * filesystem. Returns immediately with an {@link IngestHandle} — the
     * pipeline (parse → summarise → embed → persist) takes minutes on a
     * full book and runs on the server's ingest pool. Poll progress via
     * {@link IngestHandle#snapshot()} or block via
     * {@link IngestHandle#awaitCompletion(java.time.Duration)}.
     */
    public IngestHandle ingest(String sourcePath) {
        IngestJobAcceptedResponse accepted = transport.postJson(
                "/ingest", new IngestRequest(sourcePath), IngestJobAcceptedResponse.class);
        return new IngestHandle(accepted.jobId(), transport);
    }

    /**
     * Upload a local document to the server via multipart, then ingest it.
     * PDF, EPUB, DOCX, RTF, HTML, plain text — the server dispatches to
     * PDFBox or Tika based on file extension. Returns an {@link IngestHandle}
     * for progress polling; same idempotency (re-upload → same content hash
     * → same stable document_id) as {@link #ingest(String)}.
     */
    public IngestHandle ingestUpload(java.nio.file.Path localFile) {
        IngestJobAcceptedResponse accepted = transport.postFile(
                "/ingest/upload", localFile, guessContentType(localFile),
                IngestJobAcceptedResponse.class);
        return new IngestHandle(accepted.jobId(), transport);
    }

    private static String guessContentType(java.nio.file.Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".pdf"))                            return "application/pdf";
        if (name.endsWith(".epub"))                           return "application/epub+zip";
        if (name.endsWith(".docx"))                           return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".rtf"))                            return "application/rtf";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html";
        if (name.endsWith(".txt") || name.endsWith(".md"))   return "text/plain";
        return "application/octet-stream";
    }

    /**
     * Semantic search across documents — ranks by cosine of the query
     * embedding against each doc's stored summary embedding. Topical
     * relevance, not stance — for stance use
     * {@link AnchorDocument#quickValidate(String)} on a single document.
     */
    public DocumentSearchResponse searchDocuments(String query, int k) {
        String path = "/documents/search?q=" + urlEncode(query) + "&k=" + Math.max(1, k);
        return transport.get(path, DocumentSearchResponse.class);
    }

    HttpTransport transport() { return transport; }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private String baseUrl = "http://localhost:8090";
        private Duration timeout = Duration.ofSeconds(60);
        private ObjectMapper mapper;
        private String apiToken;

        public Builder baseUrl(String baseUrl) { this.baseUrl = baseUrl; return this; }
        public Builder timeout(Duration timeout) { this.timeout = timeout; return this; }
        public Builder objectMapper(ObjectMapper mapper) { this.mapper = mapper; return this; }
        /** Bearer token sent as {@code Authorization: Bearer <token>} on every request. Null/blank disables auth. */
        public Builder apiToken(String apiToken) { this.apiToken = apiToken; return this; }

        public AnchorClient build() {
            ObjectMapper m = mapper != null ? mapper : defaultMapper();
            return new AnchorClient(new HttpTransport(baseUrl, timeout, m, apiToken));
        }

        private ObjectMapper defaultMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }
    }
}
