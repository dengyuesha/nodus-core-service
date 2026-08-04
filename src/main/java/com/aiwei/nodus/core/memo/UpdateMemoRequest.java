package com.aiwei.nodus.core.memo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateMemoRequest(
        @Size(min = 1, max = 2000) String text,
        @Pattern(regexp = "OPEN|COMPLETED") String status,
        @NotNull @Min(1) Integer version) {
}
