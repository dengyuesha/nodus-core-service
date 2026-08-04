package com.aiwei.nodus.core.memo;

import java.time.Clock;
import java.time.Instant;
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
public class MemoService {

    private final MemoRepository repository;
    private final IdempotencyService idempotency;
    private final RequestHasher hasher;
    private final AuditService audit;
    private final OutboxService outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MemoService(MemoRepository repository, IdempotencyService idempotency, RequestHasher hasher,
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
    public MemoResponse create(NodusRequestContext context, String key, CreateMemoRequest request) {
        IdempotencyReservation reservation = idempotency.reserve(context, "memo.create", key, hasher.hash(request));
        if (!reservation.owner()) {
            return replay(reservation);
        }
        MemoResponse response = repository.insert(context, request, Instant.now(clock));
        audit.append(context, "MEMO_CREATED", "memo", response.memoId().toString(), Map.of("text", response.text()));
        outbox.append(context, "memo.created", "memo", response.memoId().toString(), response);
        idempotency.complete(reservation.id(), 200, json(response));
        return response;
    }

    public List<MemoResponse> list(NodusRequestContext context, boolean includeClosed) {
        return repository.list(context, includeClosed);
    }

    @Transactional
    public MemoResponse update(NodusRequestContext context, UUID id, UpdateMemoRequest request) {
        if (request.text() == null && request.status() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "MEMO_UPDATE_EMPTY", "至少提供 text 或 status");
        }
        int updated = repository.update(context, id, request, Instant.now(clock));
        if (updated == 0) {
            throw new DomainException(HttpStatus.CONFLICT, "MEMO_VERSION_CONFLICT", "备忘录不存在或版本已变化");
        }
        MemoResponse response = repository.find(context, id).orElseThrow();
        audit.append(context, "MEMO_UPDATED", "memo", id.toString(), Map.of("version", response.version()));
        return response;
    }

    @Transactional
    public void delete(NodusRequestContext context, UUID id) {
        if (repository.softDelete(context, id, Instant.now(clock)) == 0) {
            throw new DomainException(HttpStatus.NOT_FOUND, "MEMO_NOT_FOUND", "备忘录不存在");
        }
        audit.append(context, "MEMO_DELETED", "memo", id.toString(), Map.of());
    }

    private MemoResponse replay(IdempotencyReservation reservation) {
        if (!"COMPLETED".equals(reservation.status()) || reservation.responseBody() == null) {
            throw new DomainException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "相同请求正在处理中", true, Map.of());
        }
        try {
            return objectMapper.readValue(reservation.responseBody(), MemoResponse.class);
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
