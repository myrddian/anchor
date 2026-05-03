package io.aeyer.anchor.server.workers;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "worker")
public class WorkerPoolProperties {

    private PoolConfig chat = new PoolConfig(1);
    private PoolConfig embedding = new PoolConfig(2);
    private PoolConfig deliberation = new PoolConfig(4);
    private PoolConfig ingest = new PoolConfig(1);
    private int shutdownTimeoutSeconds = 30;

    public PoolConfig getChat() { return chat; }
    public void setChat(PoolConfig chat) { this.chat = chat; }

    public PoolConfig getEmbedding() { return embedding; }
    public void setEmbedding(PoolConfig embedding) { this.embedding = embedding; }

    public PoolConfig getDeliberation() { return deliberation; }
    public void setDeliberation(PoolConfig deliberation) { this.deliberation = deliberation; }

    public PoolConfig getIngest() { return ingest; }
    public void setIngest(PoolConfig ingest) { this.ingest = ingest; }

    public int getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }

    public static class PoolConfig {
        private int poolSize;

        public PoolConfig() {}
        public PoolConfig(int poolSize) { this.poolSize = poolSize; }

        public int getPoolSize() { return poolSize; }
        public void setPoolSize(int poolSize) { this.poolSize = poolSize; }
    }
}
