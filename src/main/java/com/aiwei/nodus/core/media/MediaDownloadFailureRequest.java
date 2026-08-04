package com.aiwei.nodus.core.media;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MediaDownloadFailureRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 1000) String message) {
}
