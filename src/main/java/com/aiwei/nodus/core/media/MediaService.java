package com.aiwei.nodus.core.media;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.audit.AuditService;
import com.aiwei.nodus.core.identity.NodusRequestContext;
import com.aiwei.nodus.core.media.MediaFileManager.MediaVerificationException;
import com.aiwei.nodus.core.media.MediaFileManager.VerificationResult;
import com.aiwei.nodus.core.outbox.OutboxService;

@Service
public class MediaService {

    private static final Set<String> SHARE_HOSTS = Set.of(
            "alipan.com", "aliyundrive.com", "pan.baidu.com", "pan.quark.cn", "pan.xunlei.com",
            "cloud.189.cn", "caiyun.139.com", "115.com", "123pan.com", "123684.com", "drive.uc.cn");

    private final MediaRepository repository;
    private final MediaFileManager files;
    private final MediaProperties properties;
    private final JellyfinClient jellyfin;
    private final AuditService audit;
    private final OutboxService outbox;
    private final Clock clock;

    public MediaService(
            MediaRepository repository,
            MediaFileManager files,
            MediaProperties properties,
            JellyfinClient jellyfin,
            AuditService audit,
            OutboxService outbox,
            Clock clock) {
        this.repository = repository;
        this.files = files;
        this.properties = properties;
        this.jellyfin = jellyfin;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public MediaDownloadResponse create(NodusRequestContext context, CreateMediaDownloadRequest request) {
        requireShareUrl(request.sourceUrl());
        if ("TV".equals(request.mediaType()) && request.episodeNumber() == null) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "MEDIA_EPISODE_REQUIRED", "电视剧下载必须提供集数");
        }
        long libraryBytes = repository.libraryBytes(context.tenantId(), context.userId());
        UUID id = UUID.randomUUID();
        Path staging = files.prepareStaging(
                id, request.originalFilename(), libraryBytes, request.expectedSizeBytes());
        MediaDownloadResponse response = repository.insertDownload(
                context, id, request, staging, files.normalizedFilename(request.originalFilename()), Instant.now(clock));
        audit.append(context, "MEDIA_DOWNLOAD_CREATED", "media_download", id.toString(),
                Map.of("title", response.title(), "expectedSizeBytes",
                        response.expectedSizeBytes() == null ? 0 : response.expectedSizeBytes()));
        outbox.append(context, "download.created", "media_download", id.toString(), response);
        return response;
    }

    public List<MediaDownloadResponse> listDownloads(NodusRequestContext context, int limit) {
        return repository.listDownloads(context, Math.max(1, Math.min(limit, 100)));
    }

    public MediaDownloadResponse progress(
            NodusRequestContext context,
            UUID id,
            MediaDownloadProgressRequest request) {
        if (request.totalBytes() != null && request.totalBytes() > properties.maximumFileBytes()) {
            throw new DomainException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_FILE_TOO_LARGE", "文件超过单任务大小上限");
        }
        if (repository.updateProgress(context, id, request.downloadedBytes(), request.totalBytes(), Instant.now(clock)) == 0) {
            throw new DomainException(HttpStatus.CONFLICT, "MEDIA_DOWNLOAD_NOT_ACTIVE", "下载任务不存在或已结束");
        }
        return repository.findDownload(context, id).orElseThrow();
    }

    public MediaDownloadResponse complete(NodusRequestContext context, UUID id, long downloadedBytes) {
        MediaDownloadResponse task = repository.findDownload(context, id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "MEDIA_DOWNLOAD_NOT_FOUND", "下载任务不存在"));
        Path path = Path.of(task.stagingPath());
        if (!Files.isRegularFile(path)) {
            throw new DomainException(HttpStatus.CONFLICT, "MEDIA_STAGING_FILE_MISSING", "下载文件尚未写入临时目录");
        }
        if (repository.queueVerification(context, id, downloadedBytes, Instant.now(clock)) == 0) {
            throw new DomainException(HttpStatus.CONFLICT, "MEDIA_DOWNLOAD_NOT_ACTIVE", "下载任务已结束");
        }
        return repository.findDownload(context, id).orElseThrow();
    }

    public void cancel(NodusRequestContext context, UUID id) {
        MediaDownloadResponse task = repository.findDownload(context, id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "MEDIA_DOWNLOAD_NOT_FOUND", "下载任务不存在"));
        if (repository.cancel(context, id, Instant.now(clock)) == 0) {
            throw new DomainException(HttpStatus.CONFLICT, "MEDIA_DOWNLOAD_NOT_ACTIVE", "下载任务已结束");
        }
        files.deletePartial(task.stagingPath());
    }

    public void fail(NodusRequestContext context, UUID id, MediaDownloadFailureRequest request) {
        MediaDownloadResponse task = repository.findDownload(context, id)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "MEDIA_DOWNLOAD_NOT_FOUND", "下载任务不存在"));
        if (repository.failActive(
                context, id, request.code().trim(), request.message().trim(), Instant.now(clock)) == 0) {
            throw new DomainException(
                    HttpStatus.CONFLICT, "MEDIA_DOWNLOAD_NOT_ACTIVE", "下载任务已结束");
        }
        files.deletePartial(task.stagingPath());
    }

    public List<MediaAssetResponse> listAssets(NodusRequestContext context) {
        return repository.listAssets(context);
    }

    public MediaAssetFile assetFile(NodusRequestContext context, UUID id) {
        return repository.findAssetFile(context, id)
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "MEDIA_NOT_FOUND", "媒体不存在"));
    }

    public Map<String, Object> storage(NodusRequestContext context) {
        long used = repository.libraryBytes(context.tenantId(), context.userId());
        return Map.of(
                "libraryBytes", used,
                "libraryLimitBytes", properties.maximumLibraryBytes(),
                "diskAvailableBytes", files.availableBytes(),
                "minimumFreeBytes", properties.minimumFreeBytes());
    }

    @Transactional
    public MediaDownloadTask claimVerification() {
        Instant now = Instant.now(clock);
        return repository.claimVerification(now, now.minusSeconds(600)).orElse(null);
    }

    @Transactional
    public void process(MediaDownloadTask task) {
        VerificationResult result = null;
        try {
            result = files.verifyAndMove(task);
            String jellyfinStatus = jellyfin.configured() ? "PENDING" : "NOT_CONFIGURED";
            UUID mediaId = UUID.randomUUID();
            repository.complete(task, mediaId, result.destination(), result.size(), result.sha256(),
                    result.probe(), jellyfinStatus, Instant.now(clock));
            jellyfin.refreshLibrary();
        } catch (MediaVerificationException error) {
            repository.fail(task.id(), error.code(), error.getMessage(), Instant.now(clock));
        } catch (Exception error) {
            if (result != null) {
                files.restoreToStaging(result.destination(), Path.of(task.stagingPath()));
            }
            repository.fail(task.id(), "MEDIA_VERIFICATION_FAILED", error.getMessage(), Instant.now(clock));
        }
    }

    @Transactional
    public void syncJellyfin() {
        if (!jellyfin.configured()) {
            return;
        }
        for (MediaAssetFile asset : repository.pendingJellyfinAssets(20)) {
            jellyfin.findByPath(asset.path()).ifPresent(item -> repository.jellyfinLinked(
                    asset.id(), item.id(), item.primaryImageTag(), Instant.now(clock)));
        }
    }

    private void requireShareUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
            boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null
                    && SHARE_HOSTS.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
            if (!allowed) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException error) {
            throw new DomainException(HttpStatus.BAD_REQUEST, "MEDIA_SHARE_URL_UNSUPPORTED", "不支持这个网盘分享链接");
        }
    }
}
