package com.aiwei.nodus.core.device;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.identity.RequestContextResolver;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceRegistrationController {

    private final RequestContextResolver contextResolver;
    private final DeviceRegistrationService registrationService;

    public DeviceRegistrationController(
            RequestContextResolver contextResolver,
            DeviceRegistrationService registrationService) {
        this.contextResolver = contextResolver;
        this.registrationService = registrationService;
    }

    @PostMapping("/register")
    public RegisterDeviceResponse register(
            HttpServletRequest servletRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody RegisterDeviceRequest request) {
        NodusRequestContext context = contextResolver.resolve(servletRequest);
        return registrationService.register(context, idempotencyKey, request);
    }
}
