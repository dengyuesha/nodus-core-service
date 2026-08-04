package com.aiwei.nodus.core.media;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.aiwei.nodus.core.identity.NodusRequestContext;

@Repository
public class MediaRepository {

    private final JdbcTemplate jdbcTemplate;

    public MediaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MediaDownloadResponse insertDownload(
            NodusRequestContext context,
            UUID id,
            CreateMediaDownloadRequest request,
            Path stagingPath,
            String originalFilename,
            Instant now) {
        jdbcTemplate.update("""
                insert into media_download_task (
                    id, tenant_id, user_id, device_id, title, media_type, release_year,
                    season_number, episode_number, episode_title, source_provider, source_share_id,
                    source_url, original_filename, staging_path, expected_size_bytes,
                    downloaded_bytes, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'DOWNLOADING', ?, ?)
                """, id, context.tenantId(), context.userId(), context.deviceId(), request.title().trim(),
                request.mediaType(), request.releaseYear(), request.seasonNumber(), request.episodeNumber(),
                blank(request.episodeTitle()), request.sourceProvider().trim(), blank(request.sourceShareId()),
                request.sourceUrl().trim(), originalFilename, stagingPath.toString(), request.expectedSizeBytes(),
                Timestamp.from(now), Timestamp.from(now));
        return findDownload(context, id).orElseThrow();
    }

    public Optional<MediaDownloadResponse> findDownload(NodusRequestContext context, UUID id) {
        return jdbcTemplate.query("""
                select * from media_download_task
                 where id = ? and tenant_id = ? and user_id = ?
                """, this::mapDownload, id, context.tenantId(), context.userId()).stream().findFirst();
    }

    public List<MediaDownloadResponse> listDownloads(NodusRequestContext context, int limit) {
        return jdbcTemplate.query("""
                select * from media_download_task
                 where tenant_id = ? and user_id = ?
                 order by created_at desc limit ?
                """, this::mapDownload, context.tenantId(), context.userId(), limit);
    }

    public int updateProgress(
            NodusRequestContext context,
            UUID id,
            long downloadedBytes,
            Long totalBytes,
            Instant now) {
        return jdbcTemplate.update("""
                update media_download_task
                   set downloaded_bytes = greatest(downloaded_bytes, ?),
                       expected_size_bytes = case when ? is null or ? = 0 then expected_size_bytes else ? end,
                       updated_at = ?
                 where id = ? and tenant_id = ? and user_id = ? and status = 'DOWNLOADING'
                """, downloadedBytes, totalBytes, totalBytes, totalBytes, Timestamp.from(now),
                id, context.tenantId(), context.userId());
    }

    public int queueVerification(NodusRequestContext context, UUID id, long downloadedBytes, Instant now) {
        return jdbcTemplate.update("""
                update media_download_task
                   set downloaded_bytes = greatest(downloaded_bytes, ?), status = 'VERIFYING',
                       verify_started_at = null, failure_code = null, failure_message = null, updated_at = ?
                 where id = ? and tenant_id = ? and user_id = ? and status = 'DOWNLOADING'
                """, downloadedBytes, Timestamp.from(now), id, context.tenantId(), context.userId());
    }

    public int cancel(NodusRequestContext context, UUID id, Instant now) {
        return jdbcTemplate.update("""
                update media_download_task set status = 'CANCELLED', updated_at = ?
                 where id = ? and tenant_id = ? and user_id = ?
                   and status in ('DOWNLOADING', 'VERIFYING')
                """, Timestamp.from(now), id, context.tenantId(), context.userId());
    }

    public int failActive(
            NodusRequestContext context,
            UUID id,
            String code,
            String message,
            Instant now) {
        return jdbcTemplate.update("""
                update media_download_task
                   set status = 'FAILED', failure_code = ?, failure_message = ?, updated_at = ?
                 where id = ? and tenant_id = ? and user_id = ? and status = 'DOWNLOADING'
                """, code, truncate(message), Timestamp.from(now), id, context.tenantId(), context.userId());
    }

    public Optional<MediaDownloadTask> claimVerification(Instant now, Instant expiredBefore) {
        List<UUID> candidates = jdbcTemplate.query("""
                select id from media_download_task
                 where status = 'VERIFYING'
                   and (verify_started_at is null or verify_started_at < ?)
                 order by updated_at limit 1
                """, (resultSet, row) -> resultSet.getObject("id", UUID.class), Timestamp.from(expiredBefore));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        UUID id = candidates.get(0);
        int updated = jdbcTemplate.update("""
                update media_download_task set verify_started_at = ?, updated_at = ?
                 where id = ? and status = 'VERIFYING'
                   and (verify_started_at is null or verify_started_at < ?)
                """, Timestamp.from(now), Timestamp.from(now), id, Timestamp.from(expiredBefore));
        if (updated == 0) {
            return Optional.empty();
        }
        return jdbcTemplate.query("select * from media_download_task where id = ?", this::mapTask, id)
                .stream().findFirst();
    }

    public void complete(
            MediaDownloadTask task,
            UUID mediaId,
            Path destination,
            long size,
            String sha256,
            MediaProbe probe,
            String jellyfinStatus,
            Instant now) {
        jdbcTemplate.update("""
                insert into media_asset (
                    id, download_id, tenant_id, user_id, title, media_type, release_year,
                    season_number, episode_number, episode_title, file_path, file_size_bytes,
                    sha256, container, video_codec, audio_codec, duration_seconds, width, height,
                    jellyfin_sync_status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, mediaId, task.id(), task.tenantId(), task.userId(), task.title(), task.mediaType(),
                task.releaseYear(), task.seasonNumber(), task.episodeNumber(), blank(task.episodeTitle()),
                destination.toString(), size, sha256, probe.container(), probe.videoCodec(), probe.audioCodec(),
                probe.durationSeconds(), probe.width(), probe.height(), jellyfinStatus,
                Timestamp.from(now), Timestamp.from(now));
        jdbcTemplate.update("""
                update media_download_task set status = 'COMPLETED', completed_at = ?, updated_at = ?
                 where id = ? and status = 'VERIFYING'
                """, Timestamp.from(now), Timestamp.from(now), task.id());
    }

    public void fail(UUID id, String code, String message, Instant now) {
        jdbcTemplate.update("""
                update media_download_task
                   set status = 'FAILED', failure_code = ?, failure_message = ?, updated_at = ?
                 where id = ? and status = 'VERIFYING'
                """, code, truncate(message), Timestamp.from(now), id);
    }

    public long libraryBytes(String tenantId, String userId) {
        Long value = jdbcTemplate.queryForObject("""
                select coalesce(sum(file_size_bytes), 0) from media_asset
                 where tenant_id = ? and user_id = ?
                """, Long.class, tenantId, userId);
        return value == null ? 0 : value;
    }

    public List<MediaAssetResponse> listAssets(NodusRequestContext context) {
        return jdbcTemplate.query("""
                select * from media_asset where tenant_id = ? and user_id = ? order by created_at desc
                """, this::mapAsset, context.tenantId(), context.userId());
    }

    public Optional<MediaAssetFile> findAssetFile(NodusRequestContext context, UUID id) {
        return jdbcTemplate.query("""
                select id, file_path, jellyfin_item_id, jellyfin_image_tag from media_asset
                 where id = ? and tenant_id = ? and user_id = ?
                """, (resultSet, row) -> new MediaAssetFile(
                        resultSet.getObject("id", UUID.class),
                        Path.of(resultSet.getString("file_path")),
                        resultSet.getString("jellyfin_item_id"),
                        resultSet.getString("jellyfin_image_tag")),
                id, context.tenantId(), context.userId()).stream().findFirst();
    }

    public List<MediaAssetFile> pendingJellyfinAssets(int limit) {
        return jdbcTemplate.query("""
                select id, file_path, jellyfin_item_id, jellyfin_image_tag from media_asset
                 where jellyfin_sync_status in ('PENDING', 'SCAN_REQUESTED')
                 order by updated_at limit ?
                """, (resultSet, row) -> new MediaAssetFile(
                        resultSet.getObject("id", UUID.class), Path.of(resultSet.getString("file_path")),
                        resultSet.getString("jellyfin_item_id"), resultSet.getString("jellyfin_image_tag")), limit);
    }

    public void jellyfinLinked(UUID id, String itemId, String imageTag, Instant now) {
        jdbcTemplate.update("""
                update media_asset set jellyfin_item_id = ?, jellyfin_image_tag = ?,
                       jellyfin_sync_status = 'LINKED', updated_at = ? where id = ?
                """, itemId, imageTag, Timestamp.from(now), id);
    }

    private MediaDownloadResponse mapDownload(ResultSet resultSet, int row) throws SQLException {
        return new MediaDownloadResponse(
                resultSet.getObject("id", UUID.class), resultSet.getString("title"),
                resultSet.getString("media_type"), integer(resultSet, "release_year"),
                integer(resultSet, "season_number"), integer(resultSet, "episode_number"),
                resultSet.getString("episode_title"), resultSet.getString("source_provider"),
                resultSet.getString("source_url"), resultSet.getString("original_filename"),
                resultSet.getString("staging_path"), longObject(resultSet, "expected_size_bytes"),
                resultSet.getLong("downloaded_bytes"), resultSet.getString("status"),
                resultSet.getString("failure_code"), resultSet.getString("failure_message"),
                resultSet.getTimestamp("created_at").toInstant(), resultSet.getTimestamp("updated_at").toInstant(),
                instant(resultSet, "completed_at"));
    }

    private MediaDownloadTask mapTask(ResultSet resultSet, int row) throws SQLException {
        return new MediaDownloadTask(
                resultSet.getObject("id", UUID.class), resultSet.getString("tenant_id"),
                resultSet.getString("user_id"), resultSet.getString("title"), resultSet.getString("media_type"),
                integer(resultSet, "release_year"), integer(resultSet, "season_number"),
                integer(resultSet, "episode_number"), resultSet.getString("episode_title"),
                resultSet.getString("original_filename"), resultSet.getString("staging_path"),
                longObject(resultSet, "expected_size_bytes"), resultSet.getLong("downloaded_bytes"),
                resultSet.getString("status"), resultSet.getTimestamp("created_at").toInstant());
    }

    private MediaAssetResponse mapAsset(ResultSet resultSet, int row) throws SQLException {
        UUID id = resultSet.getObject("id", UUID.class);
        String imageTag = resultSet.getString("jellyfin_image_tag");
        return new MediaAssetResponse(
                id, resultSet.getObject("download_id", UUID.class), resultSet.getString("title"),
                resultSet.getString("media_type"), integer(resultSet, "release_year"),
                integer(resultSet, "season_number"), integer(resultSet, "episode_number"),
                resultSet.getString("episode_title"), resultSet.getLong("file_size_bytes"),
                resultSet.getString("sha256"), resultSet.getString("container"),
                resultSet.getString("video_codec"), resultSet.getString("audio_codec"),
                longObject(resultSet, "duration_seconds"), integer(resultSet, "width"), integer(resultSet, "height"),
                resultSet.getString("jellyfin_item_id"), resultSet.getString("jellyfin_sync_status"),
                imageTag == null ? null : "/api/v1/media/" + id + "/poster",
                "/api/v1/media/" + id + "/stream", resultSet.getTimestamp("created_at").toInstant());
    }

    private Integer integer(ResultSet resultSet, String name) throws SQLException {
        int value = resultSet.getInt(name);
        return resultSet.wasNull() ? null : value;
    }

    private Long longObject(ResultSet resultSet, String name) throws SQLException {
        long value = resultSet.getLong(name);
        return resultSet.wasNull() ? null : value;
    }

    private Instant instant(ResultSet resultSet, String name) throws SQLException {
        Timestamp value = resultSet.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value) {
        String text = value == null ? "verification failed" : value;
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
