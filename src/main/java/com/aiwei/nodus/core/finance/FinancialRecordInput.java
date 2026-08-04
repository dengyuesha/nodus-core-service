package com.aiwei.nodus.core.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** IM 完成识别和字段抽取后提交的一条财务记录；不包含图片或 OCR 逻辑。 */
public record FinancialRecordInput(
        String sourceRecordId,
        String recordType,
        BigDecimal amount,
        String currency,
        String category,
        String account,
        String description,
        Instant occurredAt,
        Map<String, Object> metadata) {
}
