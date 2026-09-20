package dev.fandroid.protocol.crypto;

public final class EnvelopeException extends RuntimeException {
    public EnvelopeException(String message) {
        super(message);
    }

    public EnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}

