package com.aiwei.nodus.core.insight;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.audit.AuditService;
import com.aiwei.nodus.core.finance.FinancialRecordResponse;
import com.aiwei.nodus.core.finance.FinancialRecordService;
import com.aiwei.nodus.core.health.HealthRecordResponse;
import com.aiwei.nodus.core.health.HealthRecordService;
import com.aiwei.nodus.core.idempotency.RequestHasher;
import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.insight.InsightGenerationCommand.EvidenceItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 周/月/季度洞察的证据固定、生成、持久化、反馈、追问与重生成。 */
@Service
public class InsightService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> DOMAINS = Set.of("HEALTH", "FINANCE");
    private static final Set<String> PERIODS = Set.of("WEEK", "MONTH", "QUARTER");
    private static final Set<String> RATINGS = Set.of("HELPFUL", "NOT_HELPFUL");
    private static final TypeReference<List<InsightFinding>> FINDINGS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRINGS_TYPE = new TypeReference<>() { };
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RequestHasher hasher;
    private final AuditService audit;
    private final HealthRecordService health;
    private final FinancialRecordService finance;
    private final InsightNarrativeGenerator generator;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public InsightService(JdbcTemplate jdbc, ObjectMapper mapper, RequestHasher hasher, AuditService audit,
            HealthRecordService health, FinancialRecordService finance, InsightNarrativeGenerator generator,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hasher = hasher;
        this.audit = audit;
        this.health = health;
        this.finance = finance;
        this.generator = generator;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public InsightResponse generate(NodusRequestContext context, InsightGenerateRequest request) {
        if (request == null) throw bad("INSIGHT_REQUEST_INVALID", "请求不能为空");
        String domain = upper(request.domain());
        String period = upper(request.periodType());
        if (!DOMAINS.contains(domain)) throw bad("INSIGHT_DOMAIN_INVALID", "domain 仅支持 HEALTH、FINANCE");
        if (!PERIODS.contains(period)) throw bad("INSIGHT_PERIOD_INVALID", "periodType 仅支持 WEEK、MONTH、QUARTER");
        PeriodWindow window = window(period, request.anchorDate());
        return generateForWindow(context, domain, period, window, normalizeCurrency(request.currency()),
                Boolean.TRUE.equals(request.force()), null);
    }

    public InsightResponse regenerate(NodusRequestContext context, UUID insightId) {
        StoredReport original = owned(context, insightId);
        PeriodWindow window = new PeriodWindow(original.periodStart(), original.periodEnd(), original.dataCutoff());
        return generateForWindow(context, original.domain(), original.periodType(), window, original.currency(), true,
                original.id());
    }

    private InsightResponse generateForWindow(NodusRequestContext context, String domain, String period,
            PeriodWindow window, String currency, boolean force, UUID supersedes) {
        EvidenceBundle bundle = evidence(context, domain, window, currency);
        if (bundle.items().isEmpty()) {
            throw new DomainException(HttpStatus.UNPROCESSABLE_ENTITY, "INSIGHT_NO_EVIDENCE",
                    "该周期没有可用于生成洞察的结构化记录");
        }
        String evidenceHash = hasher.hash(Map.of("domain", domain, "period", period,
                "evidence", bundle.items().stream()
                        .sorted(java.util.Comparator.comparing(item -> item.recordId().toString()))
                        .map(item -> Map.of(
                        "type", item.evidenceType(), "recordId", item.recordId(),
                        "occurredAt", item.occurredAt(), "snapshot", item.snapshot())).toList()));
        if (!force) {
            InsightResponse existing = findReusable(context, domain, period, window, evidenceHash);
            if (existing != null) return existing;
        }
        InsightGenerationCommand command = new InsightGenerationCommand("REPORT", domain, period,
                window.start(), window.end(), window.dataCutoff(), currency, bundle.aggregate(),
                bundle.items().stream().limit(200).toList(), null, null, null);
        InsightGenerationResult narrative = generator.generate(command);
        validateNarrative(narrative);
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now(clock);
        transactions.executeWithoutResult(status -> {
            jdbc.update("""
                    insert into insight_report (id, tenant_id, user_id, domain, period_type, period_start,
                        period_end, data_cutoff, currency, evidence_hash, evidence_snapshot, title, summary,
                        findings, cautions, provider, model_name, prompt_version, generation_mode,
                        supersedes_insight_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, context.tenantId(), context.userId(), domain, period,
                    ts(window.start()), ts(window.end()), ts(window.dataCutoff()), currency, evidenceHash,
                    json(bundle.aggregate()), narrative.title(), narrative.summary(), json(narrative.findings()),
                    json(narrative.cautions()), narrative.provider(), narrative.modelName(), narrative.promptVersion(),
                    narrative.generationMode(), supersedes, ts(createdAt));
            for (EvidenceItem item : bundle.items()) {
                jdbc.update("""
                        insert into insight_evidence (insight_id, evidence_type, record_id, occurred_at, snapshot)
                        values (?, ?, ?, ?, ?)
                        """, id, item.evidenceType(), item.recordId(), ts(item.occurredAt()), json(item.snapshot()));
            }
            audit.append(context, supersedes == null ? "INSIGHT_GENERATED" : "INSIGHT_REGENERATED",
                    "insight_report", id.toString(), Map.of("domain", domain, "periodType", period,
                            "provider", narrative.provider(), "evidenceCount", bundle.items().size()));
        });
        return get(context, id);
    }

    public List<InsightResponse> list(NodusRequestContext context, String domain, int limit) {
        String normalized = domain == null || domain.isBlank() ? null : upper(domain);
        if (normalized != null && !DOMAINS.contains(normalized)) {
            throw bad("INSIGHT_DOMAIN_INVALID", "domain 仅支持 HEALTH、FINANCE");
        }
        List<Object> args = new ArrayList<>(List.of(context.tenantId(), context.userId()));
        String clause = normalized == null ? "" : " and domain = ?";
        if (normalized != null) args.add(normalized);
        args.add(Math.max(1, Math.min(limit, 100)));
        List<UUID> ids = jdbc.query("select id from insight_report where tenant_id = ? and user_id = ?"
                        + clause + " order by created_at desc limit ?",
                (rs, row) -> rs.getObject(1, UUID.class), args.toArray());
        return ids.stream().map(id -> get(context, id)).toList();
    }

    public InsightResponse get(NodusRequestContext context, UUID id) {
        return response(owned(context, id));
    }

    public InsightFeedbackResponse feedback(NodusRequestContext context, UUID insightId,
            InsightFeedbackRequest request) {
        owned(context, insightId);
        String rating = request == null ? "" : upper(request.rating());
        if (!RATINGS.contains(rating)) throw bad("INSIGHT_FEEDBACK_INVALID", "rating 仅支持 HELPFUL、NOT_HELPFUL");
        String comment = trim(request.comment());
        if (comment != null && comment.length() > 1000) throw bad("INSIGHT_FEEDBACK_INVALID", "comment 长度不能超过 1000");
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                insert into insight_feedback (id, insight_id, tenant_id, user_id, rating, comment, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, id, insightId, context.tenantId(), context.userId(), rating, comment, ts(now));
        audit.append(context, "INSIGHT_FEEDBACK_RECORDED", "insight_report", insightId.toString(),
                Map.of("feedbackId", id, "rating", rating));
        return new InsightFeedbackResponse(id, insightId, rating, comment, now);
    }

    public InsightFollowUpResponse ask(NodusRequestContext context, UUID insightId, InsightQuestionRequest request) {
        StoredReport report = owned(context, insightId);
        String question = request == null ? null : trim(request.question());
        if (question == null || question.length() > 1000) {
            throw bad("INSIGHT_QUESTION_INVALID", "question 必填且长度不能超过 1000");
        }
        List<EvidenceItem> evidence = evidenceItems(insightId);
        InsightGenerationResult result = generator.generate(new InsightGenerationCommand("FOLLOW_UP",
                report.domain(), report.periodType(), report.periodStart(), report.periodEnd(), report.dataCutoff(),
                report.currency(), report.snapshot(), evidence.stream().limit(200).toList(), report.title(),
                report.summary(), question));
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                insert into insight_follow_up (id, insight_id, tenant_id, user_id, question, answer,
                    provider, model_name, prompt_version, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, insightId, context.tenantId(), context.userId(), question, result.summary(),
                result.provider(), result.modelName(), result.promptVersion(), ts(now));
        audit.append(context, "INSIGHT_FOLLOW_UP_CREATED", "insight_report", insightId.toString(),
                Map.of("followUpId", id, "provider", result.provider()));
        return new InsightFollowUpResponse(id, insightId, question, result.summary(), result.provider(),
                result.modelName(), result.promptVersion(), now);
    }

    private EvidenceBundle evidence(NodusRequestContext context, String domain, PeriodWindow window, String currency) {
        List<EvidenceItem> items = new ArrayList<>();
        Map<String, Object> aggregate;
        if ("HEALTH".equals(domain)) {
            List<HealthRecordResponse> records = health.list(context, window.start(), window.dataCutoff(), null, 1000);
            for (HealthRecordResponse row : records) {
                items.add(new EvidenceItem("HEALTH_RECORD", row.recordId(), row.measuredAt(), Map.of(
                        "metricType", row.metricType(), "value", row.value(), "unit", row.unit(),
                        "measuredAt", row.measuredAt())));
            }
            aggregate = mapper.convertValue(health.summary(context, window.start(), window.dataCutoff()), MAP_TYPE);
        } else {
            List<FinancialRecordResponse> records = finance.list(context, window.start(), window.dataCutoff(), null,
                    currency, 2000);
            for (FinancialRecordResponse row : records) {
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("recordType", row.recordType()); snapshot.put("amount", row.amount());
                snapshot.put("currency", row.currency()); snapshot.put("category", row.category());
                snapshot.put("account", row.account()); snapshot.put("occurredAt", row.occurredAt());
                items.add(new EvidenceItem("FINANCIAL_RECORD", row.recordId(), row.occurredAt(), snapshot));
            }
            aggregate = mapper.convertValue(finance.summary(context, window.start(), window.dataCutoff(), currency), MAP_TYPE);
        }
        return new EvidenceBundle(aggregate, items);
    }

    private PeriodWindow window(String period, LocalDate anchorDate) {
        Instant now = Instant.now(clock);
        LocalDate anchor = anchorDate == null ? LocalDate.ofInstant(now, ZONE) : anchorDate;
        LocalDate startDate;
        LocalDate nextDate;
        if ("WEEK".equals(period)) {
            startDate = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            nextDate = startDate.plusWeeks(1);
        } else if ("MONTH".equals(period)) {
            startDate = anchor.withDayOfMonth(1);
            nextDate = startDate.plusMonths(1);
        } else {
            int firstMonth = ((anchor.getMonthValue() - 1) / 3) * 3 + 1;
            startDate = LocalDate.of(anchor.getYear(), Month.of(firstMonth), 1);
            nextDate = startDate.plusMonths(3);
        }
        Instant start = startDate.atStartOfDay(ZONE).toInstant();
        // periodEnd 使用稳定的右开边界，避免 PostgreSQL 微秒精度截断导致同周期无法复用。
        Instant end = nextDate.atStartOfDay(ZONE).toInstant();
        Instant cutoff = end.isBefore(now) ? end.minusMillis(1) : now;
        return new PeriodWindow(start, end, cutoff);
    }

    private InsightResponse findReusable(NodusRequestContext context, String domain, String period,
            PeriodWindow window, String evidenceHash) {
        List<UUID> ids = jdbc.query("""
                select id from insight_report where tenant_id = ? and user_id = ? and domain = ?
                    and period_type = ? and period_start = ? and period_end = ? and evidence_hash = ?
                order by created_at desc limit 1
                """, (rs, row) -> rs.getObject(1, UUID.class), context.tenantId(), context.userId(), domain,
                period, ts(window.start()), ts(window.end()), evidenceHash);
        return ids.isEmpty() ? null : get(context, ids.get(0));
    }

    private StoredReport owned(NodusRequestContext context, UUID id) {
        if (id == null) throw bad("INSIGHT_ID_INVALID", "insightId 必填");
        List<StoredReport> rows = jdbc.query("""
                select id, domain, period_type, period_start, period_end, data_cutoff, currency,
                    evidence_snapshot, title, summary, findings, cautions, provider, model_name,
                    prompt_version, generation_mode, supersedes_insight_id, created_at
                  from insight_report where id = ? and tenant_id = ? and user_id = ?
                """, (rs, row) -> new StoredReport(rs.getObject("id", UUID.class), rs.getString("domain"),
                rs.getString("period_type"), rs.getTimestamp("period_start").toInstant(),
                rs.getTimestamp("period_end").toInstant(), rs.getTimestamp("data_cutoff").toInstant(),
                rs.getString("currency"), map(rs.getString("evidence_snapshot")), rs.getString("title"),
                rs.getString("summary"), findings(rs.getString("findings")), strings(rs.getString("cautions")),
                rs.getString("provider"), rs.getString("model_name"), rs.getString("prompt_version"),
                rs.getString("generation_mode"), rs.getObject("supersedes_insight_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), id, context.tenantId(), context.userId());
        if (rows.isEmpty()) throw new DomainException(HttpStatus.NOT_FOUND, "INSIGHT_NOT_FOUND", "洞察不存在");
        return rows.get(0);
    }

    private InsightResponse response(StoredReport report) {
        List<InsightEvidenceReference> evidence = jdbc.query("""
                select evidence_type, record_id, occurred_at from insight_evidence
                 where insight_id = ? order by occurred_at, record_id
                """, (rs, row) -> new InsightEvidenceReference(rs.getString(1), rs.getObject(2, UUID.class),
                rs.getTimestamp(3).toInstant()), report.id());
        return new InsightResponse(report.id(), report.domain(), report.periodType(), report.periodStart(),
                report.periodEnd(), report.dataCutoff(), report.currency(), report.title(), report.summary(),
                report.findings(), report.cautions(), report.provider(), report.modelName(), report.promptVersion(),
                report.generationMode(), report.supersedesInsightId(), evidence, report.createdAt());
    }

    private List<EvidenceItem> evidenceItems(UUID insightId) {
        return jdbc.query("select evidence_type, record_id, occurred_at, snapshot from insight_evidence where insight_id = ? order by occurred_at",
                (rs, row) -> new EvidenceItem(rs.getString(1), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant(), map(rs.getString(4))), insightId);
    }

    private void validateNarrative(InsightGenerationResult result) {
        if (result == null || result.title() == null || result.title().isBlank()
                || result.summary() == null || result.summary().isBlank()
                || result.provider() == null || result.provider().isBlank()
                || result.promptVersion() == null || result.promptVersion().isBlank()) {
            throw new DomainException(HttpStatus.BAD_GATEWAY, "INSIGHT_GENERATION_INVALID",
                    "洞察生成器返回了无效结果", true, Map.of());
        }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("洞察数据序列化失败", error); }
    }
    private Map<String, Object> map(String value) {
        try { return mapper.readValue(value, MAP_TYPE); }
        catch (JsonProcessingException error) { return Map.of(); }
    }
    private List<InsightFinding> findings(String value) {
        try { return mapper.readValue(value, FINDINGS_TYPE); }
        catch (JsonProcessingException error) { return List.of(); }
    }
    private List<String> strings(String value) {
        try { return mapper.readValue(value, STRINGS_TYPE); }
        catch (JsonProcessingException error) { return List.of(); }
    }
    private String upper(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String normalizeCurrency(String value) {
        String currency = value == null || value.isBlank() ? "CNY" : upper(value);
        if (!currency.matches("[A-Z]{3}")) throw bad("INSIGHT_CURRENCY_INVALID", "currency 必须是三位币种代码");
        return currency;
    }
    private Timestamp ts(Instant value) { return Timestamp.from(value); }
    private DomainException bad(String code, String message) { return new DomainException(HttpStatus.BAD_REQUEST, code, message); }

    private record PeriodWindow(Instant start, Instant end, Instant dataCutoff) { }
    private record EvidenceBundle(Map<String, Object> aggregate, List<EvidenceItem> items) { }
    private record StoredReport(UUID id, String domain, String periodType, Instant periodStart, Instant periodEnd,
            Instant dataCutoff, String currency, Map<String, Object> snapshot, String title, String summary,
            List<InsightFinding> findings, List<String> cautions, String provider, String modelName,
            String promptVersion, String generationMode, UUID supersedesInsightId, Instant createdAt) { }
}
