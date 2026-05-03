package io.aeyer.anchor.server.llm;

public record Embedding(float[] vector) {
    public int dim() { return vector.length; }
}
