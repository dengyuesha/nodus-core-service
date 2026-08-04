package com.aiwei.nodus.core.media;

import jakarta.validation.constraints.Min;

public record MediaDownloadProgressRequest(
        @Min(0) long downloadedBytes,
        @Min(0) Long totalBytes) {
}
