package io.aeyer.anchor.server.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LMStudioClientTest {

    private MockWebServer server;
    private LMStudioClient client;
    private LMStudioProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        props = new LMStudioProperties();
        props.setBaseUrl(server.url("/v1").toString().replaceAll("/$", ""));
        props.setChatModel("gemma-3-4b-it");
        props.setEmbeddingModel("nomic-embed-text-v1.5");
        props.setEmbeddingDim(4); // small for tests
        props.getTimeouts().setBlockingSeconds(5);
        props.getTimeouts().setStreamingSeconds(5);
        props.getTimeouts().setEmbeddingSeconds(5);
        props.getRetry().setMaxAttempts(2);
        props.getRetry().setInitialBackoffMs(20);

        client = new LMStudioClient(props, mapper);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) server.shutdown();
    }

    @Test
    void api_key_when_set_is_sent_as_bearer_on_chat_streaming_and_embedding() throws Exception {
        props.setApiKey("sk-test-token-1234");
        client = new LMStudioClient(props, mapper);

        // Three calls back-to-back: blocking chat, streaming chat, embedding.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"y\"}}]}\n\ndata: [DONE]\n\n"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"embedding\":[0.1,0.2,0.3,0.4]}]}"));

        client.complete("s", "u", 0.0);
        client.completeStreaming("s", "u", 0.0, t -> {}).get(5, TimeUnit.SECONDS);
        client.embed("hi");

        for (int i = 0; i < 3; i++) {
            RecordedRequest request = server.takeRequest();
            assertThat(request.getHeader("Authorization"))
                    .as("call %d should send Bearer auth", i)
                    .isEqualTo("Bearer sk-test-token-1234");
        }
    }

    @Test
    void api_key_when_blank_means_no_authorization_header() throws Exception {
        // Default props leaves apiKey blank — covers the local-LM-Studio case.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"x\"},\"finish_reason\":\"stop\"}]}"));

        client.complete("s", "u", 0.0);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getHeader("Authorization")).isNull();
    }

    @Test
    void complete_parses_chat_completion() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"x","choices":[{"message":{"role":"assistant","content":"hello world"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":7,"completion_tokens":3,"total_tokens":10}}
                        """));

        ChatCompletion completion = client.complete("you are anchor", "say hi", 0.2);

        assertThat(completion.content()).isEqualTo("hello world");
        assertThat(completion.finishReason()).isEqualTo("stop");
        assertThat(completion.usage()).isNotNull();
        assertThat(completion.usage().totalTokens()).isEqualTo(10);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("model").asText()).isEqualTo("gemma-3-4b-it");
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(body.get("messages").get(1).get("role").asText()).isEqualTo("user");
        assertThat(body.has("stream")).isFalse();
    }

    @Test
    void complete_does_not_retry_on_4xx() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad request"));

        assertThatThrownBy(() -> client.complete("s", "u", 0.0))
                .isInstanceOf(LMStudioException.class)
                .hasMessageContaining("HTTP 400");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void complete_retries_on_connection_failure_then_succeeds() throws Exception {
        // Inject an interceptor that fails the first call with IOException, then
        // succeeds. MockWebServer's socket-policy disconnects close the listener,
        // so an interceptor is the only clean way to simulate single-call failure.
        AtomicInteger calls = new AtomicInteger();
        Interceptor failOnce = chain -> {
            if (calls.getAndIncrement() == 0) {
                throw new java.io.IOException("simulated transient connection drop");
            }
            return chain.proceed(chain.request());
        };
        client = new LMStudioClient(props, mapper, b -> b.addInterceptor(failOnce));

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                        """));

        ChatCompletion completion = client.complete("s", "u", 0.0);

        assertThat(completion.content()).isEqualTo("ok");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    void complete_gives_up_after_max_attempts() {
        AtomicInteger calls = new AtomicInteger();
        Interceptor alwaysFail = chain -> {
            calls.incrementAndGet();
            throw new java.io.IOException("simulated network down");
        };
        client = new LMStudioClient(props, mapper, b -> b.addInterceptor(alwaysFail));

        assertThatThrownBy(() -> client.complete("s", "u", 0.0))
                .isInstanceOf(LMStudioException.class)
                .hasMessageContaining("after 2 attempts")
                .hasMessageContaining("simulated network down");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void completeStreaming_forwards_tokens_and_resolves_on_done() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"content":"hel"}}]}

                data: {"choices":[{"delta":{"content":"lo"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse));

        StringBuilder seen = new StringBuilder();
        ChatCompletion result = client.completeStreaming("sys", "user", 0.1, seen::append)
                .get(5, TimeUnit.SECONDS);

        assertThat(seen.toString()).isEqualTo("hello");
        assertThat(result.content()).isEqualTo("hello");
        assertThat(result.finishReason()).isEqualTo("stop");

        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("stream").asBoolean()).isTrue();
    }

    @Test
    void embed_parses_vector_and_passes_dim_check() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data":[{"embedding":[0.1,0.2,0.3,0.4]}]}
                        """));

        Embedding embedding = client.embed("the cat sat");

        assertThat(embedding.dim()).isEqualTo(4);
        assertThat(embedding.vector()).containsExactly(0.1f, 0.2f, 0.3f, 0.4f);
    }

    @Test
    void embedBatch_parses_multiple_vectors() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data":[
                          {"embedding":[1.0,0.0,0.0,0.0]},
                          {"embedding":[0.0,1.0,0.0,0.0]}
                        ]}
                        """));

        List<Embedding> embeddings = client.embedBatch(List.of("a", "b"));

        assertThat(embeddings).hasSize(2);
        assertThat(embeddings.get(0).vector()[0]).isEqualTo(1.0f);
        assertThat(embeddings.get(1).vector()[1]).isEqualTo(1.0f);

        RecordedRequest request = server.takeRequest();
        JsonNode body = mapper.readTree(request.getBody().readUtf8());
        assertThat(body.get("input").size()).isEqualTo(2);
    }

    @Test
    void embed_fails_loudly_on_dimension_mismatch() {
        // configured dim is 4, server returns 3 — schema would be wrong, fail immediately.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data":[{"embedding":[0.1,0.2,0.3]}]}
                        """));

        assertThatThrownBy(() -> client.embed("hi"))
                .isInstanceOf(LMStudioException.class)
                .hasMessageContaining("dimension mismatch")
                .hasMessageContaining("expected 4")
                .hasMessageContaining("got 3");
    }

    @Test
    void embed_dim_check_runs_only_once() {
        // First call: correct dim. Second: mismatched dim — should NOT throw because the
        // verification only runs once. We trust the model after the first sanity check.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data":[{"embedding":[0.1,0.2,0.3,0.4]}]}
                        """));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"data":[{"embedding":[0.5,0.6,0.7]}]}
                        """));

        client.embed("first");
        Embedding second = client.embed("second");
        assertThat(second.dim()).isEqualTo(3);
    }

    @Test
    void streaming_failure_completes_future_exceptionally() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("kaboom"));
        AtomicInteger tokens = new AtomicInteger();

        assertThatThrownBy(() -> client.completeStreaming("s", "u", 0.0, t -> tokens.incrementAndGet())
                .get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(LMStudioException.class);
        assertThat(tokens.get()).isZero();
    }
}
