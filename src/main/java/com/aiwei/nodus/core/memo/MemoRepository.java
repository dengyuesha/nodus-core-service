package com.aiwei.nodus.core.memo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aiwei.nodus.core.identity.NodusRequestContext;

@Repository
public class MemoRepository {

    private final JdbcTemplate jdbcTemplate;

    public MemoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MemoResponse insert(NodusRequestContext context, CreateMemoRequest request, Instant now) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into memo (id, tenant_id, user_id, household_id, device_id, text, raw_text,
                                  status, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, 'OPEN', 1, ?, ?)
                """, id, context.tenantId(), context.userId(), context.householdId(), context.deviceId(),
                request.text().trim(), blankToNull(request.rawText()), Timestamp.from(now), Timestamp.from(now));
        return find(context, id).orElseThrow();
    }

    public List<MemoResponse> list(NodusRequestContext context, boolean includeClosed) {
        String statusClause = includeClosed ? "" : " and status = 'OPEN'";
        return jdbcTemplate.query("""
                select id, text, raw_text, status, version, created_at, updated_at, completed_at
                  from memo where tenant_id = ? and user_id = ? and status <> 'DELETED'
                """ + statusClause + " order by created_at desc", this::map,
                context.tenantId(), context.userId());
    }

    public Optional<MemoResponse> find(NodusRequestContext context, UUID id) {
        return jdbcTemplate.query("""
                select id, text, raw_text, status, version, created_at, updated_at, completed_at
                  from memo where id = ? and tenant_id = ? and user_id = ? and status <> 'DELETED'
                """, this::map, id, context.tenantId(), context.userId()).stream().findFirst();
    }

    public int update(NodusRequestContext context, UUID id, UpdateMemoRequest request, Instant now) {
        return jdbcTemplate.update("""
                update memo
                   set text = coalesce(?, text), status = coalesce(?, status),
                       completed_at = case when ? = 'COMPLETED' then ? else completed_at end,
                       version = version + 1, updated_at = ?
                 where id = ? and tenant_id = ? and user_id = ? and status <> 'DELETED' and version = ?
                """, blankToNull(request.text()), request.status(), request.status(), Timestamp.from(now),
                Timestamp.from(now), id, context.tenantId(), context.userId(), request.version());
    }

    public int softDelete(NodusRequestContext context, UUID id, Instant now) {
        return jdbcTemplate.update("""
                update memo set status = 'DELETED', deleted_at = ?, updated_at = ?, version = version + 1
                 where id = ? and tenant_id = ? and user_id = ? and status <> 'DELETED'
                """, Timestamp.from(now), Timestamp.from(now), id, context.tenantId(), context.userId());
    }

    private MemoResponse map(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        Timestamp completedAt = resultSet.getTimestamp("completed_at");
        return new MemoResponse(
                resultSet.getObject("id", UUID.class), resultSet.getString("text"),
                resultSet.getString("raw_text"), resultSet.getString("status"), resultSet.getInt("version"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
