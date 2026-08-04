package com.aiwei.nodus.core.device;

import java.time.Instant;
import java.util.UUID;

public record DeviceRegistration(
        UUID id,
        String tenantId,
        String userId,
        String householdId,
        String deviceId,
        String displayName,
        String status,
        Instant registeredAt,
        Instant updatedAt) {
}
