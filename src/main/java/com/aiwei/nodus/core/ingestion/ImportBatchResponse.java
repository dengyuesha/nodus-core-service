package com.aiwei.nodus.core.ingestion;

import java.util.List;

/** 结构化数据批量导入汇总。 */
public record ImportBatchResponse(int total, int created, int replayed, int rejected,
        List<ImportItemResult> results) {

    public static ImportBatchResponse from(List<ImportItemResult> results) {
        int created = (int) results.stream().filter(value -> "CREATED".equals(value.status())).count();
        int replayed = (int) results.stream().filter(value -> "REPLAYED".equals(value.status())).count();
        int rejected = results.size() - created - replayed;
        return new ImportBatchResponse(results.size(), created, replayed, rejected, results);
    }
}
