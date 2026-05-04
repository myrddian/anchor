package io.aeyer.anchor.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.protocol.ask.AgentEnvelope;
import io.aeyer.anchor.protocol.ask.AskJobResponse;
import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.protocol.documents.DocumentDetailResponse;
import io.aeyer.anchor.protocol.documents.DocumentListResponse;
import io.aeyer.anchor.protocol.documents.DocumentSearchResponse;
import io.aeyer.anchor.protocol.retrieve.RetrieveRequest;
import io.aeyer.anchor.protocol.retrieve.RetrieveResponse;
import io.aeyer.anchor.protocol.validate.ValidateQuickRequest;
import io.aeyer.anchor.protocol.validate.ValidateQuickResponse;
import io.aeyer.anchor.protocol.validate.ValidateRequest;
import io.aeyer.anchor.protocol.validate.ValidateResponse;
import io.aeyer.anchor.server.api.DocumentController;
import io.aeyer.anchor.server.api.RetrieveController;
import io.aeyer.anchor.server.api.ValidateController;
import io.aeyer.anchor.server.jobs.AskJob;
import io.aeyer.anchor.server.jobs.JobStore;
import io.aeyer.anchor.server.service.AskService;
import io.aeyer.anchor.server.service.IngestException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/**
 * Anchor's tool catalogue exposed over MCP. Each tool wraps an existing REST
 * controller / service so the MCP surface stays in lockstep with the JSON
 * surface — same business logic, same auth, same data shapes. Adding a new
 * tool is one entry in {@link #register()}.
 *
 * Design notes:
 * - Tools return JSON-as-text in the MCP {@code content[]} block. Some clients
 *   also accept structuredContent; text-of-JSON works against every client and
 *   keeps the wire shape obvious in tcpdumps and screenshots.
 * - {@code anchor_ask} blocks for up to {@code wait_seconds} (default 120) so
 *   one tool call can return the deliberation result inline. On timeout it
 *   returns the {@code job_id} so the agent can poll via
 *   {@code anchor_get_ask_result}. Two cadences for two consumer styles.
 * - We call the existing {@link DocumentController} / {@link RetrieveController}
 *   / {@link ValidateController} directly — they're Spring beans and their
 *   public methods are the integration point. Avoids duplicating the
 *   alternative-chunk discovery + validation orchestration.
 */
@Component
public class McpToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(McpToolRegistry.class);

    private final ObjectMapper mapper;
    private final DocumentController documentController;
    private final RetrieveController retrieveController;
    private final ValidateController validateController;
    private final AskService askService;
    private final JobStore jobs;
    private final MeterRegistry meters;

    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final List<McpProtocol.ToolDefinition> definitions = new ArrayList<>();

    public McpToolRegistry(ObjectMapper mapper,
                           DocumentController documentController,
                           RetrieveController retrieveController,
                           ValidateController validateController,
                           AskService askService,
                           JobStore jobs,
                           MeterRegistry meters) {
        this.mapper = mapper;
        this.documentController = documentController;
        this.retrieveController = retrieveController;
        this.validateController = validateController;
        this.askService = askService;
        this.jobs = jobs;
        this.meters = meters;
        register();
    }

    public List<McpProtocol.ToolDefinition> tools() {
        return List.copyOf(definitions);
    }

    /** Dispatch by tool name; returns an isError result instead of throwing. */
    public McpProtocol.ToolCallResult call(String name, JsonNode arguments) {
        ToolHandler handler = handlers.get(name);
        if (handler == null) {
            meters.counter("anchor.mcp.tool.calls", "tool", "unknown", "outcome", "unknown_tool").increment();
            return McpProtocol.ToolCallResult.error("Unknown tool: " + name);
        }
        Timer.Sample sample = Timer.start(meters);
        String outcome = "ok";
        try {
            JsonNode args = arguments == null ? mapper.createObjectNode() : arguments;
            McpProtocol.ToolCallResult result = handler.call(args);
            if (Boolean.TRUE.equals(result.isError())) outcome = "tool_error";
            return result;
        } catch (IllegalArgumentException bad) {
            outcome = "invalid_argument";
            return McpProtocol.ToolCallResult.error("Invalid argument: " + bad.getMessage());
        } catch (IngestException notFound) {
            outcome = "not_found";
            return McpProtocol.ToolCallResult.error(notFound.getMessage());
        } catch (RuntimeException e) {
            outcome = "exception";
            log.warn("Tool '{}' failed", name, e);
            return McpProtocol.ToolCallResult.error("Tool failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            meters.counter("anchor.mcp.tool.calls", "tool", name, "outcome", outcome).increment();
            sample.stop(meters.timer("anchor.mcp.tool.duration", "tool", name, "outcome", outcome));
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        McpProtocol.ToolCallResult call(JsonNode args);
    }

    // ---- Tool catalogue ------------------------------------------------

    private void register() {
        add("anchor_list_documents",
                "List ingested documents (id, title, summary, ingestion timestamp, chapter/chunk counts). "
                        + "Use this to see what's available before calling search/retrieve/ask.",
                obj()
                        .property("limit", "integer", "Max documents to return (default 50)", false)
                        .property("offset", "integer", "Pagination offset (default 0)", false)
                        .property("q", "string", "Optional title-substring filter", false)
                        .build(),
                args -> {
                    int limit = optionalInt(args, "limit", 50);
                    int offset = optionalInt(args, "offset", 0);
                    String q = optionalText(args, "q");
                    DocumentListResponse list = documentController.list(limit, offset, q);
                    return textJson(list);
                });

        add("anchor_search_documents",
                "Semantic search across all documents — top-k by cosine of the query embedding against "
                        + "each document's stored summary embedding. Topical relevance, NOT stance — for "
                        + "stance use anchor_quick_validate or anchor_validate_chunk.",
                obj()
                        .property("query", "string", "Topic / claim to search for", true)
                        .property("k", "integer", "How many top hits to return (default 5)", false)
                        .build(),
                args -> {
                    String q = requireText(args, "query");
                    int k = optionalInt(args, "k", 5);
                    DocumentSearchResponse hits = documentController.search(q, k);
                    return textJson(hits);
                });

        add("anchor_describe_document",
                "Full structure of one document: chapters, sections, summaries at every level. No raw "
                        + "chunk text (use anchor_retrieve for that).",
                obj()
                        .property("document_id", "string", "UUID of the document", true)
                        .build(),
                args -> {
                    UUID id = requireUuid(args, "document_id");
                    ResponseEntity<DocumentDetailResponse> resp = documentController.detail(id);
                    if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                        return McpProtocol.ToolCallResult.error("Unknown document: " + id);
                    }
                    return textJson(resp.getBody());
                });

        add("anchor_retrieve",
                "Semantic retrieval within a document (or corpus-wide). Returns top-k chunks each wrapped "
                        + "with their full ancestor stack (paragraph / section / chapter / document summaries) "
                        + "— no follow-up read needed to understand context. Pass document_id to scope, omit "
                        + "for corpus-wide.",
                obj()
                        .property("document_id", "string",
                                "Optional UUID to restrict the search to one document", false)
                        .property("query", "string", "What to search for", true)
                        .property("k", "integer", "How many chunks (default 8)", false)
                        .build(),
                args -> {
                    UUID id = optionalUuid(args, "document_id");
                    String q = requireText(args, "query");
                    int k = optionalInt(args, "k", 8);
                    ResponseEntity<RetrieveResponse> resp = retrieveController.retrieve(
                            new RetrieveRequest(q, id, k));
                    return unpack(resp);
                });

        add("anchor_validate_chunk",
                "Source-grounded validation of a specific chunk against a query. Judges whether the chunk "
                        + "is load-bearing, classifies its argumentative_role (AUTHOR_POSITION / "
                        + "STEELMAN_REFUTED_LATER / CITED_EXTERNAL_VIEW / QUALIFIED_CLAIM / "
                        + "BACKGROUND_FACTUAL / UNCLEAR), and the document_stance_on_query (SUPPORTS / "
                        + "REJECTS / NEUTRAL / MIXED / OFF_TOPIC). When the chunk is steelman-refuted or "
                        + "the document rejects the query, alternative_chunks are returned pointing at the "
                        + "passages doing the actual refuting.",
                obj()
                        .property("chunk_id", "string", "UUID of the chunk to judge", true)
                        .property("query", "string", "The claim or question being checked against the chunk", true)
                        .build(),
                args -> {
                    UUID chunkId = requireUuid(args, "chunk_id");
                    String q = requireText(args, "query");
                    ResponseEntity<ValidateResponse> resp = validateController.validate(
                            new ValidateRequest(chunkId, q));
                    if (resp.getStatusCode().value() == 404) {
                        return McpProtocol.ToolCallResult.error("Unknown chunk: " + chunkId);
                    }
                    return unpack(resp);
                });

        add("anchor_quick_validate",
                "Vector-only stance approximation against a document — no LLM call, just two embedding "
                        + "round trips (query and 'not '+query). Returns topical_relevance and stance_score "
                        + "in [-1, 1]. Cheap pre-filter before anchor_ask; not a substitute for the full "
                        + "deliberation.",
                obj()
                        .property("document_id", "string", "UUID of the document", true)
                        .property("query", "string", "Claim to score", true)
                        .build(),
                args -> {
                    UUID id = requireUuid(args, "document_id");
                    String q = requireText(args, "query");
                    ResponseEntity<ValidateQuickResponse> resp = validateController.validateQuick(
                            new ValidateQuickRequest(id, q));
                    if (resp.getStatusCode().value() == 404) {
                        return McpProtocol.ToolCallResult.error(
                                "Unknown document or summary embedding not yet backfilled: " + id);
                    }
                    return unpack(resp);
                });

        add("anchor_ask",
                "Three-agent deliberation against one document: proposer (full hierarchy) drafts an answer, "
                        + "critic (macro-only — chapter + doc summaries) challenges it, synthesiser produces "
                        + "the final response. Blocks for up to wait_seconds; if the deliberation finishes in "
                        + "time, returns the full result inline. Otherwise returns the job_id so the agent "
                        + "can poll via anchor_get_ask_result.",
                obj()
                        .property("document_id", "string", "UUID of the document to query", true)
                        .property("query", "string", "Reader's question", true)
                        .property("wait_seconds", "integer",
                                "Max seconds to block waiting for completion (default 120, max 600)", false)
                        .build(),
                args -> {
                    UUID id = requireUuid(args, "document_id");
                    String q = requireText(args, "query");
                    int waitSeconds = Math.min(600, optionalInt(args, "wait_seconds", 120));
                    AskJob job = askService.startAsk(id, q);
                    AskJob done = waitForTerminal(job, Duration.ofSeconds(waitSeconds));
                    if (done.isTerminal()) {
                        return textJson(toAskResponse(done));
                    }
                    Map<String, Object> stillRunning = new LinkedHashMap<>();
                    stillRunning.put("job_id", done.jobId());
                    stillRunning.put("status", done.status().name());
                    stillRunning.put("still_running", true);
                    stillRunning.put("hint",
                            "Call anchor_get_ask_result with this job_id to retrieve the final response.");
                    return textJson(stillRunning);
                });

        add("anchor_get_ask_result",
                "Retrieve the current state (or final result) of a deliberation by job_id. Useful when an "
                        + "earlier anchor_ask returned still_running=true.",
                obj()
                        .property("job_id", "string", "UUID returned by an earlier anchor_ask", true)
                        .build(),
                args -> {
                    UUID jobId = requireUuid(args, "job_id");
                    AskJob job = jobs.get(jobId).orElse(null);
                    if (job == null) {
                        return McpProtocol.ToolCallResult.error("Unknown job_id: " + jobId);
                    }
                    return textJson(toAskResponse(job));
                });
    }

    // ---- Helpers ------------------------------------------------------

    private AskJob waitForTerminal(AskJob job, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            AskJob latest = jobs.get(job.jobId()).orElse(job);
            if (latest.isTerminal()) return latest;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return latest;
            }
        }
        return jobs.get(job.jobId()).orElse(job);
    }

    /** AskJob → wire envelope. Mirrors AskController.toResponse. */
    private static AskJobResponse toAskResponse(AskJob job) {
        return new AskJobResponse(
                job.jobId(),
                job.documentId(),
                job.query(),
                job.status(),
                job.startedAt(),
                job.completedAt(),
                job.proposer(),
                job.critic(),
                job.synthesiser(),
                job.status() == JobStatus.COMPLETED ? job.finalResponse() : null,
                job.error());
    }

    private void add(String name, String description, Map<String, Object> schema, ToolHandler handler) {
        definitions.add(new McpProtocol.ToolDefinition(name, description, schema));
        handlers.put(name, handler);
    }

    private static SchemaBuilder obj() { return new SchemaBuilder(); }

    /** Just enough JSON-Schema to describe a tool's input. No external dep. */
    private static final class SchemaBuilder {
        private final Map<String, Object> root = new LinkedHashMap<>();
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        SchemaBuilder() {
            root.put("type", "object");
            root.put("properties", properties);
        }

        SchemaBuilder property(String name, String type, String description, boolean isRequired) {
            properties.put(name, Map.of("type", type, "description", description));
            if (isRequired) required.add(name);
            return this;
        }

        Map<String, Object> build() {
            if (!required.isEmpty()) root.put("required", List.copyOf(required));
            return root;
        }
    }

    // ---- Argument helpers ---------------------------------------------

    private static String requireText(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("missing required string '" + key + "'");
        }
        return node.asText();
    }

    private static String optionalText(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) return null;
        return node.asText();
    }

    private static UUID requireUuid(JsonNode args, String key) {
        String raw = requireText(args, key);
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + key + "' must be a UUID, got: " + raw);
        }
    }

    private static UUID optionalUuid(JsonNode args, String key) {
        String raw = optionalText(args, key);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + key + "' must be a UUID, got: " + raw);
        }
    }

    private static int optionalInt(JsonNode args, String key, int defaultValue) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) return defaultValue;
        if (!node.canConvertToInt()) {
            throw new IllegalArgumentException("'" + key + "' must be an integer");
        }
        return node.asInt();
    }

    private McpProtocol.ToolCallResult textJson(Object value) {
        try {
            return McpProtocol.ToolCallResult.text(
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        } catch (Exception e) {
            return McpProtocol.ToolCallResult.error("Failed to serialise tool result: " + e.getMessage());
        }
    }

    private <T> McpProtocol.ToolCallResult unpack(ResponseEntity<T> response) {
        if (response.getStatusCode().is4xxClientError()) {
            return McpProtocol.ToolCallResult.error("Bad request (HTTP " + response.getStatusCode().value() + ")");
        }
        if (response.getStatusCode().is5xxServerError() || response.getBody() == null) {
            return McpProtocol.ToolCallResult.error("Service unavailable (HTTP "
                    + response.getStatusCode().value() + ")");
        }
        return textJson(response.getBody());
    }

    /** Unused but referenced if AgentEnvelope ever becomes the tool surface. */
    @SuppressWarnings("unused")
    private static String agentLabel(AgentEnvelope env) {
        return env == null ? "(none)" : env.agent();
    }
}
