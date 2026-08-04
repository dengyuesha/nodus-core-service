package com.aiwei.nodus.core.insight;

import java.util.List;
import java.util.UUID;

/** 一条可追溯到事实记录的洞察结论。 */
public record InsightFinding(
        String type,
        String severity,
        String text,
        List<UUID> evidenceRecordIds) {

    public InsightFinding {
        evidenceRecordIds = evidenceRecordIds == null ? List.of() : List.copyOf(evidenceRecordIds);
    }
}
