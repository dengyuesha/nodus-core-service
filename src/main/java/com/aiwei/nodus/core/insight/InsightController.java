package com.aiwei.nodus.core.insight;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import com.aiwei.nodus.core.identity.RequestContextResolver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
public class InsightController {
    private final InsightService service;
    private final RequestContextResolver contexts;

    public InsightController(InsightService service, RequestContextResolver contexts) {
        this.service = service;
        this.contexts = contexts;
    }

    @PostMapping("/generate")
    public InsightResponse generate(HttpServletRequest servletRequest, @RequestBody InsightGenerateRequest request) {
        return service.generate(contexts.resolve(servletRequest), request);
    }

    @GetMapping
    public List<InsightResponse> list(HttpServletRequest request,
            @RequestParam(required = false) String domain,
            @RequestParam(defaultValue = "20") int limit) {
        return service.list(contexts.resolve(request), domain, limit);
    }

    @GetMapping("/{insightId}")
    public InsightResponse get(HttpServletRequest request, @PathVariable UUID insightId) {
        return service.get(contexts.resolve(request), insightId);
    }

    @PostMapping("/{insightId}/feedback")
    public InsightFeedbackResponse feedback(HttpServletRequest servletRequest, @PathVariable UUID insightId,
            @RequestBody InsightFeedbackRequest request) {
        return service.feedback(contexts.resolve(servletRequest), insightId, request);
    }

    @PostMapping("/{insightId}/questions")
    public InsightFollowUpResponse ask(HttpServletRequest servletRequest, @PathVariable UUID insightId,
            @RequestBody InsightQuestionRequest request) {
        return service.ask(contexts.resolve(servletRequest), insightId, request);
    }

    @PostMapping("/{insightId}/regenerate")
    public InsightResponse regenerate(HttpServletRequest request, @PathVariable UUID insightId) {
        return service.regenerate(contexts.resolve(request), insightId);
    }
}
