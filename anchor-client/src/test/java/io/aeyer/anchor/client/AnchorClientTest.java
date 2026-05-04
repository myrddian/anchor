package io.aeyer.anchor.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.aeyer.anchor.client.exceptions.AnchorClientException;
import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.protocol.documents.DocumentDetailResponse;
import io.aeyer.anchor.protocol.ingest.IngestResponse;
import io.aeyer.anchor.protocol.retrieve.RetrieveResponse;
import io.aeyer.anchor.protocol.validate.ValidateResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnchorClientTest {

    private MockWebServer server;
    private AnchorClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = AnchorClient.builder()
                .baseUrl(server.url("/").toString())
                .timeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.shutdown();
    }

    @Test
    void list_documents_pulls_first_page_and_returns_summaries() throws Exception {
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{"document_id":"%s","title":"Paper","source_path":"/x",
                                       "doc_summary":"s","ingested_at":"2026-05-04T00:00:00Z",
                                       "chapter_count":1,"section_count":2,"chunk_count":5}],
                         "total":1,"limit":200,"offset":0}
                        """.formatted(id)));

        var docs = client.listDocuments();

        assertEquals(1, docs.size());
        assertEquals(id, docs.get(0).documentId());
        RecordedRequest req = server.takeRequest();
        assertEquals("/documents?limit=200&offset=0", req.getPath());
    }

    @Test
    void use_by_id_does_not_make_a_network_call() {
        AnchorDocument doc = client.use(UUID.randomUUID());
        assertNotNull(doc);
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void use_by_substring_resolves_via_query_endpoint() throws Exception {
        UUID id = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{"document_id":"%s","title":"Phase paper","source_path":"/x",
                                       "doc_summary":"s","ingested_at":"2026-05-04T00:00:00Z",
                                       "chapter_count":1,"section_count":1,"chunk_count":1}],
                         "total":1,"limit":10,"offset":0}
                        """.formatted(id)));

        AnchorDocument doc = client.use("phase");

        assertEquals(id, doc.id());
        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().startsWith("/documents?limit=10&offset=0&q=phase"));
    }

    @Test
    void use_by_substring_throws_when_no_match() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"documents\":[],\"total\":0,\"limit\":10,\"offset\":0}"));

        assertThrows(AnchorClientException.class, () -> client.use("nothing"));
    }

    @Test
    void use_by_substring_throws_when_ambiguous() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"documents":[{"document_id":"%s","title":"A","source_path":"/a",
                                       "doc_summary":"s","ingested_at":"2026-05-04T00:00:00Z",
                                       "chapter_count":1,"section_count":1,"chunk_count":1},
                                      {"document_id":"%s","title":"B","source_path":"/b",
                                       "doc_summary":"s","ingested_at":"2026-05-04T00:00:00Z",
                                       "chapter_count":1,"section_count":1,"chunk_count":1}],
                         "total":2,"limit":10,"offset":0}
                        """.formatted(a, b)));

        assertThrows(AnchorClientException.class, () -> client.use("ambiguous"));
    }

    @Test
    void describe_calls_documents_detail() throws Exception {
        UUID docId = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"document_id":"%s","title":"X","source_path":"/x","doc_summary":"s",
                         "ingested_at":"2026-05-04T00:00:00Z","chapters":[]}
                        """.formatted(docId)));

        DocumentDetailResponse detail = client.use(docId).describe();

        assertEquals(docId, detail.documentId());
        assertEquals("/documents/" + docId, server.takeRequest().getPath());
    }

    @Test
    void retrieve_posts_to_retrieve_endpoint_with_document_scope() throws Exception {
        UUID docId = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"query":"q","document_id":"%s","k":3,"hits":[]}
                        """.formatted(docId)));

        RetrieveResponse response = client.use(docId).retrieve("q", 3);

        assertEquals("q", response.query());
        assertEquals("/retrieve", server.takeRequest().getPath());
    }

    @Test
    void validate_posts_to_validate_endpoint() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"chunk_id":"%s","document_id":"%s","query":"q",
                         "is_load_bearing":true,
                         "argumentative_role":"AUTHOR_POSITION",
                         "document_stance_on_query":"SUPPORTS",
                         "qualifying_context":"","reasoning":"r","alternative_chunks":[]}
                        """.formatted(chunkId, docId)));

        ValidateResponse response = client.use(docId).validate(chunkId, "q");

        assertEquals(chunkId, response.chunkId());
        assertEquals("/validate", server.takeRequest().getPath());
    }

    @Test
    void ask_returns_handle_and_await_polls_until_completed() throws Exception {
        UUID docId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        // POST /documents/{id}/ask
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"job_id":"%s","document_id":"%s","status":"QUEUED",
                         "stream_url":"/jobs/%s/stream","result_url":"/jobs/%s",
                         "estimated_duration_seconds":30}
                        """.formatted(jobId, docId, jobId, jobId)));
        // First poll: still PROPOSING
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"job_id":"%s","document_id":"%s","query":"q","status":"PROPOSING",
                         "started_at":"2026-05-04T00:00:00Z"}
                        """.formatted(jobId, docId)));
        // Second poll: COMPLETED
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"job_id":"%s","document_id":"%s","query":"q","status":"COMPLETED",
                         "started_at":"2026-05-04T00:00:00Z","completed_at":"2026-05-04T00:00:05Z",
                         "final_response":"final"}
                        """.formatted(jobId, docId)));

        AskHandle handle = client.use(docId).ask("q");
        var snap = handle.await(Duration.ofSeconds(5));

        assertEquals(JobStatus.COMPLETED, snap.status());
        assertEquals("final", snap.finalResponse());
    }

    @Test
    void ingest_posts_to_ingest_endpoint() throws Exception {
        UUID docId = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"document_id":"%s","title":"x","source_path":"/p",
                         "chapter_count":1,"section_count":1,"paragraph_count":1,"chunk_count":1,
                         "ingested_at":"2026-05-04T00:00:00Z",
                         "token_usage":{"summary_input_tokens":1,"summary_output_tokens":2,"embedding_inputs":3}}
                        """.formatted(docId)));

        IngestResponse response = client.ingest("/p");

        assertEquals(docId, response.documentId());
        RecordedRequest req = server.takeRequest();
        assertEquals("/ingest", req.getPath());
    }

    @Test
    void server_4xx_surfaces_as_client_exception() {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        assertThrows(AnchorClientException.class, () -> client.listDocuments());
    }

    @org.junit.jupiter.api.Test
    void ingest_upload_posts_multipart_form_with_file_field(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        UUID docId = UUID.randomUUID();
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"document_id":"%s","title":"Smith2024","source_path":"/uploads/x/Smith2024.pdf",
                         "chapter_count":1,"section_count":1,"paragraph_count":1,"chunk_count":1,
                         "ingested_at":"2026-05-04T00:00:00Z",
                         "token_usage":{"summary_input_tokens":1,"summary_output_tokens":2,"embedding_inputs":3}}
                        """.formatted(docId)));
        java.nio.file.Path local = tempDir.resolve("Smith2024.pdf");
        java.nio.file.Files.write(local, "%PDF-1.4 fake".getBytes());

        IngestResponse response = client.ingestUpload(local);

        assertEquals(docId, response.documentId());
        RecordedRequest req = server.takeRequest();
        assertEquals("/ingest/upload", req.getPath());
        assertTrue(req.getHeader("Content-Type").startsWith("multipart/form-data"),
                "expected multipart content-type, got " + req.getHeader("Content-Type"));
        String body = req.getBody().readUtf8();
        assertTrue(body.contains("name=\"file\""), "multipart body should have a file field");
        assertTrue(body.contains("filename=\"Smith2024.pdf\""),
                "filename should be preserved in the form-data part");
    }
}
