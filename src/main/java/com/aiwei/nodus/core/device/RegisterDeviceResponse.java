package com.aiwei.nodus.core.device;

import java.time.Instant;
import java.util.UUID;

public record RegisterDeviceResponse(
        UUID registrationId,
        String tenantId,
        String userId,
        String householdId,
        String deviceId,
        String displayName,
        String status,
        Instant registeredAt,
        Instant updatedAt) {

    static RegisterDeviceResponse from(DeviceRegistration registration) {
        return new RegisterDeviceResponse(
                registration.id(), registration.tenantId(), registration.userId(),
                registration.householdId(), registration.deviceId(), registration.displayName(),
                registration.status(), registration.registeredAt(), registration.updatedAt());
    }
}
