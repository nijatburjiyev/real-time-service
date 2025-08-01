package com.companya.realtime.integration.exception;

public class PermanentException extends RuntimeException {
    public PermanentException(String message, Throwable cause) {
        super(message, cause);
    }
}
