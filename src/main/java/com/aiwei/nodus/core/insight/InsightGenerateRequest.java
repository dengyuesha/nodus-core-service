package com.aiwei.nodus.core.insight;

import java.time.LocalDate;

/** 创建某个自然周、月或季度洞察的请求。 */
public record InsightGenerateRequest(
        String domain,
        String periodType,
        LocalDate anchorDate,
        String currency,
        Boolean force) {
}
