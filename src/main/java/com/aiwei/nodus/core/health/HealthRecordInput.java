package com.aiwei.nodus.core.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** IM 完成识别和字段抽取后提交的一条健康指标；不包含图片或 OCR 逻辑。 */
public record HealthRecordInput(
        String sourceRecordId,
        String metricType,
        BigDecimal value,
        String unit,
        Instant measuredAt,
        Map<String, Object> metadata) {
}
