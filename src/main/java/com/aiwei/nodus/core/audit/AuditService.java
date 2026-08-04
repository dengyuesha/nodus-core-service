package com.aiwei.nodus.core.audit;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.identity.NodusRequestContext;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void append(
            NodusRequestContext context,
            String action,
            String resourceType,
            String resourceId,
            Object details) {
        jdbcTemplate.update("""
                insert into audit_record (
                    id, request_id, tenant_id, user_id, device_id, session_id, source_client,
                    action, resource_type, resource_id, details, occurred_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), context.requestId(), context.tenantId(), context.userId(),
                context.deviceId(), context.sessionId(), context.sourceClient(), action, resourceType,
                resourceId, json(details), Timestamp.from(Instant.now(clock)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "AUDIT_SERIALIZATION_FAILED", "审计信息序列化失败", true, null);
        }
    }
}
