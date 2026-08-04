package com.aiwei.nodus.core.memo;

import java.time.Instant;
import java.util.UUID;

public record MemoResponse(
        UUID memoId,
        String text,
        String rawText,
        String status,
        int version,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
