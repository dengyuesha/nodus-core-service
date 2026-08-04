package com.aiwei.nodus.core.reminder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aiwei.nodus.core.identity.NodusRequestContext;

@Repository
public class ReminderRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReminderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ReminderResponse insert(NodusRequestContext context, CreateReminderRequest request, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into reminder (id, tenant_id, user_id, household_id, device_id, session_id, memo_id,
                    text, kind, timezone, due_at, status, delivery_attempt, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SCHEDULED', 0, 1, ?, ?)
                """, id, context.tenantId(), context.userId(), context.householdId(), context.deviceId(),
                context.sessionId(), request.memoId(), request.text().trim(), request.kind().trim().toLowerCase(),
                request.timezone(), Timestamp.from(request.dueAt()), Timestamp.from(now), Timestamp.from(now));
        return find(context, id).orElseThrow();
    }

    public Optional<ReminderResponse> find(NodusRequestContext context, UUID id) {
        return jdbcTemplate.query("""
                select id, memo_id, text, kind, timezone, due_at, status, delivery_attempt,
                       next_retry_at, created_at, updated_at
                  from reminder where id = ? and tenant_id = ? and user_id = ?
                """, this::map, id, context.tenantId(), context.userId()).stream().findFirst();
    }

    public List<ReminderResponse> list(NodusRequestContext context, boolean includeTerminal) {
        String clause = includeTerminal ? "" : " and status not in ('ACKNOWLEDGED','CANCELLED','FAILED')";
        return jdbcTemplate.query("""
                select id, memo_id, text, kind, timezone, due_at, status, delivery_attempt,
                       next_retry_at, created_at, updated_at
                  from reminder where tenant_id = ? and user_id = ?
                """ + clause + " order by due_at", this::map, context.tenantId(), context.userId());
    }

    public int cancel(NodusRequestContext context, UUID id, Instant now) {
        return jdbcTemplate.update("""
                update reminder set status = 'CANCELLED', cancelled_at = ?, updated_at = ?, version = version + 1
                 where id = ? and tenant_id = ? and user_id = ?
                   and status in ('SCHEDULED','DUE')
                """, Timestamp.from(now), Timestamp.from(now), id, context.tenantId(), context.userId());
    }

    private ReminderResponse map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ReminderResponse(rs.getObject("id", UUID.class), rs.getObject("memo_id", UUID.class),
                rs.getString("text"), rs.getString("kind"), rs.getString("timezone"),
                rs.getTimestamp("due_at").toInstant(), rs.getString("status"), rs.getInt("delivery_attempt"),
                instant(rs.getTimestamp("next_retry_at")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
