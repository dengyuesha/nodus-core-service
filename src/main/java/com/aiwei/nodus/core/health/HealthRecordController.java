package com.aiwei.nodus.core.health;

import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aiwei.nodus.core.identity.RequestContextResolver;
import com.aiwei.nodus.core.ingestion.ImportBatchResponse;

/** IM 结构化健康数据导入和 Nodus 查询接口。 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthRecordController {
    private final RequestContextResolver contexts;
    private final HealthRecordService service;

    public HealthRecordController(RequestContextResolver contexts, HealthRecordService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @PostMapping("/records/import")
    public ImportBatchResponse importRecords(HttpServletRequest request, @RequestBody HealthImportRequest body) {
        return service.importRecords(contexts.resolveStructuredDataImport(request), body);
    }

    @GetMapping("/records")
    public List<HealthRecordResponse> list(HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String metricType,
            @RequestParam(defaultValue = "200") int limit) {
        return service.list(contexts.resolve(request), from, to, metricType, limit);
    }

    @GetMapping("/summary")
    public HealthSummaryResponse summary(HttpServletRequest request,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.summary(contexts.resolve(request), from, to);
    }
}
