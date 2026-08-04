package com.aiwei.nodus.core.reminder;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReminderScheduler(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${nodus.core.reminder-scan-interval:1s}")
    @Transactional
    public void enqueueDueReminders() {
        Instant now = Instant.now(clock);
        List<DueReminder> due = jdbcTemplate.query("""
                select id, tenant_id, user_id, device_id, session_id, text, kind, due_at
                  from reminder where status = 'SCHEDULED' and due_at <= ?
                 order by due_at limit 100
                """, (rs, row) -> new DueReminder(rs.getObject("id", UUID.class), rs.getString("tenant_id"),
                        rs.getString("user_id"), rs.getString("device_id"), rs.getString("session_id"),
                        rs.getString("text"), rs.getString("kind"), rs.getTimestamp("due_at").toInstant()),
                Timestamp.from(now));
        for (DueReminder reminder : due) {
            int claimed = jdbcTemplate.update("""
                    update reminder set status = 'DUE', updated_at = ?, version = version + 1
                     where id = ? and status = 'SCHEDULED'
                    """, Timestamp.from(now), reminder.id());
            if (claimed == 1) {
                enqueue(reminder, now);
            }
        }
    }

    private void enqueue(DueReminder reminder, Instant now) {
        String eventId = UUID.randomUUID().toString();
        DeliveryPayload payload = new DeliveryPayload(eventId, reminder.id(), reminder.deviceId(),
                reminder.sessionId(), reminder.text(), reminder.kind(), reminder.dueAt());
        try {
            jdbcTemplate.update("""
                    insert into reminder_delivery (id, event_id, reminder_id, tenant_id, user_id, device_id,
                        session_id, attempt, status, next_retry_at, payload, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', ?, ?, ?)
                    """, UUID.randomUUID(), eventId, reminder.id(), reminder.tenantId(), reminder.userId(),
                    reminder.deviceId(), reminder.sessionId(), Timestamp.from(now),
                    objectMapper.writeValueAsString(payload), Timestamp.from(now));
        } catch (JsonProcessingException exception) {
            log.error("Failed to serialize reminder delivery, reminderId={}", reminder.id(), exception);
            throw new IllegalStateException("Reminder delivery serialization failed", exception);
        }
    }

    private record DueReminder(UUID id, String tenantId, String userId, String deviceId, String sessionId,
            String text, String kind, Instant dueAt) {
    }

    private record DeliveryPayload(String eventId, UUID reminderId, String deviceId, String sessionId,
            String text, String kind, Instant dueAt) {
    }
}
