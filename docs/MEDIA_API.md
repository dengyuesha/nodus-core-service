# Nodus 影视下载与媒体库 API

## 职责边界

- `aiwei-tools-service` 只适配海搜搜索和分享链接校验，不保存 Nodus 用户的下载或媒体数据。
- `nodus-core-service` 保存下载任务、校验结果、正式文件路径和 Jellyfin Item ID。
- Electron 在隔离浏览窗口中承接用户登录、选文件和明确下载确认，不把 tools/Core/Jellyfin 密钥暴露给渲染进程。
- Jellyfin 只管理用户合法保存到本地的影片，不提供影片来源。

## 下载状态

`DOWNLOADING -> VERIFYING -> COMPLETED`

浏览器或网络中断进入 `FAILED`；用户在任务建立前放弃不会创建记录。只有处于 `VERIFYING` 的文件会执行 `ffprobe`、SHA-256、命名整理和原子入库。

## 接口

所有接口使用现有 `X-Nodus-*` 身份头和 Core API Key。

- `POST /api/v1/media-downloads`：预留空间并创建 staging 路径。
- `GET /api/v1/media-downloads?limit=50`：查询下载任务。
- `POST /api/v1/media-downloads/{id}/progress`：上报下载字节数。
- `POST /api/v1/media-downloads/{id}/complete`：下载完成，进入异步校验。
- `POST /api/v1/media-downloads/{id}/failed`：记录浏览器或网络失败并清理临时文件。
- `POST /api/v1/media-downloads/{id}/cancel`：取消活动任务并清理临时文件。
- `GET /api/v1/media`：查询已校验入库的媒体。
- `GET /api/v1/media/storage`：查询媒体配额和磁盘可用空间。
- `GET /api/v1/media/{id}/stream`：支持 HTTP Range 的受控本地播放。
- `GET /api/v1/media/{id}/poster`：代理 Jellyfin 海报，不暴露管理员 Token。

## 生产目录

```text
/home/aidlux/Videos/NodusMedia/
├── staging/
├── Movies/电影名 (年份)/电影名 (年份).mp4
└── TV/剧名 (年份)/Season 01/剧名 - S01E01 - 集名.mp4
```

Jellyfin 容器应把该宿主目录以同一个绝对路径只读挂载，保证 Core 可按正式路径关联 Jellyfin Item。

## 首期限制

- 只允许 HTTPS 和受控网盘域名。
- 只接收常见视频扩展名，单文件默认上限 8 GB。
- 默认至少保留 10 GB 磁盘空间，单用户媒体库默认上限 10 GB。
- 不自动登录网盘、不绕过平台限制、不静默批量下载。
