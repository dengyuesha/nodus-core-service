package com.aiwei.nodus.core.api;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.aiwei.nodus.core.identity.NodusRequestContext;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> handleDomain(DomainException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(
                exception.errorCode(), exception.getMessage(), requestId(request),
                exception.retryable(), exception.details(), Instant.now(clock)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                details.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(new ApiError(
                "VALIDATION_FAILED", "请求参数校验失败", requestId(request), false,
                details, Instant.now(clock)));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unexpected request failure, requestId={}", requestId(request), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ApiError(
                "INTERNAL_ERROR", "服务内部错误", requestId(request), true,
                Map.of(), Instant.now(clock)));
    }

    private String requestId(HttpServletRequest request) {
        Object context = request.getAttribute(NodusRequestContext.ATTRIBUTE_NAME);
        if (context instanceof NodusRequestContext requestContext) {
            return requestContext.requestId();
        }
        String header = request.getHeader("X-Request-Id");
        return header == null || header.isBlank() ? "unknown" : header;
    }
}
