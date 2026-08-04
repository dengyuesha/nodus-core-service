# nodus-core-service

Nodus 设备侧业务数据服务。该服务保存设备私有业务数据和持久任务状态，不承担公共工具执行，也不承担图片 OCR/识别。

## 当前范围（P0 + P1）

- PostgreSQL + Flyway 数据库基线
- 统一租户、用户、家庭、设备、会话和请求追踪上下文
- API Key 服务间访问校验
- 写接口幂等记录
- 审计记录与事务 Outbox
- 设备注册最小闭环
- Actuator 健康检查
- 备忘录创建、查询、修改和软删除
- 提醒创建、查询、取消、持久到期扫描、租约领取、失败重试和 ACK
- 健康/财务已结构化记录的逐条校验、来源幂等导入、明细查询和阶段汇总

健康和财务图片由 IM/OCR 团队识别并生成结构化数据；后续 P2 只在本服务实现结构化数据的校验、幂等写入、查询和阶段性聚合。

P2 对接合同见 [健康/财务结构化数据接口](docs/STRUCTURED_HEALTH_FINANCE_API.md)。

## 本地验证

需要 JDK 17：

```powershell
$env:JAVA_HOME='D:\java\jdk-17'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
mvn test
```

连接 PostgreSQL 后启动：

```powershell
$env:NODUS_CORE_DB_PASSWORD='your-password'
$env:NODUS_CORE_API_KEY='your-random-api-key'
mvn spring-boot:run
```

默认端口为 `8094`。健康检查：`GET /actuator/health`。

## 请求约定

`/api/v1/**` 请求至少携带：

- `X-Nodus-Api-Key`
- `X-Tenant-Id`
- `X-User-Id`

建议同时携带 `X-Device-Id`、`X-Household-Id`、`X-Session-Id`、`X-Source-Client` 和 `X-Request-Id`。写接口必须携带 `Idempotency-Key`。

设备注册示例：

```http
POST /api/v1/devices/register
X-Nodus-Api-Key: <secret>
X-Tenant-Id: default
X-User-Id: user-001
X-Device-Id: device-001
Idempotency-Key: 7e2c0efe-6c87-4f96-b725-e9c9c3a0128a
Content-Type: application/json

{
  "deviceId": "device-001",
  "householdId": "home-001",
  "displayName": "客厅设备"
}
```

P1 主要接口：

- `POST/GET/PATCH/DELETE /api/v1/memos`
- `POST/GET /api/v1/reminders`
- `POST /api/v1/reminders/{reminderId}/cancel`
- `POST /api/v1/reminder-deliveries/claim`
- `POST /api/v1/reminder-deliveries/claim-tenant`
- `POST /api/v1/reminder-deliveries/{eventId}/ack`
- `POST /api/v1/reminder-deliveries/{eventId}/fail`

投递采用至少一次语义。消费者领取后必须按 `eventId + reminderId` 幂等处理，并在成功发布终端事件后 ACK；未 ACK 的租约到期后会重新开放领取。

P2 主要接口：

- `POST /api/v1/health/records/import`
- `GET /api/v1/health/records`、`GET /api/v1/health/summary`
- `POST /api/v1/finance/records/import`
- `GET /api/v1/finance/records`、`GET /api/v1/finance/summary`

设备部署验证（2026-08-04）：Core 与 PostgreSQL 已部署在设备端，AINAS 已改为通过 Core 创建、查询、完成和删除备忘；定时提醒由 AINAS 租户级领取并发布既有 `reminder.due` 事件。真实 WebSocket 验证后，提醒与投递状态均进入 `ACKNOWLEDGED`。设备端旧 AINAS JSON 共 8 条已用旧记录 ID 派生的幂等键迁移，原目录及迁移前备份均保留；历史已过期时间不会补建提醒。

架构与后续阶段以 [实施方案](../nodus-one-prototype/docs/NODUS_CORE_DATA_AND_MEDIA_IMPLEMENTATION_PLAN.md) 为准。
