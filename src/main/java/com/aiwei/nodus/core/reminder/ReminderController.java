package com.aiwei.nodus.core.reminder;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.identity.RequestContextResolver;

@RestController
@RequestMapping("/api/v1")
public class ReminderController {

    private final RequestContextResolver contexts;
    private final ReminderService reminders;
    private final ReminderDeliveryService deliveries;

    public ReminderController(RequestContextResolver contexts, ReminderService reminders,
            ReminderDeliveryService deliveries) {
        this.contexts = contexts;
        this.reminders = reminders;
        this.deliveries = deliveries;
    }

    @PostMapping("/reminders")
    public ReminderResponse create(HttpServletRequest servletRequest,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody CreateReminderRequest request) {
        return reminders.create(contexts.resolve(servletRequest), key, request);
    }

    @GetMapping("/reminders")
    public List<ReminderResponse> list(HttpServletRequest request,
            @RequestParam(defaultValue = "false") boolean includeTerminal) {
        return reminders.list(contexts.resolve(request), includeTerminal);
    }

    @PostMapping("/reminders/{reminderId}/cancel")
    public ReminderResponse cancel(HttpServletRequest request, @PathVariable UUID reminderId) {
        return reminders.cancel(contexts.resolve(request), reminderId);
    }

    @PostMapping("/reminder-deliveries/claim")
    public List<DeliveryResponse> claim(HttpServletRequest request,
            @Valid @RequestBody(required = false) DeliveryClaimRequest body) {
        NodusRequestContext context = contexts.resolve(request);
        return deliveries.claim(context, body == null ? 20 : body.effectiveLimit());
    }

    @PostMapping("/reminder-deliveries/claim-tenant")
    public List<DeliveryResponse> claimTenant(HttpServletRequest request,
            @Valid @RequestBody(required = false) DeliveryClaimRequest body) {
        NodusRequestContext context = contexts.resolve(request);
        return deliveries.claimTenant(context, body == null ? 20 : body.effectiveLimit());
    }

    @PostMapping("/reminder-deliveries/{eventId}/ack")
    public void acknowledge(HttpServletRequest request, @PathVariable String eventId,
            @Valid @RequestBody DeliveryAckRequest body) {
        deliveries.acknowledge(contexts.resolve(request), eventId, body.source());
    }

    @PostMapping("/reminder-deliveries/{eventId}/fail")
    public void fail(HttpServletRequest request, @PathVariable String eventId,
            @Valid @RequestBody DeliveryFailureRequest body) {
        deliveries.fail(contexts.resolve(request), eventId, body.error());
    }
}
