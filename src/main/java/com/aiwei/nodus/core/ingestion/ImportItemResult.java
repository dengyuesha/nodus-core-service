package com.aiwei.nodus.core.ingestion;

import java.util.UUID;

/** 结构化数据批量导入中的逐条结果。 */
public record ImportItemResult(
        String sourceRecordId,
        UUID recordId,
        String status,
        String errorCode,
        String message) {

    public static ImportItemResult created(String sourceRecordId, UUID id) {
        return new ImportItemResult(sourceRecordId, id, "CREATED", null, null);
    }

    public static ImportItemResult replayed(String sourceRecordId, UUID id) {
        return new ImportItemResult(sourceRecordId, id, "REPLAYED", null, null);
    }

    public static ImportItemResult rejected(String sourceRecordId, String code, String message) {
        return new ImportItemResult(sourceRecordId, null, "REJECTED", code, message);
    }
}
