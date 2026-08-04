package com.aiwei.nodus.core.api;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String errorCode,
        String message,
        String requestId,
        boolean retryable,
        Map<String, Object> details,
        Instant timestamp) {
}
