package com.aiwei.nodus.core.outbox;

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
public class OutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public String append(
            NodusRequestContext context,
            String eventType,
            String aggregateType,
            String aggregateId,
            Object payload) {
        Instant now = Instant.now(clock);
        String eventId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                insert into outbox_event (
                    id, event_id, request_id, tenant_id, user_id, device_id, session_id,
                    event_type, aggregate_type, aggregate_id, payload, status, attempt_count,
                    next_attempt_at, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                """, UUID.randomUUID(), eventId, context.requestId(), context.tenantId(), context.userId(),
                context.deviceId(), context.sessionId(), eventType, aggregateType, aggregateId,
                json(payload), Timestamp.from(now), Timestamp.from(now));
        return eventId;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "OUTBOX_SERIALIZATION_FAILED", "事件信息序列化失败", true, null);
        }
    }
}
