package io.aeyer.anchor.server.service;

public class SummariserException extends RuntimeException {
    public SummariserException(String message) { super(message); }
    public SummariserException(String message, Throwable cause) { super(message, cause); }
}
