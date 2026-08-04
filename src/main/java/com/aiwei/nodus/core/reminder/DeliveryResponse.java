package com.aiwei.nodus.core.reminder;

import java.time.Instant;
import java.util.UUID;

public record DeliveryResponse(
        String eventId,
        UUID reminderId,
        String tenantId,
        String userId,
        String deviceId,
        String sessionId,
        String text,
        String kind,
        Instant dueAt,
        int deliveryAttempt,
        Instant leaseUntil) {
}
