package com.aiwei.nodus.core.device;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aiwei.nodus.core.identity.NodusRequestContext;

@Repository
public class DeviceRegistrationRepository {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public DeviceRegistrationRepository(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public DeviceRegistration upsert(
            NodusRequestContext context,
            String deviceId,
            String householdId,
            String displayName) {
        Instant now = Instant.now(clock);
        int updated = jdbcTemplate.update("""
                update device_registration
                   set user_id = ?, household_id = ?, display_name = ?, status = 'ACTIVE', updated_at = ?
                 where tenant_id = ? and device_id = ?
                """, context.userId(), householdId, displayName, Timestamp.from(now),
                context.tenantId(), deviceId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into device_registration (
                        id, tenant_id, user_id, household_id, device_id, display_name,
                        status, registered_at, updated_at
                    ) values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """, UUID.randomUUID(), context.tenantId(), context.userId(), householdId,
                    deviceId, displayName, Timestamp.from(now), Timestamp.from(now));
        }
        return find(context.tenantId(), deviceId);
    }

    private DeviceRegistration find(String tenantId, String deviceId) {
        List<DeviceRegistration> matches = jdbcTemplate.query("""
                select id, tenant_id, user_id, household_id, device_id, display_name,
                       status, registered_at, updated_at
                  from device_registration
                 where tenant_id = ? and device_id = ?
                """, (resultSet, rowNumber) -> new DeviceRegistration(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getString("tenant_id"),
                        resultSet.getString("user_id"),
                        resultSet.getString("household_id"),
                        resultSet.getString("device_id"),
                        resultSet.getString("display_name"),
                        resultSet.getString("status"),
                        resultSet.getTimestamp("registered_at").toInstant(),
                        resultSet.getTimestamp("updated_at").toInstant()),
                tenantId, deviceId);
        if (matches.size() != 1) {
            throw new IllegalStateException("Device registration was not persisted");
        }
        return matches.get(0);
    }
}
