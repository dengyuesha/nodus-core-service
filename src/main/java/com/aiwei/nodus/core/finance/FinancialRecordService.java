package com.aiwei.nodus.core.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aiwei.nodus.core.audit.AuditService;
import com.aiwei.nodus.core.idempotency.RequestHasher;
import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.ingestion.ImportBatchResponse;
import com.aiwei.nodus.core.ingestion.ImportItemResult;

/** 财务结构化记录的逐条校验、来源幂等写入、明细查询和阶段聚合。 */
@Service
public class FinancialRecordService {
    private static final Set<String> TYPES = Set.of("INCOME", "EXPENSE", "ASSET_BALANCE", "LIABILITY_BALANCE");
    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("FOOD", "餐饮"), Map.entry("DINING", "餐饮"),
            Map.entry("TRANSPORT", "交通"), Map.entry("TRANSPORTATION", "交通"),
            Map.entry("SHOPPING", "购物"), Map.entry("SALARY", "工资"),
            Map.entry("HOUSING", "住房"), Map.entry("UTILITIES", "水电煤"),
            Map.entry("ENTERTAINMENT", "娱乐"), Map.entry("HEALTHCARE", "医疗"),
            Map.entry("EDUCATION", "教育"), Map.entry("TRAVEL", "旅行"),
            Map.entry("INSURANCE", "保险"), Map.entry("OTHER", "其他"));
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RequestHasher hasher;
    private final AuditService audit;
    private final Clock clock;

    public FinancialRecordService(JdbcTemplate jdbc, ObjectMapper mapper, RequestHasher hasher,
            AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ImportBatchResponse importRecords(NodusRequestContext context, FinancialImportRequest request) {
        if (request == null || blank(request.sourceSystem()) || request.records() == null) {
            return ImportBatchResponse.from(List.of(ImportItemResult.rejected(null,
                    "IMPORT_REQUEST_INVALID", "sourceSystem 和 records 必填")));
        }
        if (request.sourceSystem().trim().length() > 128) {
            return ImportBatchResponse.from(List.of(ImportItemResult.rejected(null,
                    "IMPORT_SOURCE_INVALID", "sourceSystem 长度不能超过 128")));
        }
        if (request.records().size() > 500) {
            return ImportBatchResponse.from(List.of(ImportItemResult.rejected(null,
                    "IMPORT_BATCH_TOO_LARGE", "单批最多 500 条")));
        }
        List<ImportItemResult> results = new ArrayList<>();
        for (FinancialRecordInput input : request.records()) {
            results.add(importOne(context, request.sourceSystem().trim(), input));
        }
        return ImportBatchResponse.from(results);
    }

    private ImportItemResult importOne(NodusRequestContext context, String source, FinancialRecordInput input) {
        String error = validate(input);
        if (error != null) return ImportItemResult.rejected(input == null ? null : input.sourceRecordId(),
                "FINANCIAL_RECORD_INVALID", error);
        String type = input.recordType().trim().toUpperCase(Locale.ROOT);
        String currency = input.currency().trim().toUpperCase(Locale.ROOT);
        String category = normalizeCategory(input.category());
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourceRecordId", input.sourceRecordId().trim());
        canonical.put("recordType", type);
        canonical.put("amount", input.amount());
        canonical.put("currency", currency);
        canonical.put("category", category);
        canonical.put("account", trim(input.account()));
        canonical.put("description", trim(input.description()));
        canonical.put("occurredAt", input.occurredAt());
        canonical.put("metadata", input.metadata() == null ? Map.of() : input.metadata());
        String hash = hasher.hash(canonical);
        Existing existing = existing(context, source, input.sourceRecordId());
        if (existing != null) {
            return existing.hash().equals(hash)
                    ? ImportItemResult.replayed(input.sourceRecordId(), existing.id())
                    : ImportItemResult.rejected(input.sourceRecordId(), "SOURCE_RECORD_CONFLICT",
                            "相同 sourceRecordId 的内容不同");
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now(clock);
        jdbc.update("""
                insert into financial_record (id, tenant_id, user_id, source_system, source_record_id,
                    record_type, amount, currency, category, account_name, description, occurred_at,
                    metadata, content_hash, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, context.tenantId(), context.userId(), source, input.sourceRecordId().trim(),
                type, input.amount(), currency, category, trim(input.account()),
                trim(input.description()), Timestamp.from(input.occurredAt()), json(input.metadata()), hash,
                Timestamp.from(now), Timestamp.from(now));
        audit.append(context, "FINANCIAL_RECORD_IMPORTED", "financial_record", id.toString(),
                Map.of("sourceSystem", source, "sourceRecordId", input.sourceRecordId(), "recordType", type));
        return ImportItemResult.created(input.sourceRecordId(), id);
    }

    public List<FinancialRecordResponse> list(NodusRequestContext context, Instant from, Instant to,
            String recordType, String currency, int limit) {
        Instant effectiveTo = to == null ? Instant.now(clock) : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(365, ChronoUnit.DAYS) : from;
        String typeClause = blank(recordType) ? "" : " and record_type = ?";
        String currencyClause = blank(currency) ? "" : " and currency = ?";
        List<Object> args = new ArrayList<>(List.of(context.tenantId(), context.userId(),
                Timestamp.from(effectiveFrom), Timestamp.from(effectiveTo)));
        if (!blank(recordType)) args.add(recordType.trim().toUpperCase(Locale.ROOT));
        if (!blank(currency)) args.add(currency.trim().toUpperCase(Locale.ROOT));
        args.add(Math.max(1, Math.min(limit, 2000)));
        return jdbc.query("""
                select id, source_system, source_record_id, record_type, amount, currency, category,
                    account_name, description, occurred_at, metadata, created_at from financial_record
                 where tenant_id = ? and user_id = ? and occurred_at >= ? and occurred_at <= ?
                """ + typeClause + currencyClause + " order by occurred_at desc limit ?", (rs, row) ->
                new FinancialRecordResponse(rs.getObject("id", UUID.class), rs.getString("source_system"),
                        rs.getString("source_record_id"), rs.getString("record_type"), rs.getBigDecimal("amount"),
                        rs.getString("currency"), rs.getString("category"), rs.getString("account_name"),
                        rs.getString("description"), rs.getTimestamp("occurred_at").toInstant(),
                        map(rs.getString("metadata")), rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    public FinancialSummaryResponse summary(NodusRequestContext context, Instant from, Instant to, String currency) {
        Instant effectiveTo = to == null ? Instant.now(clock) : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(180, ChronoUnit.DAYS) : from;
        String effectiveCurrency = blank(currency) ? "CNY" : currency.trim().toUpperCase(Locale.ROOT);
        List<FinancialRecordResponse> rows = list(context, effectiveFrom, effectiveTo, null, effectiveCurrency, 2000);
        BigDecimal income = sum(rows, "INCOME");
        BigDecimal expense = sum(rows, "EXPENSE");
        Map<String, FinancialRecordResponse> latestBalances = new LinkedHashMap<>();
        rows.stream().filter(row -> row.recordType().endsWith("_BALANCE"))
                .sorted(Comparator.comparing(FinancialRecordResponse::occurredAt))
                .forEach(row -> latestBalances.put(row.recordType() + ":" + account(row), row));
        BigDecimal assets = latestBalances.values().stream().filter(row -> "ASSET_BALANCE".equals(row.recordType()))
                .map(FinancialRecordResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal liabilities = latestBalances.values().stream().filter(row -> "LIABILITY_BALANCE".equals(row.recordType()))
                .map(FinancialRecordResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> categories = new LinkedHashMap<>();
        rows.stream().filter(row -> "EXPENSE".equals(row.recordType())).forEach(row ->
                categories.merge(blank(row.category()) ? "未分类" : row.category(), row.amount(), BigDecimal::add));
        Map<YearMonth, BigDecimal[]> months = new java.util.TreeMap<>();
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        rows.stream().filter(row -> "INCOME".equals(row.recordType()) || "EXPENSE".equals(row.recordType()))
                .forEach(row -> {
                    YearMonth month = YearMonth.from(row.occurredAt().atZone(zone));
                    BigDecimal[] values = months.computeIfAbsent(month, key -> new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO });
                    values["INCOME".equals(row.recordType()) ? 0 : 1] =
                            values["INCOME".equals(row.recordType()) ? 0 : 1].add(row.amount());
                });
        List<FinancialSummaryResponse.MonthlyCashFlow> monthly = months.entrySet().stream()
                .map(entry -> new FinancialSummaryResponse.MonthlyCashFlow(entry.getKey(), entry.getValue()[0],
                        entry.getValue()[1], entry.getValue()[0].subtract(entry.getValue()[1]))).toList();
        BigDecimal savingsRate = income.signum() == 0 ? BigDecimal.ZERO
                : income.subtract(expense).multiply(BigDecimal.valueOf(100))
                        .divide(income, 2, RoundingMode.HALF_UP);
        return new FinancialSummaryResponse(effectiveFrom, effectiveTo, effectiveCurrency, income, expense,
                income.subtract(expense), savingsRate, assets, liabilities, assets.subtract(liabilities),
                categories, monthly);
    }

    private BigDecimal sum(List<FinancialRecordResponse> rows, String type) {
        return rows.stream().filter(row -> type.equals(row.recordType())).map(FinancialRecordResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String account(FinancialRecordResponse row) {
        return blank(row.account()) ? row.sourceSystem() + ":" + row.sourceRecordId() : row.account();
    }

    private Existing existing(NodusRequestContext context, String source, String sourceRecordId) {
        List<Existing> rows = jdbc.query("""
                select id, content_hash from financial_record where tenant_id = ? and user_id = ?
                    and source_system = ? and source_record_id = ?
                """, (rs, row) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2)),
                context.tenantId(), context.userId(), source, sourceRecordId.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String validate(FinancialRecordInput input) {
        if (input == null) return "记录不能为空";
        if (blank(input.sourceRecordId())) return "sourceRecordId 必填";
        if (blank(input.recordType()) || !TYPES.contains(input.recordType().trim().toUpperCase(Locale.ROOT))) {
            return "recordType 仅支持 INCOME、EXPENSE、ASSET_BALANCE、LIABILITY_BALANCE";
        }
        if (input.amount() == null || input.amount().signum() < 0) return "amount 必须是非负数";
        if (blank(input.currency()) || !input.currency().matches("[A-Za-z]{3}")) return "currency 必须是三位币种代码";
        if (input.occurredAt() == null) return "occurredAt 必填";
        if (input.sourceRecordId().length() > 256) return "sourceRecordId 长度超限";
        if (input.category() != null && input.category().trim().length() > 128) return "category 长度超限";
        if (input.account() != null && input.account().trim().length() > 256) return "account 长度超限";
        if (input.description() != null && input.description().trim().length() > 1000) return "description 长度超限";
        return null;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? Map.of() : value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("metadata 无法序列化", error); }
    }

    private Map<String, Object> map(String value) {
        try { return mapper.readValue(value, MAP_TYPE); }
        catch (JsonProcessingException error) { return Map.of(); }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String trim(String value) { return blank(value) ? null : value.trim(); }
    private String normalizeCategory(String value) {
        String category = trim(value);
        if (category == null) return null;
        String code = category.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        return CATEGORY_NAMES.getOrDefault(code, category);
    }
    private record Existing(UUID id, String hash) { }
}
