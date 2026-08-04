package com.aiwei.nodus.core.insight;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Core 发给无状态生成适配器的最小化结构化上下文。 */
public record InsightGenerationCommand(
        String mode,
        String domain,
        String periodType,
        Instant periodStart,
        Instant periodEnd,
        Instant dataCutoff,
        String currency,
        Map<String, Object> aggregate,
        List<EvidenceItem> evidence,
        String previousTitle,
        String previousSummary,
        String question) {

    public record EvidenceItem(String evidenceType, UUID recordId, Instant occurredAt, Map<String, Object> snapshot) {
    }
}
