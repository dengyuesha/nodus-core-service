package com.aiwei.nodus.core.reminder;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.audit.AuditService;
import com.aiwei.nodus.core.config.NodusCoreProperties;
import com.aiwei.nodus.core.identity.NodusRequestContext;

@Service
public class ReminderDeliveryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NodusCoreProperties properties;
    private final AuditService audit;
    private final Clock clock;

    public ReminderDeliveryService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
            NodusCoreProperties properties, AuditService audit, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public List<DeliveryResponse> claim(NodusRequestContext context, int limit) {
        return claim(context, limit, false);
    }

    @Transactional
    public List<DeliveryResponse> claimTenant(NodusRequestContext context, int limit) {
        return claim(context, limit, true);
    }

    private List<DeliveryResponse> claim(NodusRequestContext context, int limit, boolean allUsers) {
        Instant now = Instant.now(clock);
        Instant leaseUntil = now.plus(properties.deliveryLease());
        String userClause = allUsers ? "" : " and user_id = ?";
        List<Object> arguments = new ArrayList<>();
        arguments.add(context.tenantId());
        if (!allUsers) {
            arguments.add(context.userId());
        }
        arguments.add(Timestamp.from(now));
        arguments.add(Timestamp.from(now));
        arguments.add(limit);
        List<DeliveryRow> candidates = jdbcTemplate.query("""
                select id, event_id, reminder_id, tenant_id, user_id, payload, attempt
                  from reminder_delivery
                 where tenant_id = ?
                """ + userClause + """
                   and ((status in ('PENDING','FAILED') and next_retry_at <= ?)
                     or (status = 'DELIVERING' and lease_until <= ?))
                 order by created_at limit ?
                """, (rs, row) -> new DeliveryRow(rs.getObject("id", UUID.class), rs.getString("event_id"),
                        rs.getObject("reminder_id", UUID.class), rs.getString("tenant_id"), rs.getString("user_id"),
                        rs.getString("payload"), rs.getInt("attempt")), arguments.toArray());
        List<DeliveryResponse> claimed = new ArrayList<>();
        for (DeliveryRow row : candidates) {
            int updated = jdbcTemplate.update("""
                    update reminder_delivery set status = 'DELIVERING', attempt = attempt + 1,
                        lease_until = ?, delivered_at = ?, last_error = null
                     where id = ? and ((status in ('PENDING','FAILED') and next_retry_at <= ?)
                        or (status = 'DELIVERING' and lease_until <= ?))
                    """, Timestamp.from(leaseUntil), Timestamp.from(now), row.id(),
                    Timestamp.from(now), Timestamp.from(now));
            if (updated == 1) {
                jdbcTemplate.update("update reminder set delivery_attempt = ?, updated_at = ? where id = ?",
                        row.attempt() + 1, Timestamp.from(now), row.reminderId());
                claimed.add(read(row, row.attempt() + 1, leaseUntil));
            }
        }
        return claimed;
    }

    @Transactional
    public void acknowledge(NodusRequestContext context, String eventId, String source) {
        Instant now = Instant.now(clock);
        List<UUID> reminders = reminderIds(context, eventId);
        if (reminders.isEmpty()) {
            throw new DomainException(HttpStatus.NOT_FOUND, "DELIVERY_NOT_FOUND", "投递事件不存在");
        }
        int updated = jdbcTemplate.update("""
                update reminder_delivery set status = 'ACKNOWLEDGED', acknowledged_at = ?, ack_source = ?,
                    lease_until = null
                 where event_id = ? and tenant_id = ? and user_id = ? and status = 'DELIVERING'
                """, Timestamp.from(now), source, eventId, context.tenantId(), context.userId());
        if (updated == 0) {
            String status = deliveryStatus(context, eventId);
            if ("ACKNOWLEDGED".equals(status)) {
                return;
            }
            throw new DomainException(HttpStatus.CONFLICT, "DELIVERY_STATE_CONFLICT", "投递事件当前不可确认");
        }
        UUID reminderId = reminders.get(0);
        jdbcTemplate.update("update reminder set status = 'ACKNOWLEDGED', updated_at = ? where id = ?",
                Timestamp.from(now), reminderId);
        audit.append(context, "REMINDER_ACKNOWLEDGED", "reminder", reminderId.toString(),
                Map.of("eventId", eventId, "source", source));
    }

    @Transactional
    public void fail(NodusRequestContext context, String eventId, String error) {
        Instant now = Instant.now(clock);
        int updated = jdbcTemplate.update("""
                update reminder_delivery set status = 'FAILED', last_error = ?, lease_until = null,
                    next_retry_at = ?
                 where event_id = ? and tenant_id = ? and user_id = ? and status = 'DELIVERING'
                """, error, Timestamp.from(now.plus(properties.deliveryRetryDelay())), eventId,
                context.tenantId(), context.userId());
        if (updated == 0) {
            throw new DomainException(HttpStatus.CONFLICT, "DELIVERY_STATE_CONFLICT", "投递事件当前不可标记失败");
        }
    }

    private List<UUID> reminderIds(NodusRequestContext context, String eventId) {
        return jdbcTemplate.query("select reminder_id from reminder_delivery where event_id = ? and tenant_id = ? and user_id = ?",
                (rs, row) -> rs.getObject(1, UUID.class), eventId, context.tenantId(), context.userId());
    }

    private String deliveryStatus(NodusRequestContext context, String eventId) {
        return jdbcTemplate.queryForObject("select status from reminder_delivery where event_id = ? and tenant_id = ? and user_id = ?",
                String.class, eventId, context.tenantId(), context.userId());
    }

    private DeliveryResponse read(DeliveryRow row, int attempt, Instant leaseUntil) {
        try {
            var node = objectMapper.readTree(row.payload());
            return new DeliveryResponse(node.get("eventId").asText(), UUID.fromString(node.get("reminderId").asText()),
                    row.tenantId(), row.userId(),
                    text(node, "deviceId"), text(node, "sessionId"), node.get("text").asText(),
                    node.get("kind").asText(), Instant.parse(node.get("dueAt").asText()), attempt, leaseUntil);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR, "DELIVERY_PAYLOAD_INVALID", "投递内容无法读取");
        }
    }

    private String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private record DeliveryRow(UUID id, String eventId, UUID reminderId, String tenantId, String userId,
            String payload, int attempt) {
    }
}
