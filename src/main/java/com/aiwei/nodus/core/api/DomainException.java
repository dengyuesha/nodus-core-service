package com.aiwei.nodus.core.api;

import java.util.Map;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final boolean retryable;
    private final Map<String, Object> details;

    public DomainException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, false, Map.of());
    }

    public DomainException(
            HttpStatus status,
            String errorCode,
            String message,
            boolean retryable,
            Map<String, Object> details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }
}
