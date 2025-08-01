package com.companya.realtime.integration.exception;

public class RetryableException extends RuntimeException {
    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}
