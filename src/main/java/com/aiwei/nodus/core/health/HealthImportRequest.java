package com.aiwei.nodus.core.health;

import java.util.List;

/** 单一来源系统提交的健康结构化记录批次。 */
public record HealthImportRequest(String sourceSystem, List<HealthRecordInput> records) {
}
