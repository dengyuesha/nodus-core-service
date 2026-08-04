package com.aiwei.nodus.core.insight;

import java.util.List;

/** AINAS 或本地降级生成器返回的结构化正文。 */
public record InsightGenerationResult(
        String title,
        String summary,
        List<InsightFinding> findings,
        List<String> cautions,
        String provider,
        String modelName,
        String promptVersion,
        String generationMode) {

    public InsightGenerationResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
    }
}
