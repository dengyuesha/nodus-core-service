package com.aiwei.nodus.core.insight;

/** 用户对洞察的明确反馈。 */
public record InsightFeedbackRequest(String rating, String comment) {
}
