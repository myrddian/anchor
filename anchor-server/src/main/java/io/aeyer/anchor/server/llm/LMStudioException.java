package io.aeyer.anchor.server.llm;

public class LMStudioException extends RuntimeException {
    public LMStudioException(String message) { super(message); }
    public LMStudioException(String message, Throwable cause) { super(message, cause); }
}
