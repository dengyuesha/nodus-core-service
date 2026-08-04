package com.aiwei.nodus.core.idempotency;

import java.util.UUID;

public record IdempotencyReservation(
        UUID id,
        boolean owner,
        String status,
        Integer responseCode,
        String responseBody) {
}
