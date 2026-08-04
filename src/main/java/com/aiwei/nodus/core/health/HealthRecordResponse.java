package com.aiwei.nodus.core.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 已持久化健康记录。 */
public record HealthRecordResponse(UUID recordId, String sourceSystem, String sourceRecordId,
        String metricType, BigDecimal value, String unit, Instant measuredAt,
        Map<String, Object> metadata, Instant createdAt) {
}
