package com.aiwei.nodus.core.reminder;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeliveryFailureRequest(@NotBlank @Size(max = 1000) String error) {
}
