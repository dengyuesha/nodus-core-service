package com.aiwei.nodus.core.memo;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.identity.RequestContextResolver;

@RestController
@RequestMapping("/api/v1/memos")
public class MemoController {

    private final RequestContextResolver contexts;
    private final MemoService service;

    public MemoController(RequestContextResolver contexts, MemoService service) {
        this.contexts = contexts;
        this.service = service;
    }

    @PostMapping
    public MemoResponse create(HttpServletRequest servletRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateMemoRequest request) {
        return service.create(contexts.resolve(servletRequest), key, request);
    }

    @GetMapping
    public List<MemoResponse> list(HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean includeClosed) {
        return service.list(contexts.resolve(request), includeClosed);
    }

    @PatchMapping("/{memoId}")
    public MemoResponse update(HttpServletRequest servletRequest, @PathVariable UUID memoId,
            @Valid @RequestBody UpdateMemoRequest request) {
        return service.update(contexts.resolve(servletRequest), memoId, request);
    }

    @DeleteMapping("/{memoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(HttpServletRequest request, @PathVariable UUID memoId) {
        service.delete(contexts.resolve(request), memoId);
    }
}
