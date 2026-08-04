# 健康/财务结构化数据接口（P2）

## 边界

本服务不接收图片，不执行 OCR，也不负责从图片中抽取字段。IM/OCR 团队完成识别后，只向本接口提交已经结构化的数据。所有请求仍需携带 `X-Nodus-Api-Key`、`X-Tenant-Id`、`X-User-Id`；建议同时传设备、会话、来源客户端和请求 ID。

记录幂等范围为：

```text
tenantId + userId + sourceSystem + sourceRecordId
```

同一范围、同一内容再次提交返回 `REPLAYED`；同一范围但内容不同返回逐条 `SOURCE_RECORD_CONFLICT`，原记录不被覆盖。单条字段错误返回 `REJECTED`，不影响同批其他合法记录。每批最多 500 条。

## 健康导入

```http
POST /api/v1/health/records/import
Content-Type: application/json

{
  "sourceSystem": "im-ocr",
  "records": [
    {
      "sourceRecordId": "im-message-1001:heart-rate",
      "metricType": "resting_heart_rate",
      "value": 58,
      "unit": "bpm",
      "measuredAt": "2026-08-04T08:30:00+08:00",
      "metadata": {
        "documentType": "体检报告",
        "confidence": 0.98
      }
    }
  ]
}
```

`metricType` 使用稳定的小写英文标识。当前设备页识别 `hrv`、`resting_heart_rate`、`spo2`、`respiratory_rate`、`steps`、`sleep_duration`、`deep_sleep_duration`、`weight`、`systolic_blood_pressure`、`diastolic_blood_pressure`；服务端允许继续扩展其他指标。

查询：

- `GET /api/v1/health/records?from=<ISO>&to=<ISO>&metricType=<type>&limit=200`
- `GET /api/v1/health/summary?from=<ISO>&to=<ISO>`

摘要按指标返回阶段内最新值、均值、最小值、最大值和趋势点。默认阶段为近 30 天。

## 财务导入

```http
POST /api/v1/finance/records/import
Content-Type: application/json

{
  "sourceSystem": "im-ocr",
  "records": [
    {
      "sourceRecordId": "im-message-2001:expense-1",
      "recordType": "EXPENSE",
      "amount": 168.50,
      "currency": "CNY",
      "category": "餐饮",
      "account": "招商银行信用卡",
      "description": "客户午餐",
      "occurredAt": "2026-08-04T12:20:00+08:00",
      "metadata": {
        "merchant": "示例餐厅",
        "confidence": 0.96
      }
    }
  ]
}
```

`recordType` 仅支持：

- `INCOME`：收入流水。
- `EXPENSE`：支出流水。
- `ASSET_BALANCE`：某账户资产余额快照。
- `LIABILITY_BALANCE`：某账户负债余额快照。

金额使用非负数，收入/支出方向由 `recordType` 表达。`currency` 使用三位币种代码。余额汇总按 `account` 取阶段内最新快照，不能把同一账户多次快照相加。

查询：

- `GET /api/v1/finance/records?from=<ISO>&to=<ISO>&recordType=<type>&currency=CNY&limit=500`
- `GET /api/v1/finance/summary?from=<ISO>&to=<ISO>&currency=CNY`

摘要返回收入、支出、净现金流、储蓄率、资产、负债、净资产、分类支出和月度现金流。当前不做跨币种换算。

## 导入响应

```json
{
  "total": 2,
  "created": 1,
  "replayed": 0,
  "rejected": 1,
  "results": [
    {
      "sourceRecordId": "source-1",
      "recordId": "c6dd41cf-2522-4be6-93d9-28fe001170ba",
      "status": "CREATED",
      "errorCode": null,
      "message": null
    },
    {
      "sourceRecordId": "source-2",
      "recordId": null,
      "status": "REJECTED",
      "errorCode": "FINANCIAL_RECORD_INVALID",
      "message": "amount 必须是非负数"
    }
  ]
}
```

IM 应持久化逐条结果，仅对可重试的网络/服务异常重试；字段错误或来源冲突需要人工或上游规则修正，不能更换随机 `sourceRecordId` 绕过冲突。
