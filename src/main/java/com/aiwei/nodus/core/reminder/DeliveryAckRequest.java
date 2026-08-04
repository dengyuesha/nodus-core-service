package com.aiwei.nodus.core.reminder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeliveryAckRequest(@NotBlank @Size(max = 128) String source) {
}
