package io.aeyer.anchor.server.llm;

public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {}
