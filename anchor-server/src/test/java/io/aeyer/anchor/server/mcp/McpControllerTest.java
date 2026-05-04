package io.aeyer.anchor.server.mcp;

import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.server.api.DocumentController;
import io.aeyer.anchor.server.api.RetrieveController;
import io.aeyer.anchor.server.api.ValidateController;
import io.aeyer.anchor.server.jobs.JobStore;
import io.aeyer.anchor.server.service.AskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone-MVC test of the MCP dispatcher. Verifies the JSON-RPC envelope
 * shape, the initialize handshake, the tools/list catalogue (every tool we
 * registered shows up), and that tools/call dispatches by name. Tool bodies
 * themselves are smoke-checked via the unknown-name error path — exercising
 * the real services would require a Spring context + LLM + pgvector, which
 * the existing integration tests already cover.
 */
class McpControllerTest {

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        // Tool handlers don't need to actually run — protocol-shape tests
        // never reach into them. Mocked services satisfy the registry's
        // constructor wiring.
        McpToolRegistry registry = new McpToolRegistry(
                mapper,
                mock(DocumentController.class),
                mock(RetrieveController.class),
                mock(ValidateController.class),
                mock(AskService.class),
                mock(JobStore.class));
        McpController controller = new McpController(mapper, registry, "anchor-server");
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void initialize_returns_protocol_version_capabilities_serverinfo() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                 "params":{"protocolVersion":"2024-11-05","capabilities":{}}}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value(McpProtocol.MCP_PROTOCOL_VERSION))
                .andExpect(jsonPath("$.result.serverInfo.name").value("anchor"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists());
    }

    @Test
    void tools_list_enumerates_every_registered_tool() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()", greaterThan(5)))
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_list_documents')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_search_documents')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_describe_document')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_retrieve')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_validate_chunk')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_quick_validate')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_ask')]").exists())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'anchor_get_ask_result')]").exists())
                // Each tool exposes a JSON Schema inputSchema with a 'type' key.
                .andExpect(jsonPath("$.result.tools[0].inputSchema.type").value("object"));
    }

    @Test
    void tools_call_unknown_name_returns_isError_block() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":3,"method":"tools/call",
                 "params":{"name":"does_not_exist","arguments":{}}}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(true))
                .andExpect(jsonPath("$.result.content[0].type").value("text"))
                .andExpect(jsonPath("$.result.content[0].text").value(
                        org.hamcrest.Matchers.containsString("Unknown tool")));
    }

    @Test
    void unknown_method_returns_jsonrpc_method_not_found() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":4,"method":"resources/list"}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ErrorCodes.METHOD_NOT_FOUND))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("resources/list")));
    }

    @Test
    void notifications_initialized_is_acknowledged() throws Exception {
        String body = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void ping_returns_empty_result() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":5,"method":"ping"}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").exists())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void tools_call_missing_name_returns_invalid_params() throws Exception {
        String body = """
                {"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"arguments":{}}}
                """;
        mvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(McpProtocol.ErrorCodes.INVALID_PARAMS));
    }
}
