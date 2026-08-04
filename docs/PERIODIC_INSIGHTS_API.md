# 阶段性洞察 API（P3）

## 边界

Core 只使用已经落库的结构化健康、财务记录，不读取图片、不执行 OCR。Core 负责周期边界、聚合、证据固定、结果持久化、反馈和重生成关系；AINAS 只做无状态文本生成，不保存业务数据。AINAS 或本地模型不可用时，Core 返回并保存标记为 `DETERMINISTIC/FALLBACK` 的事实摘要，不伪装为模型生成。AINAS 调用失败后会进入 5 分钟冷却期，防止用户连续操作堆积本地推理请求。

所有请求继续携带 `X-Nodus-Api-Key`、`X-Tenant-Id`、`X-User-Id`。自然周期按 `Asia/Shanghai` 计算，`periodEnd` 为右开边界，`dataCutoff` 是本轮实际使用的数据截止时间。

## 生成

```http
POST /api/v1/insights/generate
Content-Type: application/json

{
  "domain": "HEALTH",
  "periodType": "MONTH",
  "anchorDate": "2026-08-04",
  "currency": "CNY",
  "force": false
}
```

- `domain`：`HEALTH`、`FINANCE`。
- `periodType`：`WEEK`、`MONTH`、`QUARTER`。
- `anchorDate`：可选，决定所处自然周期；默认今天。
- `currency`：财务洞察默认 `CNY`，不做跨币种换算。
- `force=false` 时，同周期且证据内容未变化会复用已有洞察。
- 没有结构化证据时返回 `422 INSIGHT_NO_EVIDENCE`。

响应包含 `periodStart`、`periodEnd`、`dataCutoff`、`provider`、`modelName`、`promptVersion`、`generationMode`，以及每条结论引用的 `evidenceRecordIds` 和完整的最小证据引用列表。

## 查询与交互

```text
GET  /api/v1/insights?domain=HEALTH&limit=20
GET  /api/v1/insights/{insightId}
POST /api/v1/insights/{insightId}/regenerate
POST /api/v1/insights/{insightId}/feedback
POST /api/v1/insights/{insightId}/questions
```

反馈请求：

```json
{"rating":"HELPFUL","comment":"证据范围清晰"}
```

`rating` 仅支持 `HELPFUL`、`NOT_HELPFUL`。追问请求：

```json
{"question":"这个结论用了哪些记录？"}
```

追问严格复用该洞察已经固定的聚合快照和证据，不能自行扩大数据范围。重新生成会创建新记录并通过 `supersedesInsightId` 保留版本关系，不覆盖原洞察。

## 安全提示

- 健康洞察仅总结趋势，不构成医学诊断、用药或治疗建议。
- 财务洞察区分已入库事实与解释，不构成投资、借贷、税务建议。
- 模型返回的证据 ID 必须存在于 Core 提供的白名单中，否则 AINAS 会剔除。
- AINAS 输入不包含图片、OCR 原文、数据库密钥或不必要的财务描述原文。
