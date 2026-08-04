package com.aiwei.nodus.core.media;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.aiwei.nodus.core.api.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class MediaFileManager {

    private final MediaProperties properties;
    private final ObjectMapper objectMapper;

    public MediaFileManager(MediaProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Path prepareStaging(UUID id, String originalFilename, long libraryBytes, Long expectedSizeBytes) {
        String filename = normalizedFilename(originalFilename);
        validateExtension(filename);
        long reservation = expectedSizeBytes == null || expectedSizeBytes == 0
                ? properties.maximumFileBytes() : expectedSizeBytes;
        if (reservation > properties.maximumFileBytes()) {
            throw domain(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_FILE_TOO_LARGE", "文件超过单任务大小上限");
        }
        if (libraryBytes + reservation > properties.maximumLibraryBytes()) {
            throw domain(HttpStatus.INSUFFICIENT_STORAGE, "MEDIA_LIBRARY_QUOTA_EXCEEDED", "媒体库配额不足");
        }
        try {
            Files.createDirectories(properties.stagingDirectory());
            Files.createDirectories(properties.moviesDirectory());
            Files.createDirectories(properties.showsDirectory());
            FileStore store = Files.getFileStore(properties.stagingDirectory());
            if (store.getUsableSpace() - reservation < properties.minimumFreeBytes()) {
                throw domain(HttpStatus.INSUFFICIENT_STORAGE, "MEDIA_DISK_SPACE_LOW", "磁盘剩余空间不足");
            }
        } catch (IOException error) {
            throw domain(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_UNAVAILABLE", "媒体存储目录不可用");
        }
        return properties.stagingDirectory().resolve(id + "-" + filename).toAbsolutePath().normalize();
    }

    public String normalizedFilename(String originalFilename) {
        return safeFilename(originalFilename);
    }

    public VerificationResult verifyAndMove(MediaDownloadTask task) throws IOException {
        Path staging = Path.of(task.stagingPath()).toAbsolutePath().normalize();
        Path stagingRoot = properties.stagingDirectory().toAbsolutePath().normalize();
        if (!staging.startsWith(stagingRoot) || !Files.isRegularFile(staging)) {
            throw new MediaVerificationException("STAGING_FILE_MISSING", "下载临时文件不存在");
        }
        long size = Files.size(staging);
        if (size <= 0 || size > properties.maximumFileBytes()) {
            throw new MediaVerificationException("MEDIA_SIZE_INVALID", "下载文件大小无效");
        }
        if (task.expectedSizeBytes() != null && task.expectedSizeBytes() > 0
                && size != task.expectedSizeBytes()) {
            throw new MediaVerificationException("MEDIA_SIZE_MISMATCH", "下载文件大小与浏览器报告不一致");
        }
        validateExtension(task.originalFilename());
        MediaProbe probe = probe(staging);
        if (probe.videoCodec() == null || probe.videoCodec().isBlank()) {
            throw new MediaVerificationException("MEDIA_VIDEO_STREAM_MISSING", "文件中没有可播放的视频流");
        }
        String sha256 = sha256(staging);
        Path destination = destination(task, extension(task.originalFilename()));
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) {
            destination = uniqueDestination(destination, task.id());
        }
        try {
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(staging, destination);
        }
        return new VerificationResult(destination, size, sha256, probe);
    }

    public void restoreToStaging(Path destination, Path staging) {
        try {
            if (Files.exists(destination) && !Files.exists(staging)) {
                Files.move(destination, staging);
            }
        } catch (IOException ignored) {
            // 后续人工恢复时仍可从目标目录找到文件。
        }
    }

    public void deletePartial(String stagingPath) {
        try {
            Path path = Path.of(stagingPath).toAbsolutePath().normalize();
            if (path.startsWith(properties.stagingDirectory().toAbsolutePath().normalize())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // 取消动作以数据库状态为准，残留临时文件可由运维清理。
        }
    }

    public long availableBytes() {
        try {
            Files.createDirectories(properties.stagingDirectory());
            return Files.getFileStore(properties.stagingDirectory()).getUsableSpace();
        } catch (IOException error) {
            return 0;
        }
    }

    private MediaProbe probe(Path file) throws IOException {
        Process process = new ProcessBuilder(
                properties.ffprobePath(), "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", file.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished;
        try {
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new MediaVerificationException("FFPROBE_INTERRUPTED", "媒体检测被中断");
        }
        if (!finished) {
            process.destroyForcibly();
            throw new MediaVerificationException("FFPROBE_TIMEOUT", "媒体检测超时");
        }
        String output;
        try (InputStream stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (process.exitValue() != 0) {
            throw new MediaVerificationException("FFPROBE_FAILED", "文件不是有效媒体：" + trim(output, 300));
        }
        JsonNode root = objectMapper.readTree(output);
        JsonNode video = null;
        JsonNode audio = null;
        for (JsonNode stream : root.path("streams")) {
            if (video == null && "video".equals(stream.path("codec_type").asText())) {
                video = stream;
            }
            if (audio == null && "audio".equals(stream.path("codec_type").asText())) {
                audio = stream;
            }
        }
        String durationText = root.path("format").path("duration").asText("");
        Long duration = null;
        try {
            if (!durationText.isBlank()) {
                duration = new BigDecimal(durationText).setScale(0, RoundingMode.HALF_UP).longValueExact();
            }
        } catch (ArithmeticException ignored) {
            duration = null;
        }
        return new MediaProbe(
                root.path("format").path("format_name").asText(null),
                video == null ? null : video.path("codec_name").asText(null),
                audio == null ? null : audio.path("codec_name").asText(null),
                duration,
                video == null ? null : boxed(video.path("width")),
                video == null ? null : boxed(video.path("height")));
    }

    private String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file); DigestInputStream stream = new DigestInputStream(input, digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private Path destination(MediaDownloadTask task, String extension) {
        String title = safeTitle(task.title());
        String datedTitle = task.releaseYear() == null ? title : title + " (" + task.releaseYear() + ")";
        if ("MOVIE".equals(task.mediaType())) {
            return properties.moviesDirectory().resolve(datedTitle).resolve(datedTitle + "." + extension)
                    .toAbsolutePath().normalize();
        }
        int season = task.seasonNumber() == null ? 1 : task.seasonNumber();
        int episode = task.episodeNumber() == null ? 1 : task.episodeNumber();
        String episodeTitle = task.episodeTitle() == null || task.episodeTitle().isBlank()
                ? "" : " " + safeTitle(task.episodeTitle());
        String filename = datedTitle + " S%02dE%02d%s.%s".formatted(season, episode, episodeTitle, extension);
        return properties.showsDirectory().resolve(datedTitle).resolve("Season %02d".formatted(season))
                .resolve(filename).toAbsolutePath().normalize();
    }

    private Path uniqueDestination(Path original, UUID id) {
        String filename = original.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        String prefix = dot < 0 ? filename : filename.substring(0, dot);
        String suffix = dot < 0 ? "" : filename.substring(dot);
        return original.resolveSibling(prefix + " - " + id.toString().substring(0, 8) + suffix);
    }

    private String safeFilename(String value) {
        String name = Path.of(value).getFileName().toString();
        String safe = Normalizer.normalize(name, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_")
                .replaceAll("\\s+", " ").trim();
        if (safe.isBlank() || safe.length() > 240) {
            throw domain(HttpStatus.BAD_REQUEST, "MEDIA_FILENAME_INVALID", "下载文件名无效");
        }
        return safe;
    }

    private String safeTitle(String value) {
        String safe = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_")
                .replaceAll("\\s+", " ").trim();
        return safe.isBlank() ? "Untitled" : trim(safe, 180);
    }

    private void validateExtension(String filename) {
        String extension = extension(filename);
        if (!properties.allowedExtensions().contains(extension)) {
            throw domain(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_EXTENSION_UNSUPPORTED", "不支持这个视频文件格式");
        }
    }

    private String extension(String filename) {
        int index = filename.lastIndexOf('.');
        return index < 0 ? "" : filename.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private Integer boxed(JsonNode value) {
        return value.isIntegralNumber() ? value.asInt() : null;
    }

    private String trim(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private DomainException domain(HttpStatus status, String code, String message) {
        return new DomainException(status, code, message);
    }

    public record VerificationResult(Path destination, long size, String sha256, MediaProbe probe) {
    }

    static class MediaVerificationException extends IOException {
        private final String code;

        MediaVerificationException(String code, String message) {
            super(message);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
