package com.aiwei.nodus.core.health;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 指定阶段内按指标聚合的健康摘要。 */
public record HealthSummaryResponse(Instant from, Instant to, Map<String, MetricSummary> metrics) {
    public record MetricSummary(BigDecimal latest, BigDecimal average, BigDecimal minimum,
            BigDecimal maximum, String unit, Instant latestAt, List<Point> series) { }
    public record Point(Instant at, BigDecimal value) { }
}
