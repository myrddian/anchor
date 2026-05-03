package io.aeyer.anchor.server.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lmstudio")
public class LMStudioProperties {

    private String baseUrl;
    private String chatModel;
    private String embeddingModel;
    private int embeddingDim = 768;
    /**
     * Optional Bearer token for the OpenAI-compatible endpoint. Empty for a
     * vanilla local LM Studio install (it accepts unauthenticated calls); set
     * when pointing at real OpenAI, an Anthropic-compat proxy, vLLM with auth
     * enabled, etc. Sent on chat (blocking + streaming) and embedding requests
     * plus the /models health probe.
     */
    private String apiKey = "";
    private Timeouts timeouts = new Timeouts();
    private Retry retry = new Retry();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey == null ? "" : apiKey; }

    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public int getEmbeddingDim() { return embeddingDim; }
    public void setEmbeddingDim(int embeddingDim) { this.embeddingDim = embeddingDim; }

    public Timeouts getTimeouts() { return timeouts; }
    public void setTimeouts(Timeouts timeouts) { this.timeouts = timeouts; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public static class Timeouts {
        private int blockingSeconds = 60;
        private int streamingSeconds = 90;
        private int embeddingSeconds = 30;

        public int getBlockingSeconds() { return blockingSeconds; }
        public void setBlockingSeconds(int s) { this.blockingSeconds = s; }
        public int getStreamingSeconds() { return streamingSeconds; }
        public void setStreamingSeconds(int s) { this.streamingSeconds = s; }
        public int getEmbeddingSeconds() { return embeddingSeconds; }
        public void setEmbeddingSeconds(int s) { this.embeddingSeconds = s; }
    }

    public static class Retry {
        private int maxAttempts = 2;
        private long initialBackoffMs = 500;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int n) { this.maxAttempts = n; }
        public long getInitialBackoffMs() { return initialBackoffMs; }
        public void setInitialBackoffMs(long ms) { this.initialBackoffMs = ms; }
    }
}
