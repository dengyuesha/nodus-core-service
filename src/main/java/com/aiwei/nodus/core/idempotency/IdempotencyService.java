package com.aiwei.nodus.core.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.config.NodusCoreProperties;
import com.aiwei.nodus.core.identity.NodusRequestContext;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final NodusCoreProperties properties;
    private final Clock clock;
    private final boolean postgres;

    public IdempotencyService(
            JdbcTemplate jdbcTemplate,
            NodusCoreProperties properties,
            Clock clock,
            DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.clock = clock;
        this.postgres = isPostgres(dataSource);
    }

    public IdempotencyReservation reserve(
            NodusRequestContext context,
            String operation,
            String key,
            String requestHash) {
        if (key == null || key.isBlank() || key.length() > 256) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key 必填且长度不能超过 256");
        }

        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        int inserted;
        if (postgres) {
            inserted = jdbcTemplate.update("""
                    insert into idempotency_record (
                        id, tenant_id, user_id, operation, idempotency_key, request_hash,
                        status, created_at, expires_at
                    ) values (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                    on conflict (tenant_id, user_id, operation, idempotency_key) do nothing
                    """, id, context.tenantId(), context.userId(), operation, key.trim(), requestHash,
                    Timestamp.from(now), Timestamp.from(now.plus(properties.idempotencyTtl())));
        } else if (findOptional(context, operation, key.trim()).isEmpty()) {
            inserted = jdbcTemplate.update("""
                    insert into idempotency_record (
                        id, tenant_id, user_id, operation, idempotency_key, request_hash,
                        status, created_at, expires_at
                    ) values (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                    """, id, context.tenantId(), context.userId(), operation, key.trim(), requestHash,
                    Timestamp.from(now), Timestamp.from(now.plus(properties.idempotencyTtl())));
        } else {
            inserted = 0;
        }
        if (inserted == 1) {
            return new IdempotencyReservation(id, true, "IN_PROGRESS", null, null);
        } else {
            IdempotencyRow existing = find(context, operation, key.trim());
            if (!existing.expiresAt().isAfter(now)) {
                int reset = jdbcTemplate.update("""
                        update idempotency_record
                           set request_hash = ?, status = 'IN_PROGRESS', response_code = null,
                               response_body = null, created_at = ?, completed_at = null, expires_at = ?
                         where id = ? and expires_at <= ?
                        """, requestHash, Timestamp.from(now),
                        Timestamp.from(now.plus(properties.idempotencyTtl())), existing.id(), Timestamp.from(now));
                if (reset == 1) {
                    return new IdempotencyReservation(existing.id(), true, "IN_PROGRESS", null, null);
                }
                existing = find(context, operation, key.trim());
            }
            if (!MessageDigestSupport.safeEquals(existing.requestHash(), requestHash)) {
                throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                        "同一个 Idempotency-Key 不能用于不同请求", false,
                        Map.of("operation", operation));
            }
            return new IdempotencyReservation(existing.id(), false, existing.status(),
                    existing.responseCode(), existing.responseBody());
        }
    }

    public void complete(UUID id, int responseCode, String responseBody) {
        int updated = jdbcTemplate.update("""
                update idempotency_record
                   set status = 'COMPLETED', response_code = ?, response_body = ?, completed_at = ?
                 where id = ? and status = 'IN_PROGRESS'
                """, responseCode, responseBody, Timestamp.from(Instant.now(clock)), id);
        if (updated != 1) {
            throw new DomainException(HttpStatus.CONFLICT, "IDEMPOTENCY_STATE_CONFLICT",
                    "幂等请求状态已发生变化", true, Map.of("recordId", id.toString()));
        }
    }

    private IdempotencyRow find(NodusRequestContext context, String operation, String key) {
        return findOptional(context, operation, key).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Idempotency record disappeared"));
    }

    private List<IdempotencyRow> findOptional(NodusRequestContext context, String operation, String key) {
        return jdbcTemplate.query("""
                select id, request_hash, status, response_code, response_body, expires_at
                  from idempotency_record
                 where tenant_id = ? and user_id = ? and operation = ? and idempotency_key = ?
                """, (resultSet, rowNumber) -> new IdempotencyRow(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("request_hash"),
                        resultSet.getString("status"),
                        (Integer) resultSet.getObject("response_code"),
                        resultSet.getString("response_body"),
                        resultSet.getTimestamp("expires_at").toInstant()),
                context.tenantId(), context.userId(), operation, key);
    }

    private boolean isPostgres(DataSource dataSource) {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("Unable to determine database type", exception);
        }
    }

    private record IdempotencyRow(
            UUID id,
            String requestHash,
            String status,
            Integer responseCode,
            String responseBody,
            Instant expiresAt) {
    }
}
