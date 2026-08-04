package com.aiwei.nodus.core.media;

import java.time.Instant;
import java.util.UUID;

public record MediaAssetResponse(
        UUID mediaId,
        UUID downloadId,
        String title,
        String mediaType,
        Integer releaseYear,
        Integer seasonNumber,
        Integer episodeNumber,
        String episodeTitle,
        long fileSizeBytes,
        String sha256,
        String container,
        String videoCodec,
        String audioCodec,
        Long durationSeconds,
        Integer width,
        Integer height,
        String jellyfinItemId,
        String jellyfinSyncStatus,
        String posterUrl,
        String streamUrl,
        Instant createdAt) {
}
