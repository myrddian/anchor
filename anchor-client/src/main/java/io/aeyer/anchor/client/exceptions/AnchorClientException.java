package io.aeyer.anchor.client.exceptions;

public class AnchorClientException extends RuntimeException {
    public AnchorClientException(String message) { super(message); }
    public AnchorClientException(String message, Throwable cause) { super(message, cause); }
}
