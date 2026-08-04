package com.aiwei.nodus.core.media;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.aiwei.nodus.core.api.DomainException;
import com.aiwei.nodus.core.identity.RequestContextResolver;

@RestController
@RequestMapping("/api/v1")
public class MediaController {

    private final RequestContextResolver contexts;
    private final MediaService service;
    private final JellyfinClient jellyfin;

    public MediaController(RequestContextResolver contexts, MediaService service, JellyfinClient jellyfin) {
        this.contexts = contexts;
        this.service = service;
        this.jellyfin = jellyfin;
    }

    @PostMapping("/media-downloads")
    @ResponseStatus(HttpStatus.CREATED)
    public MediaDownloadResponse create(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateMediaDownloadRequest request) {
        return service.create(contexts.resolve(servletRequest), request);
    }

    @GetMapping("/media-downloads")
    public List<MediaDownloadResponse> downloads(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {
        return service.listDownloads(contexts.resolve(request), limit);
    }

    @PostMapping("/media-downloads/{downloadId}/progress")
    public MediaDownloadResponse progress(
            HttpServletRequest servletRequest,
            @PathVariable UUID downloadId,
            @Valid @RequestBody MediaDownloadProgressRequest request) {
        return service.progress(contexts.resolve(servletRequest), downloadId, request);
    }

    @PostMapping("/media-downloads/{downloadId}/complete")
    public MediaDownloadResponse complete(
            HttpServletRequest servletRequest,
            @PathVariable UUID downloadId,
            @Valid @RequestBody MediaDownloadProgressRequest request) {
        return service.complete(contexts.resolve(servletRequest), downloadId, request.downloadedBytes());
    }

    @PostMapping("/media-downloads/{downloadId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(HttpServletRequest servletRequest, @PathVariable UUID downloadId) {
        service.cancel(contexts.resolve(servletRequest), downloadId);
    }

    @PostMapping("/media-downloads/{downloadId}/failed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void failed(
            HttpServletRequest servletRequest,
            @PathVariable UUID downloadId,
            @Valid @RequestBody MediaDownloadFailureRequest request) {
        service.fail(contexts.resolve(servletRequest), downloadId, request);
    }

    @GetMapping("/media")
    public List<MediaAssetResponse> library(HttpServletRequest request) {
        return service.listAssets(contexts.resolve(request));
    }

    @GetMapping("/media/storage")
    public Map<String, Object> storage(HttpServletRequest request) {
        return service.storage(contexts.resolve(request));
    }

    @GetMapping("/media/{mediaId}/poster")
    public ResponseEntity<byte[]> poster(HttpServletRequest servletRequest, @PathVariable UUID mediaId) {
        MediaAssetFile asset = service.assetFile(contexts.resolve(servletRequest), mediaId);
        JellyfinClient.ImagePayload image = jellyfin.poster(asset.jellyfinItemId())
                .orElseThrow(() -> new DomainException(HttpStatus.NOT_FOUND, "MEDIA_POSTER_NOT_FOUND", "媒体海报不存在"));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(org.springframework.http.CacheControl.maxAge(java.time.Duration.ofHours(6)))
                .body(image.data());
    }

    @GetMapping("/media/{mediaId}/stream")
    public void stream(
            HttpServletRequest servletRequest,
            HttpServletResponse response,
            @PathVariable UUID mediaId) throws IOException {
        MediaAssetFile asset = service.assetFile(contexts.resolve(servletRequest), mediaId);
        if (!Files.isRegularFile(asset.path())) {
            throw new DomainException(HttpStatus.NOT_FOUND, "MEDIA_FILE_MISSING", "媒体文件不存在");
        }
        long fileSize = Files.size(asset.path());
        ByteRange range = range(servletRequest.getHeader(HttpHeaders.RANGE), fileSize);
        String contentType = Files.probeContentType(asset.path());
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CONTENT_TYPE,
                contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "inline; filename*=UTF-8''" + java.net.URLEncoder.encode(
                        asset.path().getFileName().toString(), java.nio.charset.StandardCharsets.UTF_8));
        response.setStatus(range.partial() ? HttpStatus.PARTIAL_CONTENT.value() : HttpStatus.OK.value());
        response.setHeader(HttpHeaders.CONTENT_LENGTH, Long.toString(range.length()));
        if (range.partial()) {
            response.setHeader(HttpHeaders.CONTENT_RANGE,
                    "bytes " + range.start() + "-" + range.end() + "/" + fileSize);
        }
        try (RandomAccessFile input = new RandomAccessFile(asset.path().toFile(), "r")) {
            input.seek(range.start());
            byte[] buffer = new byte[64 * 1024];
            long remaining = range.length();
            while (remaining > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                response.getOutputStream().write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private ByteRange range(String header, long size) {
        if (header == null || header.isBlank()) {
            return new ByteRange(0, size - 1, false);
        }
        if (!header.matches("bytes=\\d*-\\d*") || header.contains(",")) {
            throw new DomainException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                    "MEDIA_RANGE_INVALID", "不支持这个 Range 请求");
        }
        String[] values = header.substring(6).split("-", -1);
        try {
            long start;
            long end;
            if (values[0].isBlank()) {
                long suffix = Long.parseLong(values[1]);
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(values[0]);
                end = values[1].isBlank() ? size - 1 : Long.parseLong(values[1]);
            }
            if (start < 0 || start >= size || end < start) {
                throw new NumberFormatException();
            }
            return new ByteRange(start, Math.min(end, size - 1), true);
        } catch (NumberFormatException error) {
            throw new DomainException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE,
                    "MEDIA_RANGE_INVALID", "Range 超出文件范围");
        }
    }

    private record ByteRange(long start, long end, boolean partial) {
        long length() {
            return end - start + 1;
        }
    }
}
