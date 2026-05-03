package io.aeyer.anchor.server.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "lmstudio")
public class LMStudioProperties {

    private String baseUrl;
    private String chatModel;
    private String embeddingModel;
    private int embeddingDim = 768;
    private Timeouts timeouts = new Timeouts();
    private Retry retry = new Retry();

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

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
