package io.aeyer.anchor.server.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public class IngestProperties {

    private int chunkTargetTokens = 300;
    private int abstractMinWords = 100;

    public int getChunkTargetTokens() { return chunkTargetTokens; }
    public void setChunkTargetTokens(int n) { this.chunkTargetTokens = n; }

    public int getAbstractMinWords() { return abstractMinWords; }
    public void setAbstractMinWords(int n) { this.abstractMinWords = n; }
}
