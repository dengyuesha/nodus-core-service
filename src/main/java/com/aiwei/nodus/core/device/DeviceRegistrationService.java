package com.aiwei.nodus.core.device;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.audit.AuditService;
import com.aiwei.nodus.core.idempotency.IdempotencyReservation;
import com.aiwei.nodus.core.idempotency.IdempotencyService;
import com.aiwei.nodus.core.idempotency.RequestHasher;
import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.outbox.OutboxService;

@Service
public class DeviceRegistrationService {

    private static final String OPERATION = "device.register";

    private final DeviceRegistrationRepository repository;
    private final IdempotencyService idempotencyService;
    private final RequestHasher requestHasher;
    private final AuditService auditService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;

    public DeviceRegistrationService(
            DeviceRegistrationRepository repository,
            IdempotencyService idempotencyService,
            RequestHasher requestHasher,
            AuditService auditService,
            OutboxService outboxService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyService = idempotencyService;
        this.requestHasher = requestHasher;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RegisterDeviceResponse register(
            NodusRequestContext context,
            String idempotencyKey,
            RegisterDeviceRequest request) {
        validateDeviceContext(context, request.deviceId());
        IdempotencyReservation reservation = idempotencyService.reserve(
                context, OPERATION, idempotencyKey, requestHasher.hash(request));
        if (!reservation.owner()) {
            return replay(reservation);
        }

        String householdId = request.householdId() == null
                ? context.householdId()
                : request.householdId();
        DeviceRegistration registration = repository.upsert(
                context, request.deviceId(), householdId, request.displayName());
        RegisterDeviceResponse response = RegisterDeviceResponse.from(registration);

        auditService.append(context, "DEVICE_REGISTERED", "device_registration",
                registration.id().toString(), Map.of("deviceId", registration.deviceId()));
        outboxService.append(context, "device.registered", "device",
                registration.deviceId(), response);
        idempotencyService.complete(reservation.id(), HttpStatus.OK.value(), serialize(response));
        return response;
    }

    private void validateDeviceContext(NodusRequestContext context, String bodyDeviceId) {
        if (context.deviceId() != null && !context.deviceId().equals(bodyDeviceId)) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "DEVICE_CONTEXT_MISMATCH",
                    "X-Device-Id 与请求体 deviceId 不一致");
        }
    }

    private RegisterDeviceResponse replay(IdempotencyReservation reservation) {
        if (!"COMPLETED".equals(reservation.status()) || reservation.responseBody() == null) {
            throw new DomainException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS",
                    "相同请求正在处理中，请稍后重试", true,
                    Map.of("recordId", reservation.id().toString()));
        }
        try {
            return objectMapper.readValue(reservation.responseBody(), RegisterDeviceResponse.class);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "IDEMPOTENT_RESPONSE_INVALID", "历史幂等响应无法读取", true, null);
        }
    }

    private String serialize(RegisterDeviceResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "IDEMPOTENT_RESPONSE_FAILED", "幂等响应序列化失败", true, null);
        }
    }
}
