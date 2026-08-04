package com.aiwei.nodus.core.media;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("nodus.core.media")
public record MediaProperties(
        Path stagingDirectory,
        Path moviesDirectory,
        Path showsDirectory,
        long minimumFreeBytes,
        long maximumLibraryBytes,
        long maximumFileBytes,
        String ffprobePath,
        Duration verificationInterval,
        String jellyfinBaseUrl,
        String jellyfinApiKey,
        Duration jellyfinTimeout,
        List<String> allowedExtensions) {

    public MediaProperties {
        stagingDirectory = stagingDirectory == null
                ? Path.of("/home/aidlux/Videos/NodusMedia/staging") : stagingDirectory;
        moviesDirectory = moviesDirectory == null
                ? Path.of("/home/aidlux/Videos/NodusMedia/Movies") : moviesDirectory;
        showsDirectory = showsDirectory == null
                ? Path.of("/home/aidlux/Videos/NodusMedia/TV") : showsDirectory;
        minimumFreeBytes = positive(minimumFreeBytes, 10L * 1024 * 1024 * 1024);
        maximumLibraryBytes = positive(maximumLibraryBytes, 10L * 1024 * 1024 * 1024);
        maximumFileBytes = positive(maximumFileBytes, 8L * 1024 * 1024 * 1024);
        ffprobePath = text(ffprobePath, "/usr/bin/ffprobe");
        verificationInterval = verificationInterval == null ? Duration.ofSeconds(2) : verificationInterval;
        jellyfinBaseUrl = text(jellyfinBaseUrl, "http://127.0.0.1:8096").replaceAll("/+$", "");
        jellyfinApiKey = jellyfinApiKey == null ? "" : jellyfinApiKey.trim();
        jellyfinTimeout = jellyfinTimeout == null ? Duration.ofSeconds(5) : jellyfinTimeout;
        allowedExtensions = allowedExtensions == null || allowedExtensions.isEmpty()
                ? List.of("mp4", "mkv", "m4v", "mov", "webm", "avi", "ts")
                : allowedExtensions.stream().map(String::trim).map(String::toLowerCase).distinct().toList();
    }

    private static long positive(long value, long fallback) {
        return value <= 0 ? fallback : value;
    }

    private static String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
