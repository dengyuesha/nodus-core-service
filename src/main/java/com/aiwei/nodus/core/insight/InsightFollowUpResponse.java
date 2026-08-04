package com.aiwei.nodus.core.insight;

import java.time.Instant;
import java.util.UUID;

public record InsightFollowUpResponse(
        UUID followUpId,
        UUID insightId,
        String question,
        String answer,
        String provider,
        String modelName,
        String promptVersion,
        Instant createdAt) {
}
