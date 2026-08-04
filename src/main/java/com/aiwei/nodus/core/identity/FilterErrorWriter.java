package com.aiwei.nodus.core.identity;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

import com.aiwei.nodus.core.api.ApiError;

public final class FilterErrorWriter {

    private FilterErrorWriter() {
    }

    public static void write(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String errorCode,
            String message,
            String requestId) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(
                errorCode, message, requestId, false, Map.of(), Instant.now()));
    }
}
