package com.aiwei.nodus.core.reminder;

import java.time.Instant;
import java.util.UUID;

public record ReminderResponse(
        UUID reminderId,
        UUID memoId,
        String text,
        String kind,
        String timezone,
        Instant dueAt,
        String status,
        int deliveryAttempt,
        Instant nextRetryAt,
        Instant createdAt,
        Instant updatedAt) {
}
