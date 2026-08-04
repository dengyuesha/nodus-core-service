package com.aiwei.nodus.core.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.aiwei.nodus.core.config.NodusCoreProperties;
import com.aiwei.nodus.core.identity.FilterErrorWriter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final NodusCoreProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyFilter(NodusCoreProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (properties.apiKey().isBlank() || matches(properties.apiKey(), request.getHeader("X-Nodus-Api-Key"))) {
            filterChain.doFilter(request, response);
            return;
        }
        String requestId = response.getHeader("X-Request-Id");
        FilterErrorWriter.write(response, objectMapper, HttpStatus.UNAUTHORIZED.value(),
                "UNAUTHORIZED", "无效的服务访问凭证", requestId);
    }

    private boolean matches(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
