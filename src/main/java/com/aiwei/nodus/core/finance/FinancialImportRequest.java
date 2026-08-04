package com.aiwei.nodus.core.finance;

import java.util.List;

/** 单一来源系统提交的财务结构化记录批次。 */
public record FinancialImportRequest(String sourceSystem, List<FinancialRecordInput> records) {
}
