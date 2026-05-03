package io.aeyer.anchor.server.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Hand-rolled OpenAI-compatible client for LM Studio (SPEC §7.2).
 *
 * Three OkHttp clients with separate read timeouts so blocking chat (60s),
 * streaming chat (90s), and embeddings (30s) can be tuned independently.
 * Connection-failure retry on blocking calls only — streaming has one shot
 * because mid-stream retry would replay partial output.
 *
 * Concurrency control lives in {@code WorkerPools.chatPool()} /
 * {@code embeddingPool()}; this class is intentionally pool-agnostic so callers
 * choose the right submission lane explicitly.
 */
@Service
public class LMStudioClient {

    private static final Logger log = LoggerFactory.getLogger(LMStudioClient.class);
    private static final MediaType JSON = MediaType.get("application/json");

    private final LMStudioProperties props;
    private final ObjectMapper mapper;
    private final OkHttpClient blockingHttp;
    private final OkHttpClient streamingHttp;
    private final OkHttpClient embeddingHttp;
    private final AtomicBoolean embeddingDimVerified = new AtomicBoolean(false);

    @org.springframework.beans.factory.annotation.Autowired
    public LMStudioClient(LMStudioProperties props, ObjectMapper mapper) {
        this(props, mapper, UnaryOperator.identity());
    }

    /**
     * Test-only constructor: pass an OkHttpClient.Builder customizer (e.g. to add
     * a failure-injection interceptor). Production code uses the two-arg constructor.
     */
    LMStudioClient(LMStudioProperties props, ObjectMapper mapper,
                   UnaryOperator<OkHttpClient.Builder> customizer) {
        this.props = props;
        this.mapper = mapper;
        this.blockingHttp = httpClient(props.getTimeouts().getBlockingSeconds(), customizer);
        this.streamingHttp = httpClient(props.getTimeouts().getStreamingSeconds(), customizer);
        this.embeddingHttp = httpClient(props.getTimeouts().getEmbeddingSeconds(), customizer);
    }

    private OkHttpClient httpClient(int readSeconds, UnaryOperator<OkHttpClient.Builder> customizer) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(readSeconds))
                .retryOnConnectionFailure(false); // we handle retry ourselves with backoff
        return customizer.apply(builder).build();
    }

    // ---- Chat (blocking) ----

    public ChatCompletion complete(String systemPrompt, String userPrompt, double temperature) {
        Map<String, Object> body = chatRequestBody(systemPrompt, userPrompt, temperature, false);
        Request request = new Request.Builder()
                .url(props.getBaseUrl() + "/chat/completions")
                .post(RequestBody.create(toJson(body), JSON))
                .build();

        String responseBody = executeWithRetry(blockingHttp, request, "chat");
        try {
            return parseChatCompletion(mapper.readTree(responseBody));
        } catch (IOException e) {
            throw new LMStudioException("Failed to parse chat response JSON", e);
        }
    }

    // ---- Chat (streaming via SSE) ----

    public CompletableFuture<ChatCompletion> completeStreaming(
            String systemPrompt,
            String userPrompt,
            double temperature,
            Consumer<String> tokenHandler) {
        Map<String, Object> body = chatRequestBody(systemPrompt, userPrompt, temperature, true);
        Request request = new Request.Builder()
                .url(props.getBaseUrl() + "/chat/completions")
                .post(RequestBody.create(toJson(body), JSON))
                .header("Accept", "text/event-stream")
                .build();

        CompletableFuture<ChatCompletion> future = new CompletableFuture<>();
        StringBuilder accumulator = new StringBuilder();
        AtomicReference<String> finishReason = new AtomicReference<>();

        EventSource.Factory factory = EventSources.createFactory(streamingHttp);
        EventSource source = factory.newEventSource(request, new EventSourceListener() {
            @Override
            public void onEvent(EventSource src, String id, String type, String data) {
                if ("[DONE]".equals(data)) {
                    src.cancel();
                    future.complete(new ChatCompletion(accumulator.toString(), finishReason.get(), null));
                    return;
                }
                try {
                    JsonNode chunk = mapper.readTree(data);
                    JsonNode choice = chunk.path("choices").path(0);
                    String delta = choice.path("delta").path("content").asText("");
                    if (!delta.isEmpty()) {
                        accumulator.append(delta);
                        tokenHandler.accept(delta);
                    }
                    String fr = choice.path("finish_reason").asText(null);
                    if (fr != null && !"null".equals(fr)) finishReason.set(fr);
                } catch (IOException e) {
                    src.cancel();
                    future.completeExceptionally(new LMStudioException("Failed to parse SSE chunk", e));
                }
            }

            @Override
            public void onClosed(EventSource src) {
                if (!future.isDone()) {
                    future.complete(new ChatCompletion(accumulator.toString(), finishReason.get(), null));
                }
            }

            @Override
            public void onFailure(EventSource src, Throwable t, Response response) {
                String detail = response == null
                        ? (t == null ? "unknown failure" : t.getMessage())
                        : "HTTP " + response.code();
                if (response != null) response.close();
                future.completeExceptionally(new LMStudioException("Streaming chat failed: " + detail, t));
            }
        });
        future.whenComplete((r, e) -> source.cancel());
        return future;
    }

    // ---- Embeddings ----

    public Embedding embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    public List<Embedding> embedBatch(List<String> texts) {
        if (texts.isEmpty()) return List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getEmbeddingModel());
        body.put("input", texts);

        Request request = new Request.Builder()
                .url(props.getBaseUrl() + "/embeddings")
                .post(RequestBody.create(toJson(body), JSON))
                .build();

        String responseBody = executeWithRetry(embeddingHttp, request, "embedding");
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                throw new LMStudioException("Embedding response had no data array");
            }
            List<Embedding> result = new ArrayList<>(data.size());
            for (JsonNode item : data) {
                JsonNode vec = item.path("embedding");
                if (!vec.isArray() || vec.isEmpty()) {
                    throw new LMStudioException("Embedding response had empty vector");
                }
                float[] values = new float[vec.size()];
                for (int i = 0; i < vec.size(); i++) values[i] = (float) vec.get(i).asDouble();
                result.add(new Embedding(values));
            }
            verifyEmbeddingDim(result.get(0).dim());
            return result;
        } catch (IOException e) {
            throw new LMStudioException("Failed to parse embedding response JSON", e);
        }
    }

    private void verifyEmbeddingDim(int observedDim) {
        if (embeddingDimVerified.get()) return;
        if (observedDim != props.getEmbeddingDim()) {
            throw new LMStudioException(
                    "Embedding dimension mismatch: expected " + props.getEmbeddingDim()
                            + " (per lmstudio.embedding-dim), got " + observedDim
                            + ". Check the model — Anchor's pgvector schema is fixed at " + props.getEmbeddingDim() + ".");
        }
        embeddingDimVerified.set(true);
        log.info("Embedding dimension verified: {} matches configured dim", observedDim);
    }

    // ---- HTTP retry wrapper ----

    /**
     * Execute the request and read the body as a string, retrying connection-level
     * failures (IOException raised either from execute() or from body read). HTTP
     * status errors (4xx/5xx) bubble up immediately as LMStudioException — no retry,
     * the model isn't going to suddenly start liking the request.
     */
    private String executeWithRetry(OkHttpClient http, Request request, String label) {
        int maxAttempts = Math.max(1, props.getRetry().getMaxAttempts());
        long backoff = props.getRetry().getInitialBackoffMs();
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try (Response response = http.newCall(request).execute()) {
                String responseBody = readBody(response);
                if (!response.isSuccessful()) {
                    throw new LMStudioException(
                            label + " request failed: HTTP " + response.code() + " " + responseBody);
                }
                return responseBody;
            } catch (IOException e) {
                lastFailure = e;
                log.warn("LM Studio {} request attempt {}/{} failed: {}",
                        label, attempt, maxAttempts, e.getMessage());
                if (attempt < maxAttempts) {
                    // Drop any half-broken connections so the retry establishes a fresh socket
                    // rather than reusing a poisoned pooled connection.
                    http.connectionPool().evictAll();
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new LMStudioException("Interrupted during retry backoff", ie);
                    }
                    backoff *= 2;
                }
            }
        }
        throw new LMStudioException(
                "LM Studio " + label + " request failed after " + maxAttempts
                        + " attempts: " + lastFailure.getMessage(),
                lastFailure);
    }

    // ---- Request body / parsing ----

    private Map<String, Object> chatRequestBody(String system, String user, double temperature, boolean stream) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", user == null ? "" : user));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getChatModel());
        body.put("messages", messages);
        body.put("temperature", temperature);
        if (stream) body.put("stream", true);
        return body;
    }

    private ChatCompletion parseChatCompletion(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LMStudioException("Chat response had no choices");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("");
        String finish = choice.path("finish_reason").asText(null);

        TokenUsage usage = null;
        JsonNode u = root.path("usage");
        if (!u.isMissingNode() && !u.isNull()) {
            usage = new TokenUsage(
                    u.path("prompt_tokens").isInt() ? u.path("prompt_tokens").asInt() : null,
                    u.path("completion_tokens").isInt() ? u.path("completion_tokens").asInt() : null,
                    u.path("total_tokens").isInt() ? u.path("total_tokens").asInt() : null);
        }
        return new ChatCompletion(content, finish, usage);
    }

    private byte[] toJson(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new LMStudioException("Failed to encode request body", e);
        }
    }

    private String readBody(Response response) throws IOException {
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    @PreDestroy
    void shutdown() {
        for (OkHttpClient client : List.of(blockingHttp, streamingHttp, embeddingHttp)) {
            client.dispatcher().executorService().shutdown();
            client.connectionPool().evictAll();
        }
    }
}
