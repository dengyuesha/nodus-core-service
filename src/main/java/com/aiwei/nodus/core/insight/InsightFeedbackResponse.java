package com.aiwei.nodus.core.insight;

import java.time.Instant;
import java.util.UUID;

public record InsightFeedbackResponse(UUID feedbackId, UUID insightId, String rating, String comment, Instant createdAt) {
}
