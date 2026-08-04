package com.aiwei.nodus.core.reminder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.zone.ZoneRulesException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
public class ReminderService {

    private final ReminderRepository repository;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReminderService(ReminderRepository repository, IdempotencyService idempotency, RequestHasher hasher,
            AuditService audit, OutboxService outbox, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.hasher = hasher;
        this.audit = audit;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ReminderResponse create(NodusRequestContext context, String key, CreateReminderRequest request) {
        validateTimezone(request.timezone());
        IdempotencyReservation reservation = idempotency.reserve(context, "reminder.create", key, hasher.hash(request));
        if (!reservation.owner()) {
            return replay(reservation);
        }
        ReminderResponse response = repository.insert(context, request, Instant.now(clock));
        audit.append(context, "REMINDER_CREATED", "reminder", response.reminderId().toString(),
                Map.of("dueAt", response.dueAt().toString(), "kind", response.kind()));
        outbox.append(context, "reminder.created", "reminder", response.reminderId().toString(), response);
        idempotency.complete(reservation.id(), 200, json(response));
        return response;
    }

    public List<ReminderResponse> list(NodusRequestContext context, boolean includeTerminal) {
        return repository.list(context, includeTerminal);
    }

    @Transactional
    public ReminderResponse cancel(NodusRequestContext context, UUID id) {
        if (repository.cancel(context, id, Instant.now(clock)) == 0) {
            throw new DomainException(HttpStatus.CONFLICT, "REMINDER_NOT_CANCELLABLE", "提醒不存在或当前状态不可取消");
        }
        ReminderResponse response = repository.find(context, id).orElseThrow();
        audit.append(context, "REMINDER_CANCELLED", "reminder", id.toString(), Map.of());
        return response;
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (ZoneRulesException exception) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "TIMEZONE_INVALID", "无效的时区");
        }
    }

    private ReminderResponse replay(IdempotencyReservation reservation) {
        if (!"COMPLETED".equals(reservation.status()) || reservation.responseBody() == null) {
            throw new DomainException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中", true, Map.of());
        }
        try {
            return objectMapper.readValue(reservation.responseBody(), ReminderResponse.class);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR, "IDEMPOTENT_RESPONSE_INVALID", "历史响应无法读取");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new DomainException(HttpStatus.INTERNAL_SERVER_ERROR, "RESPONSE_SERIALIZATION_FAILED", "响应序列化失败");
        }
    }
}
