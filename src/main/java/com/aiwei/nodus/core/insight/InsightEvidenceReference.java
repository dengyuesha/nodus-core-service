package com.aiwei.nodus.core.insight;

import java.time.Instant;
import java.util.UUID;

/** 返回给设备端的最小证据引用。 */
public record InsightEvidenceReference(String evidenceType, UUID recordId, Instant occurredAt) {
}
