package io.aeyer.anchor.server.llm;

public record ChatCompletion(String content, String finishReason, TokenUsage usage) {}
