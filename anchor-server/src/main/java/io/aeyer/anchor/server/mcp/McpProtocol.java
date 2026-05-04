package io.aeyer.anchor.server.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Wire records for the Model Context Protocol over JSON-RPC 2.0. The MCP spec
 * is a small layer on top of JSON-RPC: an {@code initialize} handshake, then
 * {@code tools/list} + {@code tools/call} (and {@code resources/*} +
 * {@code prompts/*} which Anchor doesn't implement yet).
 *
 * Hand-rolled rather than pulled from a Spring AI / MCP SDK because the
 * footprint is small and the surface is stable enough that a 100-line
 * protocol module beats a multi-megabyte starter dependency.
 */
public final class McpProtocol {

    private McpProtocol() {}

    public static final String JSON_RPC_VERSION = "2.0";
    public static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    // ---- JSON-RPC envelopes -------------------------------------------

    /**
     * Inbound JSON-RPC. {@code id} is null for notifications (no response
     * expected). {@code params} is left as a JsonNode so each method handler
     * can deserialise into its own typed shape.
     */
    @JsonInclude(Include.NON_NULL)
    public record JsonRpcRequest(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") JsonNode id,
            @JsonProperty("method") String method,
            @JsonProperty("params") JsonNode params) {}

    @JsonInclude(Include.NON_NULL)
    public record JsonRpcResponse(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") JsonNode id,
            @JsonProperty("result") Object result,
            @JsonProperty("error") JsonRpcError error) {

        public static JsonRpcResponse ok(JsonNode id, Object result) {
            return new JsonRpcResponse(JSON_RPC_VERSION, id, result, null);
        }

        public static JsonRpcResponse error(JsonNode id, int code, String message) {
            return new JsonRpcResponse(JSON_RPC_VERSION, id,
                    null, new JsonRpcError(code, message, null));
        }

        public static JsonRpcResponse error(JsonNode id, int code, String message, Object data) {
            return new JsonRpcResponse(JSON_RPC_VERSION, id,
                    null, new JsonRpcError(code, message, data));
        }
    }

    @JsonInclude(Include.NON_NULL)
    public record JsonRpcError(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") Object data) {}

    /** Standard JSON-RPC error codes used by MCP. */
    public static final class ErrorCodes {
        private ErrorCodes() {}
        public static final int PARSE_ERROR = -32700;
        public static final int INVALID_REQUEST = -32600;
        public static final int METHOD_NOT_FOUND = -32601;
        public static final int INVALID_PARAMS = -32602;
        public static final int INTERNAL_ERROR = -32603;
    }

    // ---- MCP method results -------------------------------------------

    @JsonInclude(Include.NON_NULL)
    public record InitializeResult(
            @JsonProperty("protocolVersion") String protocolVersion,
            @JsonProperty("capabilities") Capabilities capabilities,
            @JsonProperty("serverInfo") ServerInfo serverInfo) {

        public record Capabilities(@JsonProperty("tools") Map<String, Object> tools) {}
        public record ServerInfo(
                @JsonProperty("name") String name,
                @JsonProperty("version") String version) {}
    }

    @JsonInclude(Include.NON_NULL)
    public record ToolsListResult(@JsonProperty("tools") List<ToolDefinition> tools) {}

    /**
     * MCP tool definition. {@code inputSchema} must be a JSON Schema object —
     * we hand-build them as {@code Map<String,Object>} so they serialise to
     * the right shape without a schema-generation library.
     */
    @JsonInclude(Include.NON_NULL)
    public record ToolDefinition(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("inputSchema") Map<String, Object> inputSchema) {}

    @JsonInclude(Include.NON_NULL)
    public record ToolCallResult(
            @JsonProperty("content") List<ContentBlock> content,
            @JsonProperty("isError") Boolean isError) {

        public static ToolCallResult text(String body) {
            return new ToolCallResult(List.of(new ContentBlock("text", body)), null);
        }

        public static ToolCallResult error(String message) {
            return new ToolCallResult(List.of(new ContentBlock("text", message)), Boolean.TRUE);
        }
    }

    public record ContentBlock(
            @JsonProperty("type") String type,
            @JsonProperty("text") String text) {}
}
