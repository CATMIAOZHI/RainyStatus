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
| 缓存 | 默认 `Cache-Control: no-store`。例外：`/api/status` 带 30 秒机房内缓存（见该节） |

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
    "avatarUrl": null,
    "showTemperature": false,
    "showNetwork": false,
    "showMood": true,
    "timezoneOffsetMinutes": 480,
    "defaultLang": "zh-Hans",
    "offlineThresholdMs": 1800000,
    "customText": {
      "online": null,
      "offline": null,
      "gone": null,
      "noData": null
    },
    "history": {
      "enabled": false,
      "ranges": [],
      "challenge": "none",
      "siteKey": null
    }
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
| `site.defaultLang` | `zh-Hans` / `zh-Hant` / `en`。**仅作兜底**：浏览器语言未命中受支持语言时才使用 |
| `site.customText` | 部署者自定义的状态徽章文案（`STATUS_TEXT_*`），未配置的字段为 `null`。**非 null 时前端固定用它、不跟语言切换**；描述文字已去换行、控制字符与零宽字符（BOM/ZWSP）按空格处理，并按码点截断到 40 |
| `site.avatarUrl` | 头像图片（`AVATAR_URL`）；`null` = 用 `site.avatar` 的 emoji 字符。只接受站内路径 `/x.png`、`https://` 外链、`data:image/*;base64,…`（长度 ≤ 4096），其余一律按未配置处理；前端在图片加载失败时也会退回 emoji |
| `site.history` | 历史图表能力（`HISTORY_*` 解析结果）。`enabled=false` 时网页隐藏图表卡；`siteKey` 只在 `challenge="turnstile"` 时下发 |
| `site` | 站点展示配置，由 Worker 的 `vars` 下发，前端据此渲染（不硬编码个人信息） |

**展示开关是服务端过滤，不是前端隐藏**：`SHOW_TEMPERATURE` / `SHOW_NETWORK` 关闭时，`device.temperatureC` / `device.network` 在响应里就是 `null`；`SHOW_MOOD` 关闭时 `mood` 整体为 `null`。直接 `curl` 也拿不到——否则这个开关只是「看起来关了」。

**30 秒缓存**：`/api/status` 每次请求要读 2 个 KV key，而 KV 免费只有 10 万读/天，不缓存约 5 万次请求就能打光。缓存写在**机房内**（`caches.default`），缓存键是固定内部地址（`?lang=`、自定义 Host、随机参数都不参与），所以：

- KV 异常返回 **503 且不写缓存**（避免把故障状态粘住 30 秒）；
- 改了 `vars` 后最多有 **30 秒**的旧响应窗口（缓存里仍是旧配置）；
- `HEAD` 复用 GET 的缓存，但**不回传 body**；
- 这层缓存**不省 Worker 请求次数**（缓存命中照样算 1 次请求），只省 KV 读。

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
| `emoji` | 可选 | ≤ 8 **UTF-16 单元**（一个 😀 占 2 个，`👨‍👩‍👧` 这种 ZWJ 组合占 8 个；≈ 4 个普通 emoji）。App 端用 `MoodEmoji` 按同一口径截断，不会因为表情超长而被 400 拒 |

响应：`{ "ok": true, "updatedAt": 1759200000500 }`
---

## `GET /api/history?range=24h`

公开只读的历史图表数据。**默认关闭**（要开见 README 的 `HISTORY_*` 配置）。

三条硬约束（改这个接口前先读 `docs/roadmap.md` 的踩坑记录）：

1. **只读预生成的导出 JSON**（按主键读 1 行），**绝不现场扫描历史表**——否则一次「查一年」就能吃掉 D1 免费 500 万行读/天的一大块。
2. 档位是**白名单**（`24h`，更长档位在 `RANGE_SPECS` 里加），不接受任意 `from`/`to`/`limit`：参数只要能被攻击者控制，就能被用来逼出昂贵查询。
3. 三道门顺序从便宜到昂贵：**档位白名单 → Turnstile → 读 1 行**。

验证令牌放在请求头 `cf-turnstile-response`，**不要放查询参数**（URL 会进日志、Referer 和监控）。

未开启时（前端据此隐藏图表卡）：

```json
{ "ok": true, "enabled": false, "ranges": [] }
```

开启时：

```json
{
  "ok": true,
  "enabled": true,
  "challenge": "turnstile",
  "stale": false,
  "exportGeneratedAt": 1759212000000,
  "data": {
    "schemaVersion": 1,
    "mode": "samples",
    "range": "24h",
    "windowMs": 86400000,
    "resolutionMs": 600000,
    "generatedAt": 1759212000000,
    "sourceThrough": 1759211400000,
    "from": 1759125600000,
    "to": 1759212000000,
    "availability": "partial",
    "coverage": { "firstSampleAt": 1759126200000, "lastSampleAt": 1759211400000, "sampleCount": 140 },
    "chargeSessions": 2,
    "points": [{ "t": 1759211400000, "p": 87, "c": 1 }],
    "mood": [{ "at": 1759200000000, "text": "困了喵…", "emoji": "😴" }]
  }
}
```

| 字段 | 说明 |
|---|---|
| `challenge` | `turnstile` / `none`。前者时前端必须先拿令牌再请求 |
| `stale` | 导出超过 **45 分钟**没重建（Cron 掉队）→ 前端显示「可能不是最新」，而不是假装实时 |
| `exportGeneratedAt` | 导出生成时刻（服务端） |
| `data.mode` | 当前恒为 `samples`（原始采样）；将来长档位会是聚合口径，前端必须按它分支 |
| `data.resolutionMs` | 点的标称间隔（`24h` = 600000 = 10 分钟）；**缺口不要插值补点** |
| `data.sourceThrough` | 数据实际覆盖到的最后时刻；与 `generatedAt` 的差值 = 「多久没上报」 |
| `data.availability` | `empty`（窗口内无样本）/ `partial`（首尾没覆盖满窗口）/ `available` |
| `data.coverage` | 窗口内真实样本的首末时刻与条数。曲线要按它画「覆盖率」，不要按 144 点假设 |
| `data.chargeSessions` | 窗口内**充电开始次数**（由 `charging` 的已知跳变推断，非插拔次数） |
| `data.points[].p` | 电量百分比；`null` = 这次没读到（**不是 0**） |
| `data.points[].c` | `1` 充电中 / `0` 未充电 / `null` 没读到充电状态（**不能当未充电**） |
| `data.mood` | 心情事件（稀疏）。`SHOW_MOOD` 关闭时这里恒为 `[]`，服务端强制过滤，不等 Cron 重建 |
| `data.points[].t` | 服务端**收到**的时刻（epoch ms UTC），不是手机采样时刻：App 断网会排队补发，所以补齐的点只代表「何时收到」。**不插值、不回填**，缺口就是缺口 |
| `data.generatedAt` / `from` / `to` / `sourceThrough` | 分别是导出生成时刻、窗口起止、数据实际覆盖到的最后时刻——都不是采样时刻 |

---

## 错误码

| HTTP | code | 触发 |
|---|---|---|
| 400 | `invalid_json` | body 不是合法 JSON 对象 |
| 400 | `invalid_payload` | 字段越界 / 未知字段 / `range` 不在白名单 |
| 401 | `unauthorized` | 缺 token / token 不符 |
| 403 | `challenge_failed` | `/api/history` 人机验证未通过（含验证服务不可达——fail-closed） |
| 404 | `not_found` | 未知 API 路径；或 `/api/history` 的档位存在但**未在 `HISTORY_RANGES` 公开** |
| 405 | `method_not_allowed` | 方法不在白名单（响应带 `Allow` 头） |
| 413 | `payload_too_large` | body > 4096 字节 |
| 415 | `unsupported_media_type` | 非 `application/json` |
| 429 | `rate_limited` | 触发边缘限流 / KV 同 key 写频限（1 次/秒） |
| 500 | `internal_error` | KV 异常（含免费写额度耗尽） |
| 503 | `upstream_unavailable` | `/api/status` 读 KV 失败（**不写缓存**；前端应保留上一次数据） |
| 503 | `history_unavailable` | `/api/history` 的 D1 未配置 / 读失败 / 导出还没生成 / 导出 JSON 损坏 |

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
| KV 读 | 100,000 / 天（账号级） | 每次 `/api/status` 读 2 个 key；**30 秒机房缓存**把它压到「每机房每 30 秒 2 次」 |
| D1 行读 | 5,000,000 / 天 | `/api/history` 只读 1 行导出；心跳聚合每次要扫当天约 144 行 + 每条充电样本向前找一次「上一条已知状态」；Cron 每次多读窗口内约 144 行 + 1 行前置状态 |
| D1 行写 | 100,000 / 天 | 10 分钟心跳 = 1 行原始 + 2 行汇总 = 432/天 ≈ 0.43% |
| D1 存储 | 5 GB | 稳态约 3700 行（原始 7 天 ≈1008 + 小时 35 天 ≈840 + 日 5 年 ≈1826）＋心情事件，几 MB 量级 |
| Workers 请求 | 100,000 / 天（账号级） | **静态资源请求免费且不限量**；缓存命中（含 `caches.default`）**照样算 1 次请求** |
| 重置 | 每日 UTC 00:00 | 超额后该类操作直接失败 |

账号级意味着：**同账号下其他项目也在扣同一份额度**。多人使用请**各自部署自己的实例**（见 README 的一键部署按钮），不要共用一台。
