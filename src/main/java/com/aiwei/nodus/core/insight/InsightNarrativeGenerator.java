package com.aiwei.nodus.core.insight;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 优先调用 AINAS；失败时生成有明确来源标记的确定性事实摘要。 */
@Service
public class InsightNarrativeGenerator {
    private static final Logger log = LoggerFactory.getLogger(InsightNarrativeGenerator.class);
    private static final String PROMPT_VERSION = "nodus-insight-v1";
    private final AinasInsightClient ainas;

    public InsightNarrativeGenerator(AinasInsightClient ainas) {
        this.ainas = ainas;
    }

    public InsightGenerationResult generate(InsightGenerationCommand command) {
        if (ainas.enabled()) {
            try {
                return ainas.generate(command);
            } catch (Exception error) {
                log.warn("AINAS insight generation failed; using deterministic fallback. errorType={}",
                        error.getClass().getSimpleName());
            }
        }
        return fallback(command);
    }

    private InsightGenerationResult fallback(InsightGenerationCommand command) {
        if ("FOLLOW_UP".equals(command.mode())) {
            String answer = "基于该周期已固定的 " + command.evidence().size() + " 条证据记录，"
                    + (command.previousSummary() == null ? "目前没有更多可验证结论。" : command.previousSummary())
                    + " 如需新的判断，请先补充或更正原始结构化记录后重新生成。";
            return new InsightGenerationResult("洞察追问", answer, List.of(), cautions(command.domain()),
                    "DETERMINISTIC", null, PROMPT_VERSION, "FALLBACK");
        }
        return "HEALTH".equals(command.domain()) ? health(command) : finance(command);
    }

    @SuppressWarnings("unchecked")
    private InsightGenerationResult health(InsightGenerationCommand command) {
        Object rawMetrics = command.aggregate().get("metrics");
        Map<String, Object> metrics = rawMetrics instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        List<InsightFinding> findings = new ArrayList<>();
        for (Map.Entry<String, Object> entry : metrics.entrySet()) {
            Map<String, Object> metric = entry.getValue() instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            List<UUID> evidenceIds = command.evidence().stream()
                    .filter(item -> entry.getKey().equals(item.snapshot().get("metricType")))
                    .map(InsightGenerationCommand.EvidenceItem::recordId).limit(20).toList();
            String text = entry.getKey() + "：最新 " + value(metric.get("latest"))
                    + unit(metric.get("unit")) + "，周期均值 " + value(metric.get("average"))
                    + "，范围 " + value(metric.get("minimum")) + "–" + value(metric.get("maximum")) + "。";
            findings.add(new InsightFinding("OBSERVATION", "INFO", text, evidenceIds));
        }
        String summary = metrics.isEmpty()
                ? "该周期没有可用于生成洞察的健康指标。"
                : "该周期共覆盖 " + metrics.size() + " 类健康指标、" + command.evidence().size()
                        + " 条结构化记录。以下内容仅为数据趋势总结。";
        return new InsightGenerationResult(periodLabel(command) + "健康洞察", summary, findings,
                cautions("HEALTH"), "DETERMINISTIC", null, PROMPT_VERSION, "FALLBACK");
    }

    private InsightGenerationResult finance(InsightGenerationCommand command) {
        Map<String, Object> a = new LinkedHashMap<>(command.aggregate());
        String currency = command.currency() == null ? "CNY" : command.currency();
        String summary = "该周期收入 " + money(a.get("income"), currency) + "，支出 "
                + money(a.get("expense"), currency) + "，净现金流 " + money(a.get("netCashFlow"), currency)
                + "，储蓄率 " + value(a.get("savingsRate")) + "% 。";
        List<UUID> flowEvidence = command.evidence().stream()
                .filter(item -> "FINANCIAL_RECORD".equals(item.evidenceType()))
                .map(InsightGenerationCommand.EvidenceItem::recordId).limit(50).toList();
        List<InsightFinding> findings = List.of(
                new InsightFinding("FACT", "INFO", summary, flowEvidence),
                new InsightFinding("BALANCE", "INFO", "最新资产 " + money(a.get("assets"), currency)
                        + "，最新负债 " + money(a.get("liabilities"), currency)
                        + "，净资产 " + money(a.get("netWorth"), currency) + "。", flowEvidence));
        return new InsightGenerationResult(periodLabel(command) + "财务洞察", summary, findings,
                cautions("FINANCE"), "DETERMINISTIC", null, PROMPT_VERSION, "FALLBACK");
    }

    private List<String> cautions(String domain) {
        return "HEALTH".equals(domain)
                ? List.of("仅基于已采集记录进行趋势总结，不构成医学诊断或治疗建议。",
                        "记录缺失、设备误差和测量条件可能影响结论。")
                : List.of("仅总结已入库财务事实，不构成投资、借贷或税务建议。",
                        "不同币种未进行汇率换算，余额采用各账户周期内最新快照。");
    }

    private String periodLabel(InsightGenerationCommand command) {
        return switch (command.periodType()) { case "WEEK" -> "周度"; case "QUARTER" -> "季度"; default -> "月度"; };
    }

    private String money(Object value, String currency) { return currency + " " + value(value); }
    private String unit(Object value) { return value == null ? "" : " " + value; }
    private String value(Object value) {
        if (value == null) return "0";
        if (value instanceof BigDecimal number) return number.stripTrailingZeros().toPlainString();
        return value.toString();
    }
}
