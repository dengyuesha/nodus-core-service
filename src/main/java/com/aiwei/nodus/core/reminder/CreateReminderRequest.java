package com.aiwei.nodus.core.reminder;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateReminderRequest(
        UUID memoId,
        @NotBlank @Size(max = 2000) String text,
        @NotBlank @Size(max = 64) String kind,
        @NotBlank @Size(max = 64) String timezone,
        @NotNull Instant dueAt) {
}
