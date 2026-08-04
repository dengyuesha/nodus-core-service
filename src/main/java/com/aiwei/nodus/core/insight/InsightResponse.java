package com.aiwei.nodus.core.insight;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 已持久化洞察以及生成来源和证据。 */
public record InsightResponse(
        UUID insightId,
        String domain,
        String periodType,
        Instant periodStart,
        Instant periodEnd,
        Instant dataCutoff,
        String currency,
        String title,
        String summary,
        List<InsightFinding> findings,
        List<String> cautions,
        String provider,
        String modelName,
        String promptVersion,
        String generationMode,
        UUID supersedesInsightId,
        List<InsightEvidenceReference> evidence,
        Instant createdAt) {

    public InsightResponse {
        findings = findings == null ? List.of() : List.copyOf(findings);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
