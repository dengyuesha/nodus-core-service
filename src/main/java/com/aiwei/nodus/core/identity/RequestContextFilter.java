package com.aiwei.nodus.core.identity;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

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

@Component("nodusRequestContextFilter")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter extends OncePerRequestFilter {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:@-]{1,128}");

    private final ObjectMapper objectMapper;

    public RequestContextFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String requestId = optionalId(request, "X-Request-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader("X-Request-Id", requestId);

        if (!request.getRequestURI().startsWith("/api/v1/")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            NodusRequestContext context = new NodusRequestContext(
                    requiredId(request, "X-Tenant-Id"),
                    requiredId(request, "X-User-Id"),
                    optionalId(request, "X-Household-Id"),
                    optionalId(request, "X-Device-Id"),
                    optionalId(request, "X-Session-Id"),
                    requestId,
                    optionalId(request, "X-Source-Client"));
            request.setAttribute(NodusRequestContext.ATTRIBUTE_NAME, context);
            filterChain.doFilter(request, response);
        } catch (IllegalArgumentException exception) {
            FilterErrorWriter.write(response, objectMapper, HttpStatus.BAD_REQUEST.value(),
                    "INVALID_REQUEST_CONTEXT", exception.getMessage(), requestId);
        }
    }

    private String requiredId(HttpServletRequest request, String header) {
        String value = optionalId(request, header);
        if (value == null) {
            throw new IllegalArgumentException("缺少请求头 " + header);
        }
        return value;
    }

    private String optionalId(HttpServletRequest request, String header) {
        String value = request.getHeader(header);
        if (value == null || value.isBlank()) {
            return null;
        }
        value = value.trim();
        if (!SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("请求头 " + header + " 格式无效");
        }
        return value;
    }
}
