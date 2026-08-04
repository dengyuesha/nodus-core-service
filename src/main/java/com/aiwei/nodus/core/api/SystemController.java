package com.aiwei.nodus.core.api;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.identity.RequestContextResolver;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final RequestContextResolver contextResolver;

    public SystemController(RequestContextResolver contextResolver) {
        this.contextResolver = contextResolver;
    }

    @GetMapping("/context")
    public NodusRequestContext context(HttpServletRequest request) {
        return contextResolver.resolve(request);
    }
}
