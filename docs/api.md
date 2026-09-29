# API 契约

> 这是 App 与 Worker 之间**唯一的耦合点**。任何一侧改动都必须同步本文档、另一侧实现，
> 以及 `cloud/src/types.ts` 与 App 端 DTO。

## 通用约定

| 项 | 约定 |
|---|---|
| 基础地址 | 部署者自己的 Worker 地址（`https://<name>.<subdomain>.workers.dev` 或自有域名） |
| 时间戳 | **epoch 毫秒整数（UTC）**。不用 ISO 字符串、不用秒 |
| `lastSeenAt` | **服务端 `Date.now()` 生成**。客户端时钟不准也不会影响掉线判定 |
| 在线判定 | **读取时**计算：`online = (now - lastSeenAt) <= offlineThresholdMs` |
| 鉴权 | `Authorization: Bearer <AUTH_TOKEN>`（仅写接口需要） |
| 请求体 | `Content-Type: application/json`，body ≤ 4096 字节 |
| 错误体 | 统一 `{ "ok": false, "error": { "code": "...", "message": "..." } }` |
| CORS | **不发任何 CORS 头**（网页与 API 同源部署）。浏览器跨域调用会被浏览器侧拦截 |
| 缓存 | API 响应 `Cache-Control: no-store`；KV 读缓存 30 秒 |

---

## `GET /api/health`

Worker 自身存活探针。**不需要鉴权，不碰 KV**（0 额度消耗）。
注意：它只说明 Worker 活着，**与「手机是否在线」无关**（那个看 `/api/status`）。

```json
{ "ok": true, "status": "ok", "service": "rainystatus", "time": 1759212000000 }
```

---

## `GET /api/status`

公开只读。`device` / `mood` 在无数据时均为 `null`。

```json
{
  "ok": true,
  "schemaVersion": 1,
  "generatedAt": 1759212000000,
  "online": true,
  "offlineThresholdMs": 1800000,
  "lastSeenAt": 1759211880000,
  "offlineForMs": 120000,
  "device": {
    "batteryPercent": 87,
    "charging": true,
    "chargeSource": "ac",
    "temperatureC": 31.5,
    "network": "wifi",
    "deviceName": "WaterRainCat-Phone",
    "appVersion": "1.0.0",
    "clientTs": 1759211879500
  },
  "mood": { "text": "困了喵…", "emoji": "😴", "updatedAt": 1759200000000 },
  "site": {
    "title": "RainyStatus",
    "owner": "WaterRainCat",
    "avatar": "☔",
    "showTemperature": false,
    "showNetwork": false,
    "showMood": true,
    "timezoneOffsetMinutes": 480,
    "defaultLang": "zh-Hans",
    "offlineThresholdMs": 1800000
  }
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `online` | `offlineForMs <= offlineThresholdMs` |
| `offlineForMs` | `now - lastSeenAt`，`lastSeenAt` 为 null 时也是 null |
| `device.*` | 未上报的可选字段为 `null`；`device` 整体为 `null` 表示从未收到过心跳 |
| `site.timezoneOffsetMinutes` | `null` = 按访客浏览器时区显示（默认）；数字 = 固定 UTC 偏移（分钟） |
| `site` | 站点展示配置，由 Worker 的 `vars` 下发，前端据此渲染（不硬编码个人信息） |

> **不提供 `stale` 字段**：KV 读缓存固定 30 秒，对 30 分钟阈值可忽略，加了反而自相矛盾。

---

## `POST /api/heartbeat`

需要 Bearer。**先验 token 再碰 KV** —— 非法请求 0 KV 成本。

请求：

```json
{
  "schemaVersion": 1,
  "batteryPercent": 87,
  "charging": true,
  "chargeSource": "ac",
  "temperatureC": 31.5,
  "network": "wifi",
  "deviceName": "WaterRainCat-Phone",
  "appVersion": "1.0.0",
  "clientTs": 1759211879500,
  "seq": 12345
}
```

| 字段 | 必需 | 约束 |
|---|---|---|
| `schemaVersion` | 可选 | 契约版本，当前恒为 `1`；客户端总是发送（缺省按 1 处理） |
| `batteryPercent` | ✅ | 整数 0–100 |
| `charging` | ✅ | boolean |
| `chargeSource` | 可选 | `ac` / `usb` / `wireless` / `none` |
| `temperatureC` | 可选 | −50 – 200 |
| `network` | 可选 | `wifi` / `cellular` / `ethernet` / `none` / `unknown` |
| `deviceName` | 可选 | ≤ 32 字符（**网页公开展示，别写隐私**） |
| `appVersion` | 可选 | ≤ 24 字符 |
| `clientTs` | 可选 | epoch ms，仅作诊断 |
| `seq` | 可选 | 客户端递增序号，保留给乱序检测 |

**未知字段会被拒绝（400）** —— 这是防止契约悄悄漂移的护栏。

响应：

```json
{ "ok": true, "receivedAt": 1759211880000, "nextExpectedInMs": 600000 }
```

---

## `POST /api/mood`

需要 Bearer。与心跳分开存（`current_mood`），心跳**不会**覆盖心情。
去重放在**客户端**（内容没变就不发），服务端不做「读-比较-再写」。

请求：

```json
{ "schemaVersion": 1, "text": "困了喵…", "emoji": "😴" }
```

| 字段 | 必需 | 约束 |
|---|---|---|
| `text` | ✅ | 非空，≤ 140 **码点**（emoji/中文都算 1 个） |
| `emoji` | 可选 | ≤ 8 字符 |

响应：`{ "ok": true, "updatedAt": 1759200000500 }`

---

## 错误码

| HTTP | code | 触发 |
|---|---|---|
| 400 | `invalid_json` | body 不是合法 JSON 对象 |
| 400 | `invalid_payload` | 字段越界 / 未知字段 |
| 401 | `unauthorized` | 缺 token / token 不符 |
| 404 | `not_found` | 未知 API 路径 |
| 405 | `method_not_allowed` | 方法不在白名单（响应带 `Allow` 头） |
| 413 | `payload_too_large` | body > 4096 字节 |
| 415 | `unsupported_media_type` | 非 `application/json` |
| 429 | `rate_limited` | 触发边缘限流 / KV 同 key 写频限（1 次/秒） |
| 500 | `internal_error` | KV 异常（含免费写额度耗尽） |

---

## 客户端重试约定（App 侧实现）

| 响应 | 行为 |
|---|---|
| 2xx | 成功，清空待发队列，重置退避 |
| 401 / 403 | **不重试**（凭据失效，重试一万次也没用）→ UI 提示重填 Token |
| 400 / 415 | **不重试**（契约不符，是 bug）→ 写调试日志 |
| 413 | **不重试**（payload 过大） |
| 429 | 按 `Retry-After`（若有），否则指数退避 |
| 5xx / 网络异常 / 超时 | 指数退避重试：30s → ×2 → 上限 15 分钟，±20% 抖动 |

**重试最短间隔必须 ≥ 1 秒**，否则会撞上 KV 同 key 写频限（1 次/秒）→ 429。

---

## 额度约束（写客户端时请记住）

| 资源 | 免费额度 | 说明 |
|---|---|---|
| KV 写 | **1,000 / 天**（账号级） | 10 分钟心跳 = 144/天 ≈ 14.4% |
| KV 同 key 写 | 1 次/秒 | 只有重试风暴会撞上 |
| KV 读 | 100,000 / 天（账号级） | 每次 `/api/status` 读 2 个 key |
| Workers 请求 | 100,000 / 天（账号级） | **静态资源请求免费且不限量** |
| 重置 | 每日 UTC 00:00 | 超额后该类操作直接失败 |

账号级意味着：**同账号下其他项目也在扣同一份额度**。多人使用请**各自部署自己的实例**（见 README 的一键部署按钮），不要共用一台。
