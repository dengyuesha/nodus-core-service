package com.aiwei.nodus.core.media;

import java.time.Instant;
import java.util.UUID;

record MediaDownloadTask(
        UUID id,
        String tenantId,
        String userId,
        String title,
        String mediaType,
        Integer releaseYear,
        Integer seasonNumber,
        Integer episodeNumber,
        String episodeTitle,
        String originalFilename,
        String stagingPath,
        Long expectedSizeBytes,
        long downloadedBytes,
        String status,
        Instant createdAt) {
}
