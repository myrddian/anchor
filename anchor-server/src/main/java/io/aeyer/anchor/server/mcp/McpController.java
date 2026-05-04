package io.aeyer.anchor.server.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.server.mcp.McpProtocol.ErrorCodes;
import io.aeyer.anchor.server.mcp.McpProtocol.InitializeResult;
import io.aeyer.anchor.server.mcp.McpProtocol.JsonRpcRequest;
import io.aeyer.anchor.server.mcp.McpProtocol.JsonRpcResponse;
import io.aeyer.anchor.server.mcp.McpProtocol.ToolCallResult;
import io.aeyer.anchor.server.mcp.McpProtocol.ToolsListResult;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP (Model Context Protocol) endpoint over the Streamable HTTP transport.
 * Lets a local Claude Code / Claude Desktop / any MCP-aware client connect to
 * Anchor and call its tools — same surface as the REST API, exposed under
 * the JSON-RPC vocabulary MCP clients speak.
 *
 * v0 implements the request/response slice of the transport: POST /mcp with a
 * JSON-RPC body, response is a single JSON-RPC envelope. SSE streaming and
 * server-initiated notifications are spec'd but not used by Anchor's tools —
 * deliberation streaming already has its own /jobs/{id}/stream endpoint and
 * the {@code anchor_ask} tool blocks-then-returns.
 *
 * Auth: this endpoint is NOT in the filter exempt list, so when
 * ANCHOR_API_TOKEN is set the MCP client must include the Bearer header.
 * Claude Code's MCP HTTP transport supports {@code "headers"} in its config
 * for exactly this.
 *
 * Methods implemented:
 * - {@code initialize}: handshake — returns protocol version, server info,
 *   and the tools capability flag.
 * - {@code tools/list}: enumerates the registered tools.
 * - {@code tools/call}: dispatches by tool name.
 * - {@code ping}: trivial liveness probe used by some clients.
 *
 * Other MCP methods ({@code resources/*}, {@code prompts/*},
 * {@code logging/*}) return method-not-found; we don't claim those
 * capabilities in the initialize response, so well-behaved clients won't
 * call them.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final ObjectMapper mapper;
    private final McpToolRegistry tools;
    private final String serverVersion;

    public McpController(ObjectMapper mapper,
                         McpToolRegistry tools,
                         @Value("${spring.application.name:anchor-server}") String appName) {
        this.mapper = mapper;
        this.tools = tools;
        this.serverVersion = appName;
    }

    @PostMapping(value = "/mcp",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonRpcResponse> handle(@RequestBody JsonNode body) {
        JsonRpcRequest req;
        try {
            req = mapper.treeToValue(body, JsonRpcRequest.class);
        } catch (Exception e) {
            return ResponseEntity.ok(
                    JsonRpcResponse.error(null, ErrorCodes.PARSE_ERROR, "Invalid JSON-RPC envelope"));
        }
        if (req.method() == null) {
            return ResponseEntity.ok(
                    JsonRpcResponse.error(req.id(), ErrorCodes.INVALID_REQUEST, "Missing 'method'"));
        }

        try {
            return ResponseEntity.ok(dispatch(req));
        } catch (RuntimeException e) {
            log.warn("MCP method '{}' threw", req.method(), e);
            return ResponseEntity.ok(
                    JsonRpcResponse.error(req.id(), ErrorCodes.INTERNAL_ERROR,
                            "Internal error in MCP handler",
                            Map.of("exception", e.getClass().getSimpleName(),
                                    "message", String.valueOf(e.getMessage()))));
        }
    }

    private JsonRpcResponse dispatch(JsonRpcRequest req) {
        return switch (req.method()) {
            case "initialize" -> JsonRpcResponse.ok(req.id(), initializeResult());
            case "ping" -> JsonRpcResponse.ok(req.id(), Map.of());
            case "tools/list" -> JsonRpcResponse.ok(req.id(), new ToolsListResult(tools.tools()));
            case "tools/call" -> handleToolCall(req);
            // Notifications fired by the client during normal handshake; we
            // accept and ignore. Returning an empty result keeps the client
            // happy without us claiming any extra capabilities.
            case "notifications/initialized" -> JsonRpcResponse.ok(req.id(), Map.of());
            default -> JsonRpcResponse.error(req.id(),
                    ErrorCodes.METHOD_NOT_FOUND, "Method not implemented: " + req.method());
        };
    }

    private JsonRpcResponse handleToolCall(JsonRpcRequest req) {
        JsonNode params = req.params();
        if (params == null || params.path("name").isMissingNode()) {
            return JsonRpcResponse.error(req.id(),
                    ErrorCodes.INVALID_PARAMS, "tools/call requires 'name'");
        }
        String name = params.get("name").asText();
        JsonNode arguments = params.get("arguments");
        ToolCallResult result = tools.call(name, arguments);
        return JsonRpcResponse.ok(req.id(), result);
    }

    private InitializeResult initializeResult() {
        // Only declare capabilities we actually implement. Empty object under
        // tools (rather than a richer { listChanged: true } block) means
        // "tools work, but the catalogue won't be re-broadcast" — accurate.
        return new InitializeResult(
                McpProtocol.MCP_PROTOCOL_VERSION,
                new InitializeResult.Capabilities(Map.of()),
                new InitializeResult.ServerInfo("anchor", serverVersion));
    }
}
