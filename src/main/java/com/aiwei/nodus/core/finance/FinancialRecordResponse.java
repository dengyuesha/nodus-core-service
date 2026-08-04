package com.aiwei.nodus.core.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 已持久化财务记录。 */
public record FinancialRecordResponse(UUID recordId, String sourceSystem, String sourceRecordId,
        String recordType, BigDecimal amount, String currency, String category, String account,
        String description, Instant occurredAt, Map<String, Object> metadata, Instant createdAt) {
}
