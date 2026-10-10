# 雨晴Status · 设计方案

> 状态：**方案已定稿，工程已落地，云端已上线**（App + Worker + 静态页均已实现；2026-10-06 已部署到 `https://status.WaterRainCat.com`，自有域名 + KV 均已绑定）
> 最后更新：2026-10-10

---

## 1. 一句话定位

Android App 常驻后台，定时把手机电量 / 充电状态 / 在线心跳上报到自己的服务器；自有域名下一个公开网页，任何人打开就能看到「电量多少、人还在不在」。

---

## 2. 架构

```
Redmi K80 Pro                      Cloudflare                       访客
┌──────────────┐                ┌──────────────────┐            ┌────────┐
│ RainyStatus  │  每 10 分钟     │ Worker           │  HTTPS GET │ 浏览器 │
│ 前台服务      │ ──HTTPS POST──▶│ status.<域名>     │◀──────────│        │
│ 电量/充电/心跳 │  Bearer Token  │                  │            └────────┘
└──────────────┘                │  ┌────────────┐  │
                                │  │ Workers KV │  │
                                │  │ device_status │
                                │  │ current_mood  │
                                │  └────────────┘  │
                                │  GET /   → 静态网页（免费不限量）
                                │  GET  /api/status
                                │  POST /api/heartbeat
                                │  POST /api/mood
                                └──────────────────┘
```

- 默认**无数据库**：**1 个 Worker + 1 个 KV namespace + 1 个静态资源目录**。
- 公开网页做成 **静态资源**（Cloudflare 官方：*Requests to static assets are free and unlimited*）→ 网页浏览量不吃任何额度；即使免费额度耗尽（错误码 `1027`），**网页照常打开、只有 API 报错**。
- v1.2 起：**网页云端图表 + 云端心情历史已实现，但默认全关**——D1 分层聚合、Cron 只预生成导出、公开接口只读 1 行预生成 JSON、可选 Turnstile 人机验证。契约见 `docs/api.md` 的 `GET /api/history`；抗打边界、额度账与 10 条踩坑见 `docs/roadmap.md`。
- **App 本地图表 + 本机心情历史已实现**（都**默认开**，不需要配置就攒数据；设置 → 本机记录里可以关）：Room 表 `battery_samples` + Canvas 手绘的 24 小时折线与 7 天每日区间柱；心情另有一张 `mood_events` 表、首页「最近的心情」卡片，以及一个**按天分组的完整心情历史页**（入口两处：首页卡片底部的「查看全部」、设置页那行「已记录 N 条」）。见 `README.md` 的「App · 本机历史」。

---

## 3. 参数与额度

| 项 | 值 |
|---|---|
| 心跳间隔 | **10 分钟**（默认，可配置 60 / 300 / 600 / 900 秒） |
| 每天心跳写入 | **144 次** |
| KV 免费写额度 | 1,000 / 天（**账号级**，UTC 00:00 重置） |
| 占用比 | **14.4%** |
| 心情写入 | 仅在内容变化时（≈0–10 / 天） |
| 掉线判定阈值 | **30 分钟**无心跳 → 显示「已掉线」+ 掉线时长 |
| 写预算硬兜底 | App 内记 `writesToday`，超过 **600** 自动降频到 15 分钟并在首页提示 |
| 实时性 | 数据新鲜度 = 心跳间隔（≤10 分钟）+ KV 读缓存（`cacheTtl` 可设 **30 秒**，最小 30） |

**账号级额度提醒**：KV 的 1,000 写 / 天与 100,000 请求 / 天都是 **整个 Cloudflare 账号共享**的，同账号下其他项目也在扣。若将来额度紧张，可迁 Durable Objects（免费版已可用，强一致，写 SQLite 不占 KV 写额度）或升 Workers Paid（$5/月，KV 写 100 万/月）。

---

## 4. 接口契约（App ↔ Worker 唯一耦合点）

**时间统一**：所有时间戳为 **epoch 毫秒（UTC，整数）**。`lastSeenAt` **由服务端 `Date.now()` 生成**——绝不拿客户端时间判掉线。在线/掉线**在读取时计算**。

### `GET /api/status` → 200

```json
{
  "schemaVersion": 1,
  "generatedAt": 1759212000000,
  "online": true,
  "offlineThresholdMs": 1800000,
  "lastSeenAt": 1759211880000,
  "offlineForMs": 0,
  "device": {
    "batteryPercent": 87,
    "charging": true,
    "chargeSource": "ac",
    "temperatureC": 31.5,
    "network": "wifi",
    "deviceName": "WaterRainCat-Phone",
    "appVersion": "1.0.0"
  },
  "mood": { "text": "困了喵…", "emoji": "😴", "updatedAt": 1759200000000 }
}
```

- 无数据时 `device: null` / `mood: null`（前端显示「还没有收到过任何心跳」）。
- 可选字段缺失即 `null`。

### `POST /api/heartbeat`（需 `Authorization: Bearer <TOKEN>`）

```json
{ "schemaVersion": 1, "batteryPercent": 87, "charging": true,
  "chargeSource": "ac", "temperatureC": 31.5, "network": "wifi",
  "clientTs": 1759211879500, "deviceName": "WaterRainCat-Phone",
  "appVersion": "1.0.0" }
```
→ `{ "ok": true, "receivedAt": 1759211880000, "nextExpectedInMs": 600000 }`

### `POST /api/mood`（需 Bearer）

```json
{ "schemaVersion": 1, "text": "困了喵…", "emoji": "😴" }
```
→ `{ "ok": true, "updatedAt": 1759200000500 }`（`text` ≤ 140 码点，`emoji` ≤ 8 字符）

### `GET /api/health` → Worker 自身存活（与设备存活无关）

### 错误码（统一 `{ ok:false, error:{ code, message } }`）

| HTTP | code | 触发 |
|---|---|---|
| 400 | `invalid_json` / `invalid_payload` | 解析失败 / 字段越界 |
| 401 | `unauthorized` | 缺 token / token 不符 |
| 405 | `method_not_allowed` | 方法不在白名单 |
| 413 | `payload_too_large` | body > 4 KB |
| 415 | `unsupported_media_type` | 非 `application/json` |
| 429 | `rate_limited` | 限流 / KV 同 key 写频限（1 次/秒） |
| 500 | `internal_error` | KV 异常（含写额度耗尽） |

---

## 5. KV 设计

| key | 内容 | 写入频率 | TTL |
|---|---|---|---|
| `device_status` | 最近一次心跳的完整快照 | 144 / 天 | **不设 TTL** |
| `current_mood` | `{ schemaVersion, text, emoji, updatedAt }` | 内容变化时 | **不设 TTL** |

**为什么绝不能用 TTL**：`expirationTtl` 最小 60 秒，一旦过期 key 消失，`lastSeenAt` 就没了，**掉线时间无法计算**，网页会退化成「没有数据」。1 KB 占用 vs 1 GB 额度，宁可永久留存。

**写入策略**
1. 心跳恒定按间隔写，**不做「值没变就不写」的省写优化**——那会让掉线检测粒度变差。
2. 心情去重放 **App 端**（记住上次发送内容，没变不发），服务端不做「读-比较-再写」。
3. 幂等 = last-write-wins，服务端不加守卫；客户端重试带同一 `clientTs` + `seq`，重复覆盖无害。
4. 重试必须指数退避、最短间隔 ≥ 1 秒，避免撞同 key 写频限（1 次/秒）→ 429。
5. `schemaVersion` 放 value 内，key 名保持稳定；v2 大改时写 `device_status_v2` 平滑迁移。
6. **负缓存坑**：key 不存在时的「not found」也会被缓存 30–60 秒 → 首次部署后第一次心跳写完，网页可能仍显示「无数据」约 1 分钟，不是 bug。
7. 读合并：`KV.get(["device_status","current_mood"], {cacheTtl:30})`。

---

## 6. 安全设计

| 项 | 方案 |
|---|---|
| 鉴权 | `Authorization: Bearer <TOKEN>`；token ≥ 32 字节随机（`openssl rand -base64 48`）；**常数时间比较**防时序侧信道 |
| secret 存放 | `wrangler secret put AUTH_TOKEN`（加密、只写不可读）；**绝不放** `vars`、仓库、Android 源码 |
| App 侧 | token 存设置页输入 + DataStore；`android:allowBackup="false"` |
| 部署凭据 | `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` 走环境变量；`cloud/.dev.vars` 必须 gitignore |
| 请求体 | 先查 `Content-Length` ≤ 4096，再按文本长度二次校验后 `JSON.parse` |
| 字段校验 | 严格白名单 + 范围：`batteryPercent` 整数 0–100；`charging` bool；`chargeSource ∈ {ac,usb,wireless,none}`；`temperatureC` −50–200；**未知字段直接拒绝** |
| 失效快 | **先验 token 再碰 KV**，非法请求 0 KV 成本 |
| 边缘限流 | 免费版仅 1 条 Rate Limiting 规则（表达式字段只有 Path / Verified Bot，计数特征只有 IP）：`/api/heartbeat` → 30 次/分钟/IP → block 60s |
| 收敛面 | `workers_dev: false`，只暴露自定义域，减少 `*.workers.dev` 被扫描 |
| CORS | 网页与 API **同源** → **完全不发 CORS 头**（尤其不写 `Access-Control-Allow-Origin: *`），顺带挡掉浏览器跨域调用；非浏览器客户端由 token 挡 |
| 隐私边界 | 只存 电量/充电/温度/心情/时间戳/App 版本/设备名。**不采集、不持久化** IP、地理位置、SSID、IMEI。Worker 代码**禁止**把 `cf-connecting-ip` 写入 KV |
| 本机数据 | `battery_samples` / `mood_events` **只落本机磁盘**（Room），App 从不上传、不吃云端额度；两个开关**默认开**（2026-10 决定）、可各自关掉，两个清空入口**分开**（电量曲线没有云端副本，不能被「顺便清心情」带走）。心情那条还额外要求：只在**真的发送成功**之后才记 |
| 公开性提醒 | 心情文案与设备名是**全世界可见**的 |
| token 泄露处置 | `wrangler secret put AUTH_TOKEN` 立刻替换 → 旧 token 即刻失效（无宽限期）；同时吊销 CF API Token |

**XSS**：网页渲染心情/设备名一律用 `textContent`，**禁止 `innerHTML`**。

---

## 7. 多用户支持（「给别人用」）

### 核心结论：**每人部署自己的实例，不共用一台**

原因：KV 写额度是 **Cloudflare 账号级**的 1,000 写/天。如果所有人上报到 `status.WaterRainCat.com`，144 写/天 × N 人，**约 6 人就会打爆**，而且一个 token 泄露就是所有人受影响。因此**共享单实例在免费额度下不可行**。

### 模式 A（默认，推荐）：一键自部署

README 放置 **Deploy to Cloudflare 按钮**（Cloudflare 官方功能，已核实）：

```
[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/CATMIAOZHI/RainyStatus/tree/main/cloud)
```

点击后 Cloudflare 会：克隆仓库到用户自己的 GitHub → **自动创建并绑定 KV namespace**（官方支持的自动 provision 资源类型包含 KV）→ 用 Workers Builds 构建部署。全程不需要本地环境。

> **自动 provision 已实测**（CLI 路径）：模板里 `kv_namespaces` 刻意不写 id 时，`npx wrangler deploy` 会输出 `Provisioning STATUS_KV... STATUS_KV provisioned 🎉` 并完成绑定。按钮走的是 Workers Builds 的等价路径（resource id 只在 dashboard 可见、不回写仓库），与 CLI 的「回写 wrangler.jsonc」行为不同，该路径未单独实测。
>
> 反过来说：模板里**不能**写占位 id。wrangler 判定「是否需要自动创建 KV」看的是 `id` 是否为空——写个占位串会被当成「id 已指定」，于是跳过 provision 并把占位串当真实 id 上传（dry-run 查不出来，只有真实部署才会失败）。

- 每个用户花自己的额度 → 各自独立，互不影响。
- App 端只需填「自己的 Worker 地址 + 自己的 Token」。
- 无自有域名也能用（默认 `*.workers.dev` 子域）。

### 模式 B（可选，v2）：单实例多设备

若确实想做「一台实例服务多台设备」，需要：
1. KV key 加设备前缀：`device_status:<deviceId>`、`current_mood:<deviceId>`；
2. 每设备独立 token（`DEVICE_TOKENS` 作为 JSON secret，或 `token:<deviceId>` 存 KV）；
3. 网页支持 `?device=<id>` 选择或并排展示；
4. **必须升级 Workers Paid**（$5/月，KV 写 100 万/月），否则 7 人以上就会失败。

> v1 只实现模式 A；模式 B 的记录留在此处，避免将来重复评估。

---

## 8. App 自定义设置（完整清单）

### 8.1 用户可配置项（App 内「设置」页）

| 分组 | 项 | 类型 / 默认 | 说明 |
|---|---|---|---|
| **服务器** | 上报地址 Endpoint | 文本，必填，默认空 | 用户自己的 Worker 地址（`https://xxx.workers.dev` 或自有域名）；保存前做 URL 与 scheme 校验，只允许 https |
| | 设备 Token | 文本，必填，默认空 | 与 Worker 的 `AUTH_TOKEN` 一致；输入框做遮挡 + 显隐切换 |
| | 连接测试 | 按钮 | 点一下发一次心跳，立刻显示 HTTP 状态码与结果，避免用户填错后盲等 |
| **上报** | 启用常驻上报 | 开关，默认关 | 首次安装默认关，配好服务器再开 |
| | 上报间隔 | 单选 60 / 300 / **600** / 900 秒 | < 300 秒的选项标注「仅在亮屏时生效」（Doze 下精确闹钟最低约 9 分钟） |
| | 开机自启 | 开关，默认开 | `BOOT_COMPLETED` |
| | 立即上报 | 按钮 | 手动触发一次，绕过节流 |
| **上报字段** | 电量百分比 | 开关，默认开 | 关掉后网页不显示电量 |
| | 充电状态 | 开关，默认开 | |
| | 电池温度 | 开关，默认**关** | 隐私更保守的默认值 |
| | 网络类型 | 开关，默认**关** | 同上 |
| | 设备名称 | 文本，默认空 | 留空则网页不显示；**注意全世界可见** |
| **心情** | 启用心情 | 开关，默认开 | 关闭后隐藏首页心情卡片 |
| | 心情表情 | 单行文本 | **自己输入**（用系统输入法的 emoji 面板挑，不做固定候选列表）；按 UTF-16 单元 ≤ 8 截断，与云端校验同口径 |
| | 心情文案 | 多行文本 | 本地落草稿（debounce 500ms），防误触丢失；表情与文案一起存，同一次磁盘写入 |
| **本机记录** | 记录本机电量历史 | 开关，默认**开** | 首页电量图表的数据源；采样是上报的副产物，上报关闭时也不记 |
| | 清空记录 | 按钮 + 二次确认 | 只删 `battery_samples`；关掉开关只是不再记新的，已有历史仍在 |
| | 记录本机心情历史 | 开关，默认**开** | 与电量开关**独立**；只记真的发送成功的那条，草稿/去重跳过/失败都不记 |
| | 清空心情记录 | 按钮 + 二次确认 | 只删 `mood_events`，**电量历史不受影响**（反之亦然：两个入口刻意不合并） |
| | 查看全部心情 | 页面入口（两处） | 首页「最近的心情」卡片底部的「查看全部」，以及本组那行「已记录 N 条」**本身**（有记录就可点）。按天分组、**一次读全量、不分页**、文案不截断；**不做单条删除**——删掉本机这条，云端 `history_mood` 与公开页上那条都还在，给了按钮只会让人以为「删干净了」 |
| **保活** | 电池优化白名单 | 状态 + 引导按钮 | 未加白时首页显示黄色提示 |
| | 精确闹钟 | 状态 + 引导按钮 | Android 14+ 默认拒绝 |
| | 系统自启动 | 用户确认状态 + 引导 + 确认/撤销 | 按厂商启发式显示；不查询系统开关，跳设置页后由用户确认，可随时撤销（见 9.8） |
| **通知** | 常驻通知 | 开关，默认开 | 关掉只改可见性策略，前台服务仍必须带通知 |
| **语言** | 界面语言 | 跟随系统 / 简体 / 繁體 / English | 复用 RainyToken 的 `LocaleManager` 逻辑 |
| **调试** | 调试日志 | 页面入口 | 记录每次上报的触发源、耗时、响应码、退避、降频 |
| **关于** | 版本 | 只读 | `BuildConfig.VERSION_NAME` |
| | 本项目地址 | 按钮 | 跳 GitHub |

### 8.2 云端可配置项（`cloud/wrangler.jsonc` 的 `vars`）

| 变量 | 默认 | 说明 |
|---|---|---|
| `SITE_TITLE` | `RainyStatus` | 网页标题 |
| `OWNER_NAME` | `WaterRainCat` | 网页上显示的主人名字 |
| `AVATAR_EMOJI` | `☔` | 头像字符（没配图片时用它） |
| `AVATAR_URL` | 空 | 头像图片：站内路径 `/avatar.png`（图放 `cloud/public/`）、`https://` 外链、或小尺寸 `data:image/png;base64,…`。配了用图片，加载失败自动退回 `AVATAR_EMOJI`；`http://` 不收（会被当混合内容拦掉） |
| `OFFLINE_THRESHOLD_MS` | `1800000` | 掉线阈值（30 分钟） |
| `SHOW_TEMPERATURE` | `false` | 网页是否展示温度（即使上报了也可隐藏） |
| `SHOW_NETWORK` | `false` | 同上 |
| `SHOW_MOOD` | `true` | 是否展示心情 |
| `TIMEZONE_OFFSET_MINUTES` | `auto` | 网页时间显示时区。`auto`（默认）＝按访客自己的时区；填数字（如 `480`）＝固定 UTC+8 |
| `DEFAULT_LANG` | `zh-Hans` | `zh-Hans` / `zh-Hant` / `en`。**仅作兜底**：浏览器语言未命中受支持语言时才用它 |
| `NEXT_EXPECTED_MS` | `600000` | 下一条心跳的期望间隔（毫秒）—— 也随 `/api/heartbeat` 响应下发 |
| `STATUS_TEXT_ONLINE` | 空 | 在线时状态徽章的文案。留空用内置三语（跟访客语言切换）；填了固定用这句，**不跟语言切换**，最多 40 字 |
| `STATUS_TEXT_OFFLINE` | 空 | 同上，最近一次心跳超过掉线阈值时 |
| `STATUS_TEXT_GONE` | 空 | 同上，长时间没有任何上报时 |
| `STATUS_TEXT_NO_DATA` | 空 | 同上，从未收到过心跳时 |

> 网页访问口令**不在 v1 范围**：静态资源在 Worker 之前命中，口令逻辑无处安放，
> 需连同 `run_worker_first` 一起设计。因此这里不列出该变量，避免部署者以为它已生效。

这些变量在 Deploy to Cloudflare 的配置页里可被用户直接改，也会写回他们自己的仓库。

### 8.3 设计原则

- **App 不硬编码任何地址和 token** —— 换服务器只需改设置，不用重装。
- **云端不硬编码任何个人信息** —— 名字、标题、emoji 全走 `vars`，别人部署后看到的是自己的。
- **默认值保守** —— 温度/网络/设备名默认不上报，用户主动开启才采。
- **不申请无关权限** —— 见第 9 节。

---

## 9. Android 端设计

### 9.1 工程骨架（与 RainyToken 同构）

| 项 | 取值 |
|---|---|
| namespace / applicationId | `com.rainy.status` |
| compileSdk / targetSdk / minSdk | 35 / 35 / 31 |
| JVM / Gradle wrapper | 17 / 9.1.0 |
| AGP / Kotlin / KSP / Hilt | 9.0.0 / 2.3.10 / 2.3.9 / 2.59.2 |
| Compose BOM / DataStore | 2024.10.01 / 1.1.1 |
| 网络 | OkHttp 4.12.0 + kotlinx-serialization 1.7.3（只有一个上报 + 一个心情端点，**不上 Retrofit**） |

> **v1 明确不需要**：WebKit、Firebase/FCM。

> **Room 是例外（2026-10 起启用）**：本机电量历史一开始是 DataStore 里的一个 JSON blob（7 天 / 2048 点上限），因为「保留期永久」把上限这条路堵死了——10 分钟一条一年就 5 万条，再整段重写不可接受。于是照 RainyToken 换成 Room 2.7.1 + KSP，现在有**两张表**：`battery_samples`（电量，写一条是一次 INSERT）与 `mood_events`（心情，主键就是事件时刻）。加第二张表时手写了 `MIGRATION_1_2`——`exportSchema = false` 且没配 `room.schemaLocation`，`AutoMigration` 在这个配置下根本用不了。**离线队列仍然用 DataStore**（压成「最多一条待发」，不值得上 Room）。

**必须复刻 RainyToken 的两段 ARM64 proot workaround**（`aapt2` 强制 `linux-aarch64` + `guardReleaseResources`），否则 Release APK 会静默缺资源。

### 9.2 前台服务类型：`specialUse`（不是 `dataSync`）

**关键坑**：Android 15 起 `dataSync` 类型的 FGS 在 24 小时内**累计只能跑 6 小时**，超时回调 `Service.onTimeout()` 要求几秒内 `stopSelf()`，否则抛 `RemoteServiceException`。常驻心跳必然触发。

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".service.StatusHeartbeatService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Periodic device battery and presence heartbeat to the user's own status dashboard" />
</service>
```

选它的理由：① 不在 6 小时超时名单；② **不在** Android 15 的 BOOT_COMPLETED 禁启动名单（名单只有 `dataSync`/`camera`/`mediaPlayback`/`phoneCall`/`mediaProjection`/`microphone`）；③ 无运行时权限前置条件。

### 9.3 定时：三通道互补

| 通道 | 机制 | 生效场景 |
|---|---|---|
| **A** | FGS 内协程 `delay` 循环（用 `SystemClock.elapsedRealtime()` 校正漂移） | 亮屏 / 设备活跃 —— 最准时 |
| **B** | `AlarmManager.setExactAndAllowWhileIdle`（一次性自续，非 `setRepeating`） | 息屏 / Doze —— 但同应用**最低约 9 分钟**、低电耗模式可拉长到 ~15 分钟 |
| **C** | 事件即时上报 | 充电插入/拔出（无条件）、解锁、网络恢复、开机 |

- `canScheduleExactAlarms() == false` 时降级 `setAndAllowWhileIdle`，并在保活卡片里提示（首页「精确闹钟」那一行的状态文案即是该提示）。
- `WAKE_LOCK` 权限**声明但未使用**（上报是短请求，不需要持锁），保留在 Manifest 里仅为将来若要长任务的预留。
- WorkManager **不在 v1 实现范围**：原计划作兜底（周期 30 分钟 + `NetworkType.CONNECTED` + `ExistingPeriodicWorkPolicy.KEEP`），但 Doze 下 JobScheduler 会整体停摆，兜底价值有限，而多一个后台调度通道就多一份耗电与被系统限制的风险。v1 只保留 A/B/C 三通道。

### 9.4 事件触发节流（防打爆 KV）

| 事件 | 行为 |
|---|---|
| 充电插入 / 拔出 | **立即上报**（低频、语义重要，不受节流） |
| 电量变化 | **Δ% ≥ 3 且距上次 ≥ 120s** 才发，否则只更新本地状态 |
| 屏幕点亮 / 解锁 | 距上次成功上报 ≥ 间隔时补一次 |
| 网络恢复 | 队列有待发 → 立即冲刷 |

### 9.5 离线队列（压成「只剩最新一条」）

```kotlin
@Serializable
data class PendingReport(val payload: HeartbeatPayload, val firstQueuedAt: Long, val attempts: Int)
```

- 入队规则：已有待发时，**无意义差异**（电量差 < 1%、充电状态未翻转、无心情）→ 丢弃新的；**有意义差异** → 用新的**替换**旧的（旧电量已过时，发出去只会污染云端）。
- 结果：断网 8 小时恢复后只发 **1 条**（最新），不是 96 条。云端曲线有段空缺是**有意为之**——8 小时无心跳本来就是掉线状态。
- 退避：初始 30s → ×2 → 上限 15 分钟，±20% 抖动；任意成功即重置。
- 失败分类：`401/403` 凭据失效**不重试**（UI 提示重填）；`400/415` 契约错误**不重试**（写调试日志）；`429` 按 `Retry-After`；`5xx`/IOException 退避重试。

### 9.6 权限清单

**需要**：`INTERNET`、`ACCESS_NETWORK_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`（非 FGS 前置，仅通知可见性）、`RECEIVE_BOOT_COMPLETED`、`SCHEDULE_EXACT_ALARM`（特殊权限，用户手动开）、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。

**已声明但未使用（预留）**：`WAKE_LOCK`——上报是短请求，不需要持锁；留在 Manifest 里只为将来若引入长时间后台任务。

**不要**：`USE_EXACT_ALARM`（仅闹钟/日历类合规）、`ACCESS_BACKGROUND_LOCATION`、`CAMERA`、`RECORD_AUDIO`、`BODY_SENSORS`、`QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE_DATA_SYNC`。

### 9.7 真实风险：HyperOS 杀后台

用户 build、非 root 下**无法根治**。缓解手段：FGS + 电池白名单 + 自启动 + 任务卡加锁 + 闹钟兜底。**必须接受「偶尔漏心跳、甚至连续几小时无心跳」的现实**——云端的 30 分钟阈值给了容错窗口（漏 2 次不判掉线，漏 4 次才判）。

### 9.8 系统「自启动」：为什么只做引导 + 用户确认

保活三件套里，前两项（电池优化白名单、精确闹钟）都有 AOSP 标准接口可查：
`PowerManager.isIgnoringBatteryOptimizations()` 与 `AlarmManager.canScheduleExactAlarms()`。
第三项（厂商「自启动」开关）在本项目中没有可靠的跨厂商公开查询接口；项目也未采用厂商非公开接口。以下仅是本机 HyperOS V816（Android 16）的 **shell（uid 2000）观察**，不是普通 App 权限测试，不能据此断言 App 绝对无权读取：

| 探测方式 | 本机观察 |
|---|---|
| `cmd appops get <pkg> AUTO_START` | 返回 `Unknown operation string` |
| `cmd appops get <pkg> 10008` | shell 可读到 `MIUIOP(10008): allow/ignore`；未验证普通 App 的查询能力 |
| `cmd appops query-op 10008` | 返回 540 行；不据此推断普通 App 权限 |
| `RUN_ANY_IN_BACKGROUND` | 当次扫描的第三方包未发现 `ignore`；不能推广为所有 MIUI 设备恒为 `allow`，也不能替代自启动状态 |
| `miui.intent.action.OP_AUTO_START` | 本机 `resolve-activity` 解析到 `com.miui.permcenter.autostart.AutoStartManagementActivity`，不保证其他系统版本 |

方案是「引导 + 可撤销的用户确认」：跳转厂商设置页后，用户返回点击「我已开启」，App 记录**用户自述**（`AppSettings.autostartConfirmed`）。首页与设置页均显示「未确认 / 你已确认」，不把未知显示成未开启。已确认时，两页都提供带「撤销确认」说明的矢量图标按钮；撤销恢复未确认，**不修改系统开关**。

实现要点：

- **跳转顺序**：厂商 action（`AUTOSTART_ACTIONS`）→ ComponentName（`AUTOSTART_COMPONENTS`，小米/华为/OPPO/vivo）→ `openAppDetails` 兜底。非标准入口存在版本差异，不保证跨版本稳定。三星 BatteryActivity 仅为电池管理入口，未验证自启动语义，已从入口及显示关键词中移除。
- **启发式显示**：`PermissionUtils.needsOemAutostartGuide()` 用 `Build.MANUFACTURER + Build.BRAND` 匹配关键词（xiaomi/redmi/poco/huawei/honor/oppo/realme/oneplus/vivo/iqoo/meizu/letv/smartisan）；命中不代表检测到开关，未命中也不代表设备没有后台限制。没有专用 component 的厂商依靠 action 或应用详情页兜底。
- **`allGood` 必须带上厂商条件**：`... && (!needsAutostartGuide || autostartConfirmed)`，否则原生机器永远显示「还有一项没做」。
- **这只是给用户看的自我确认**，不参与上报逻辑——服务能否拉起仍由系统决定。语义上**不是** `AppSettings.autostart`（那是 App 自己的开机自启开关，控制 `BootReceiver` 是否拉起服务），两者只是名字像，KDoc 里都写明了区别。

---

## 10. 三语与文案

- 资源目录（AGP 9 不接受 `values-zh-Hans`，必须 BCP-47 写法）：
  - `values/` → 英文（fallback，`unqualifiedResLocale=en`）
  - `values-b+zh+Hans/` → 简体
  - `values-b+zh+Hant/` → 繁體
- 品牌名 `translatable="false"`；`app_name` 跟随语言（中文系统显「雨晴Status」，其他显 `RainyStatus`）。
- **网页端**三语：前端 JS 字典 + `navigator.language` 自动判定；支持 `?lang=` 覆盖并写入 `localStorage`，同步 `<html lang>`。`DEFAULT_LANG` 只在**浏览器语言未命中任何受支持语言**时兜底（否则默认值 `zh-Hans` 会把英文访客强制切成中文）。
- 网页诚实标注新鲜度：`<60s` →「刚刚」；`<60min` →「N 分钟前」；否则「N 小时 M 分钟前」。页脚注明「数据最多可能滞后约 1 分钟」。
- **网页头像可以是图片**：`AVATAR_URL` 支持站内路径（图放 `cloud/public/`）、`https://` 外链、小尺寸 `data:image/…;base64,…`；图片加载失败自动退回 `AVATAR_EMOJI`，标签页图标同步用这张图。`http://` 一律不收——页面是 https，它会被浏览器当**混合内容**直接拦掉，配了也是空白。
- **网页时间的时区**：默认 `TIMEZONE_OFFSET_MINUTES=auto` → 用**访客浏览器所在时区**渲染（`new Date()` 本地读数），页脚同时标注当前生效偏移（如 `UTC+08:00`）。填数字偏移则固定时区、所有人同一时钟。**服务端与 App 传递的时间一律是 epoch 毫秒 UTC**，时区只影响展示，不影响任何判定。
- **App 心情历史页的日期与时刻**：格式串放在字符串资源里（`mood_history_day_format` / `mood_history_day_format_year` / `mood_history_time_format`），用 `DateTimeFormatter.ofPattern(pattern, locale)` 解析——中文 `M月d日` / `yyyy年M月d日` / `HH:mm`，英文 `MMM d` / `MMM d, yyyy` / `h:mm a`。**换语言时别顺手改这些字母**（`M`/`d`/`yyyy`/`HH` 是 pattern，不是文案）。
- **网页响应式**：窄屏（< 720px）保持单列竖排卡片（`max-width: 420px`，手机观感）；`@media (min-width: 720px)` 起切两列宽屏（卡片放宽到 880px，左列状态+电量、右列心情+明细）。桌面浏览器打开时按桌面习惯排版，不再是一根手机竖条。

---

## 11. 仓库结构

```
RainyStatus/
├── app/                          # Android 模块
├── cloud/
│   ├── wrangler.jsonc
│   ├── src/
│   │   ├── index.ts              # 路由分发 + 错误兜底
│   │   ├── routes/{status,heartbeat,mood,health}.ts
│   │   ├── lib/{kv,auth,validate,response}.ts
│   │   └── types.ts
│   ├── public/                   # 静态网页（免费不限量）
│   │   ├── index.html  app.js  style.css  _headers
│   ├── .dev.vars.example
│   └── package.json
├── docs/design.md                # 本文件
├── docs/api.md                   # 接口契约
├── .github/workflows/ci.yml      # 单测 + lint + 构建 + Worker dry-run
├── .github/workflows/release.yml # tag v* 触发
├── README.md
├── AGENTS.md / taste.md / LICENSE
```

---

## 12. 待决策清单

| # | 决策点 | 建议 |
|---|---|---|
| D1 | Cloudflare API Token（部署用） | 官方模板 **Edit Cloudflare Workers**，资源范围限本账号 + `waterraincat.com` |
| D2 | 首版版本号 | `versionCode 1` / `versionName "1.0.0"`，由水晴喵定 |
| D3 | 保活权限是否引导（电池白名单 + 精确闹钟） | **需要**，不加白名单红米上 10 分钟精度做不到；仅影响 Google Play 上架场景 |
| D4 | 公开字段边界 | 温度 / 网络 / 设备名默认**关**，用户主动开 |
| D5 | 是否允许提交现有骨架 | 需水晴喵点头 |
| D6 | 是否上架 Google Play | 自用分发则 `specialUse` 零顾虑；上架需 FGS 声明 + 演示视频 |
| D7 | 模式 B（单实例多设备）是否要做 | v1 不做；要做需升 Workers Paid |
