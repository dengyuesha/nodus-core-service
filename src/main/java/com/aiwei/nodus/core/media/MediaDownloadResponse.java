package com.aiwei.nodus.core.media;

import java.time.Instant;
import java.util.UUID;

public record MediaDownloadResponse(
        UUID downloadId,
        String title,
        String mediaType,
        Integer releaseYear,
        Integer seasonNumber,
        Integer episodeNumber,
        String episodeTitle,
        String sourceProvider,
        String sourceUrl,
        String originalFilename,
        String stagingPath,
        Long expectedSizeBytes,
        long downloadedBytes,
        String status,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
