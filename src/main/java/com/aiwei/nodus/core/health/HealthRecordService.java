package com.aiwei.nodus.core.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/** 健康结构化记录的逐条校验、来源幂等写入、明细查询和阶段聚合。 */
@Service
public class HealthRecordService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final RequestHasher hasher;
    private final AuditService audit;
    private final Clock clock;

    public HealthRecordService(JdbcTemplate jdbc, ObjectMapper mapper, RequestHasher hasher,
            AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ImportBatchResponse importRecords(NodusRequestContext context, HealthImportRequest request) {
        List<ImportItemResult> results = new ArrayList<>();
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
        for (HealthRecordInput input : request.records()) {
            results.add(importOne(context, request.sourceSystem().trim(), input));
        }
        return ImportBatchResponse.from(results);
    }

    private ImportItemResult importOne(NodusRequestContext context, String source, HealthRecordInput input) {
        String error = validate(input);
        if (error != null) return ImportItemResult.rejected(input == null ? null : input.sourceRecordId(),
                "HEALTH_RECORD_INVALID", error);
        String hash = hasher.hash(Map.of(
                "sourceRecordId", input.sourceRecordId().trim(),
                "metricType", input.metricType().trim().toLowerCase(),
                "value", input.value(),
                "unit", input.unit().trim(),
                "measuredAt", input.measuredAt(),
                "metadata", input.metadata() == null ? Map.of() : input.metadata()));
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
                insert into health_record (id, tenant_id, user_id, source_system, source_record_id,
                    metric_type, metric_value, unit, measured_at, metadata, content_hash, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, context.tenantId(), context.userId(), source, input.sourceRecordId().trim(),
                input.metricType().trim().toLowerCase(), input.value(), input.unit().trim(),
                Timestamp.from(input.measuredAt()), json(input.metadata()), hash, Timestamp.from(now), Timestamp.from(now));
        audit.append(context, "HEALTH_RECORD_IMPORTED", "health_record", id.toString(),
                Map.of("sourceSystem", source, "sourceRecordId", input.sourceRecordId()));
        return ImportItemResult.created(input.sourceRecordId(), id);
    }

    public List<HealthRecordResponse> list(NodusRequestContext context, Instant from, Instant to,
            String metricType, int limit) {
        Instant effectiveTo = to == null ? Instant.now(clock) : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(90, ChronoUnit.DAYS) : from;
        String metricClause = blank(metricType) ? "" : " and metric_type = ?";
        List<Object> args = new ArrayList<>(List.of(context.tenantId(), context.userId(),
                Timestamp.from(effectiveFrom), Timestamp.from(effectiveTo)));
        if (!blank(metricType)) args.add(metricType.trim().toLowerCase());
        args.add(Math.max(1, Math.min(limit, 1000)));
        return jdbc.query("""
                select id, source_system, source_record_id, metric_type, metric_value, unit,
                    measured_at, metadata, created_at from health_record
                 where tenant_id = ? and user_id = ? and measured_at >= ? and measured_at <= ?
                """ + metricClause + " order by measured_at desc limit ?", (rs, row) ->
                new HealthRecordResponse(rs.getObject("id", UUID.class), rs.getString("source_system"),
                        rs.getString("source_record_id"), rs.getString("metric_type"),
                        rs.getBigDecimal("metric_value"), rs.getString("unit"),
                        rs.getTimestamp("measured_at").toInstant(), map(rs.getString("metadata")),
                        rs.getTimestamp("created_at").toInstant()), args.toArray());
    }

    public HealthSummaryResponse summary(NodusRequestContext context, Instant from, Instant to) {
        Instant effectiveTo = to == null ? Instant.now(clock) : to;
        Instant effectiveFrom = from == null ? effectiveTo.minus(30, ChronoUnit.DAYS) : from;
        List<HealthRecordResponse> rows = list(context, effectiveFrom, effectiveTo, null, 1000);
        Map<String, List<HealthRecordResponse>> grouped = new LinkedHashMap<>();
        rows.stream().sorted(java.util.Comparator.comparing(HealthRecordResponse::measuredAt))
                .forEach(row -> grouped.computeIfAbsent(row.metricType(), key -> new ArrayList<>()).add(row));
        Map<String, HealthSummaryResponse.MetricSummary> metrics = new LinkedHashMap<>();
        grouped.forEach((type, values) -> {
            BigDecimal sum = values.stream().map(HealthRecordResponse::value).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal min = values.stream().map(HealthRecordResponse::value).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal max = values.stream().map(HealthRecordResponse::value).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            HealthRecordResponse latest = values.get(values.size() - 1);
            List<HealthSummaryResponse.Point> series = values.stream()
                    .map(value -> new HealthSummaryResponse.Point(value.measuredAt(), value.value())).toList();
            metrics.put(type, new HealthSummaryResponse.MetricSummary(latest.value(),
                    sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP), min, max,
                    latest.unit(), latest.measuredAt(), series));
        });
        return new HealthSummaryResponse(effectiveFrom, effectiveTo, metrics);
    }

    private Existing existing(NodusRequestContext context, String source, String sourceRecordId) {
        List<Existing> rows = jdbc.query("""
                select id, content_hash from health_record where tenant_id = ? and user_id = ?
                    and source_system = ? and source_record_id = ?
                """, (rs, row) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2)),
                context.tenantId(), context.userId(), source, sourceRecordId.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String validate(HealthRecordInput input) {
        if (input == null) return "记录不能为空";
        if (blank(input.sourceRecordId())) return "sourceRecordId 必填";
        if (blank(input.metricType())) return "metricType 必填";
        if (input.value() == null) return "value 必填";
        if (blank(input.unit())) return "unit 必填";
        if (input.measuredAt() == null) return "measuredAt 必填";
        if (input.sourceRecordId().length() > 256 || input.metricType().length() > 128 || input.unit().length() > 64) {
            return "字段长度超限";
        }
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
    private record Existing(UUID id, String hash) { }
}
