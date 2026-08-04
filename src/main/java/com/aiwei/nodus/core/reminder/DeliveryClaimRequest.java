package com.aiwei.nodus.core.reminder;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record DeliveryClaimRequest(@Min(1) @Max(100) Integer limit) {

    public int effectiveLimit() {
        return limit == null ? 20 : limit;
    }
}
