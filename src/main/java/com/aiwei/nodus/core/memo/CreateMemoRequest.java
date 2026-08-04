package com.aiwei.nodus.core.memo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMemoRequest(
        @NotBlank @Size(max = 2000) String text,
        @Size(max = 4000) String rawText) {
}
