# 🌧️ 雨晴Status (RainyStatus)

> *"让朋友知道你还在冒泡 — let people know you're still alive"*

[![CI](https://github.com/CATMIAOZHI/RainyStatus/actions/workflows/ci.yml/badge.svg)](https://github.com/CATMIAOZHI/RainyStatus/actions/workflows/ci.yml)
[![Release](https://github.com/CATMIAOZHI/RainyStatus/actions/workflows/release.yml/badge.svg)](https://github.com/CATMIAOZHI/RainyStatus/actions)
Android 手机电量与在线状态实时上报 —— 定时把电量、充电状态、心跳发到自己的服务器，任何人打开一个网页就能看到「电量多少、人还在不在」。粉色调雨晴品牌 UI，配套 Cloudflare Worker 状态页。
RainyStatus（雨晴Status）— Live battery & heartbeat status page · the Rainy Family tools.

---

## ✨ 它长什么样

```
┌─────────────────────────────────┐
│ ☔  RainyStatus                  │
│                                 │
│ ● 还在冒泡                       │
│   2026-09-30 05:12（2 分钟前）    │
│                                 │
│ 电量                     87%    │
│ ████████████████████░░░         │
│ 充电中                           │
│                                 │
│ 现在的心情                       │
│ 😴 困了喵…                       │
│                                 │
│ 最后在线   2026-09-30 05:12      │
│                                 │
│ 数据来自手机定时上报，最多可能滞后 │
│ 约 1 分钟。                      │
└─────────────────────────────────┘
```

掉线时显示「已掉线」+ **最后在线时刻**与**已掉线时长**（超过 6 小时显示「好像很久没消息了」）。

---

## 🚀 快速开始

### 1. 部署你自己的云端（1 分钟）

每个人**部署自己的实例**，各自花自己的 Cloudflare 免费额度，互不影响。

[![Deploy to Cloudflare](https://deploy.workers.cloudflare.com/button)](https://deploy.workers.cloudflare.com/?url=https://github.com/CATMIAOZHI/RainyStatus/tree/main/cloud)

点一下 → Cloudflare 会自动克隆仓库到你的 GitHub、**自动创建并绑定 KV**、构建部署。
部署后在 Cloudflare 控制台给这个 Worker 添加一个 secret：`AUTH_TOKEN`（值随便一串长随机字符，`openssl rand -base64 48` 就行）—— 这就是手机 App 要填的 Token。

> **为什么不能所有人共用一台？** Workers KV 的免费额度（1,000 写/天）是**整个 Cloudflare 账号级**的。10 分钟一次心跳 = 144 写/天，约 6 个人共用一台就会打爆，而且一个 token 泄露影响所有人。所以默认「一人一台」。

### 2. 装 App、填两个值

安装 APK → 设置页填 **上报地址**（你的 Worker 地址）和 **设备 Token**（刚设的 `AUTH_TOKEN`）→ 点「连接测试」确认通了 → 打开「启用常驻上报」。

### 3. 完事

打开 `https://<你的-worker>.workers.dev` 就能看到状态页。

---

## ✨ 功能特性

| 特性 | 说明 |
|------|------|
| 🔋 **电量上报** | 定时上报电量百分比、充电状态、充电方式；（可选）电池温度、网络类型 |
| 💓 **心跳与在线状态** | 超过 30 分钟（可配）无心跳即判掉线，网页显示**最后在线时刻**与**已掉线时长** |
| 😴 **心情状态** | App 里发一条心情，网页独立展示；与心跳分开存，心跳不会覆盖心情 |
| 📈 **本机电量历史** | 首页图表卡片：24 小时折线 + 充电竖带、7 天每日区间柱与当天充电次数。数据**只存本机**，默认关（设置 → 本机记录） |
| 🌐 **三语网页** | 简体 / 繁體 / English；浏览器语言自动判定，可手动切换，支持 `?lang=` 分享 |
| 🌙 **深色模式** | 网页与 App 均自适应系统深浅色 |
| ⚙️ **App 全自定义** | 服务器地址、Token、上报间隔、上报哪些字段、设备名、心情、通知、语言全部可配 |
| 🔧 **云端全自定义** | 站点标题、主人名字、头像、时区、展示哪些字段 —— 全在 `wrangler.jsonc` 的 vars 里，别人部署后看到的是自己的 |
| 🔐 **安全** | Bearer Token 鉴权 + 常数时间比较 + 字段白名单 + 请求体大小限制；网页渲染全部走 `textContent`（无 XSS） |
| 🙈 **隐私保守** | 默认**不**上报温度/网络/设备名；不采集 IP、位置、SSID、IMEI |
| 📱 **前台服务常驻** | Android 15 安全（`specialUse` 类型，避开 `dataSync` 的 6 小时上限） |
| 🔁 **离线队列** | 断网时只保留最新一条待发，恢复后只发 1 条（不会把额度打爆） |
| 💰 **¥0/月** | Cloudflare Workers 免费版：KV 写 144/天（占 14.4%），网页浏览量**免费且不限量** |

---

## 🏗️ 架构

```
┌──────────────────┐        ┌───────────────────────────┐        ┌────────┐
│  Android App     │ 每 10  │  Cloudflare Worker        │  HTTPS │ 浏览器 │
│  前台服务         │ 分钟    │  <你的域名>                │◀───────│        │
│  电量/充电/心跳   │──POST──▶│                           │        └────────┘
└──────────────────┘ Bearer │   ┌───────────────────┐   │
                            │   │ Workers KV        │   │
                            │   │ device_status     │   │
                            │   │ current_mood      │   │
                            │   └───────────────────┘   │
                            │  /            静态网页（免费不限量）
                            │  /api/status  公开只读
                            │  /api/heartbeat  上报（需 Token）
                            │  /api/mood       心情（需 Token）
                            │  /api/health     探活
                            └───────────────────────────┘
```

- **默认无数据库**：只有 1 个 Worker + 1 个 KV namespace + 1 个静态资源目录。历史图表是可选的，开了才多一个 D1（见「云端 · 历史图表」）。
- 网页做成**静态资源**（Cloudflare 官方：*Requests to static assets are free and unlimited*）→ 网页访问不吃任何额度；即使免费额度耗尽（错误码 `1027`），**网页照常打开、只有 API 报错**。
- 详细设计见 [`docs/design.md`](docs/design.md)，接口契约见 [`docs/api.md`](docs/api.md)。

---

## ⚙️ 可配置项

### App 内（设置页）

| 分组 | 项 |
|------|----|
| 服务器 | 上报地址、设备 Token、连接测试 |
| 上报 | 启用开关、上报间隔（60/300/**600**/900 秒）、开机自启、立即上报 |
| 上报字段 | 电量、充电状态、（默认关）电池温度、（默认关）网络类型、设备名称 |
| 心情 | 启用开关、心情文案 |
| 本机记录 | **记录本机电量历史**（默认关）；首页电量历史图表的数据源 |
| 保活 | 电池优化白名单、精确闹钟、系统自启动引导（激进厂商显示，开启后可确认） |
| 通知 | 常驻通知开关 |
| 语言 | 跟随系统 / 简体 / 繁體 / English |
| 调试 | 调试日志 |

### 云端（`cloud/wrangler.jsonc` 的 `vars`）

| 变量 | 默认 | 说明 |
|------|------|------|
| `SITE_TITLE` | `RainyStatus` | 网页标题 |
| `OWNER_NAME` | `WaterRainCat` | 网页上显示的主人名字 |
| `AVATAR_EMOJI` | `☔` | 头像字符（没配图片时用它） |
| `AVATAR_URL` | 空 | 头像图片。三种写法：站内路径 `/avatar.png`（把图放进 `cloud/public/`）、`https://` 外链、小尺寸 `data:image/png;base64,…`。配了就用图片，**图片加载失败会自动退回 `AVATAR_EMOJI`**；`http://` 会被浏览器当混合内容拦掉，所以不收 |
| `OFFLINE_THRESHOLD_MS` | `1800000` | 掉线阈值（30 分钟） |
| `NEXT_EXPECTED_MS` | `600000` | 上报间隔提示（10 分钟） |
| `SHOW_TEMPERATURE` | `false` | 网页是否展示温度 |
| `SHOW_NETWORK` | `false` | 网页是否展示网络类型 |
| `SHOW_MOOD` | `true` | 网页是否展示心情 |
| `TIMEZONE_OFFSET_MINUTES` | `auto` | 网页时间显示时区。`auto`（默认）＝**按访问者自己的时区**显示；填数字（如 `480`＝UTC+8）则所有人看到同一时钟 |
| `DEFAULT_LANG` | `zh-Hans` | `zh-Hans` / `zh-Hant` / `en`。**仅作兜底**：浏览器语言未命中受支持语言时才用它 |
| `STATUS_TEXT_ONLINE` | 空 | 在线时状态徽章的文案（如「还在冒泡」）。留空用内置三语；填了固定用这句，**不跟语言切换**，最多 40 字 |
| `STATUS_TEXT_OFFLINE` | 空 | 同上，最近一次心跳超过掉线阈值时 |
| `STATUS_TEXT_GONE` | 空 | 同上，长时间没有任何上报时 |
| `STATUS_TEXT_NO_DATA` | 空 | 同上，从未收到过心跳时 |

**展示开关是服务端过滤，不是"藏起来"**：`SHOW_TEMPERATURE` / `SHOW_NETWORK` 关掉后，`/api/status` 返回的对应字段本身就是 `null`；`SHOW_MOOD` 关掉后 `mood` 整体为 `null`。直接 `curl` 也拿不到。

### 云端 · 历史图表（可选，**默认全关**）

不配置就**完全没有**历史接口，也不会记录任何历史——别人部署时不会不小心公开自己的作息。

要开就三步（详细见 `cloud/wrangler.jsonc` 末尾注释）：

1. `npx wrangler d1 create rainystatus-history`，把输出的 `database_id` 填进 `d1_databases`（binding 必须是 `HISTORY_DB`）；
2. `npx wrangler d1 execute rainystatus-history --remote --file=./migrations/0001_history.sql`；
3. 打开 `triggers.crons`（`*/15 * * * *`），并把下面这些 vars 配上。

| 变量 | 默认 | 说明 |
|------|------|------|
| `HISTORY_ENABLED` | `false` | 是否对外提供 `/api/history`（公开开关） |
| `HISTORY_COLLECT` | 跟随 `HISTORY_ENABLED` | 是否记录历史。可以「只记不晒」（先攒数据）或「只晒不记」（停止记录但保留已有历史） |
| `HISTORY_RANGES` | 空 | 公开哪些档位，逗号分隔。目前只实现 `24h`；**没写进来的档位一律 404** |
| `TURNSTILE_SITE_KEY` | 空 | Turnstile 的 site key（公开值，会下发给前端渲染验证组件） |
| `TURNSTILE_REQUIRED` | `true` | 是否强制人机验证。**默认就是必须**；确实想免验证（比如只在内网用）要显式写 `false` |
| `TURNSTILE_SECRET` | — | **密钥**，用 `npx wrangler secret put TURNSTILE_SECRET` 写入，**绝不要写进 `vars` 或提交** |

> **fail-closed**：要求验证（默认）却没配密钥时，`/api/history` **整体关闭**（而不是退化成「不验证」）——否则会让人以为已经受保护了。此时页面会直说原因，采集也照常进行；补齐密钥并重新部署后，下一个 Cron 周期（≤15 分钟）就能出图。

数据分层与保留期：原始 10 分钟点 **7 天** / 小时汇总 **35 天** / 日汇总 **5 年**（约 1830 行、几 MB），见 `DEFAULT_RETENTION`。稳态总行数约 3700 行。

> 这些值在 Deploy to Cloudflare 的配置页里也能直接改。

---

### App · 本机电量历史（**默认关**）

首页的图表卡把手机自己的电量历史画出来，数据**只存在本机**（独立 DataStore：`rainystatus_history`，保留 7 天、上限约 2048 点），不上传、不吃云端额度：

- **24 小时**：电量折线（断档不连线）+ 绿色充电竖带，下面一行「最近 / 最低 / 最高 / 充电次数」；
- **7 天**：每天一根「最低–最高」电量区间柱，柱下是该天的充电次数（按**设备本地日**切分，跨午夜的一次充电只算一次）。

采样是上报链路的**副产物**：每次采样上报（含被门控挡下的那一轮）顺手记一个点，值没变且距上一点不到 9 分钟就不重复记；补发断网期间积压的那条旧数据时**不补记**（那不代表现在的状态）。所以**上报关闭时不记录**——这正是 `docs/roadmap.md` 里 T5「服务关闭也记录」没有做的原因；要全天曲线得另做一套采集与保活策略。

---

## 🔧 手动部署（不想用一键按钮）

> 需要 **Node ≥ 22**：wrangler 4.x 启动时会硬性校验版本，Node 20 下第一个
> `npx wrangler` 命令就会报 `Wrangler requires at least Node.js v22.0.0`
> （`npm install` 阶段也会先给一条 EBADENGINE 警告）。
> `cloud/package.json` 的 `engines` 与 CI 的 `node-version` 都按这个下限对齐。

```bash
cd cloud
npm install

# 1) 建 KV namespace（记下输出的 id）
npx wrangler kv namespace create STATUS_KV

# 2) 把 id 填进 wrangler.jsonc 的 kv_namespaces[0]：
#       { "binding": "STATUS_KV", "id": "<上一步的 id>" }
#    模板里刻意没写 id：写了占位串会被 wrangler 当成「id 已指定」而跳过
#    自动创建。忘了填也不会报错 —— CLI 会自动建一个 KV 并把 id 回写进
#    wrangler.jsonc（这个受跟踪文件会被改动，注意别连同它一起提交）。

# 3) 设置鉴权 token（非交互；交互式终端会转成密码提示）
printf '%s' "$(openssl rand -base64 48)" | npx wrangler secret put AUTH_TOKEN

# 4) 部署
npx wrangler deploy

# 5) 验证
curl https://<你的-worker>.workers.dev/api/health
curl https://<你的-worker>.workers.dev/api/status
curl -X POST https://<你的-worker>.workers.dev/api/heartbeat \
  -H "Authorization: Bearer <你的 TOKEN>" -H 'Content-Type: application/json' \
  -d '{"schemaVersion":1,"batteryPercent":87,"charging":true,"chargeSource":"ac"}'
```

### 绑定自己的域名（可选）

同账号下 zone 处于 active、且该主机名**没有已存在的 CNAME** 时，取消 `wrangler.jsonc` 末尾 `routes` 的注释并改成你的域名，同时把 `workers_dev` 改成 `false`（收敛扫描面）。Cloudflare 会自动建 DNS 记录并签发证书（可能等 1–2 分钟）。

> **本仓库维护者请注意**：上面这段是给「部署自己实例的读者」的通用说明。
> **本仓库自己**不要把真域名与真 KV id 提交进仓库 —— `wrangler.jsonc` 是给所有人
> 一键部署用的模板，里面留着真域名会让别人的部署因为「zone 不存在」而失败。
> 本仓库的做法是把它放进 `cloud/wrangler.prod.jsonc`（已被 `.gitignore` 忽略，**不进仓库**），
> 部署线上实例时：
>
> ```bash
> npx wrangler deploy -c wrangler.prod.jsonc
> ```
>
> 改动 `wrangler.jsonc` 的公共项（assets / vars / 兼容日期）时，记得同步 prod 配置；
> 而且改的是**你自己 fork 里的副本**，不要把这边的模板改回带真域名/真 id 的版本。

---

## 📦 构建

```bash
# 本地跑单测/lint/build 前必须设签名环境变量（build.gradle.kts 会强制校验）
KEYSTORE_PASSWORD=dummy KEYSTORE_ALIAS=dummy KEY_PASSWORD=dummy ./gradlew testDebugUnitTest
KEYSTORE_PASSWORD=dummy KEYSTORE_ALIAS=dummy KEY_PASSWORD=dummy ./gradlew lintDebug
KEYSTORE_PASSWORD=dummy KEYSTORE_ALIAS=dummy KEY_PASSWORD=dummy ./gradlew assembleDebug
```

> 没有 `release.jks` 时，本地（非 CI）跑**任何** Gradle 任务都需要这组变量或 `CI=true`：
> `app/build.gradle.kts` 在配置阶段就会校验 release 签名凭据，缺失直接抛 `GradleException`。

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

### 云端（`cloud/`）

> 需要 **Node ≥ 22**（wrangler 4.x 的硬性下限，见上方「手动部署」开头）。

```bash
cd cloud
npm install
npm run typecheck   # tsc --noEmit —— wrangler 只打包不检查类型，这一步不能省
npm run test        # vitest —— readBoth 的 KV Map 回归测试
npm run dry-run     # 真正只做打包（不上传）
```

---

## 📋 免费额度怎么算

| 资源 | 免费额度 | 本项目（10 分钟心跳） |
|------|---------|---------------------|
| KV 写 | 1,000 / 天（**账号级**） | 144 心跳 + 少量心情 ≈ **15%** |
| KV 读 | 100,000 / 天 | 每次看网页读 2 个 key |
| Workers 请求 | 100,000 / 天（账号级） | 144 上报 + API 调用；**网页浏览免费不限量** |
| KV 存储 | 1 GB | < 1 KB |
| 费用 | — | **¥0 / 月**（只有你自己的域名续费） |

App 内置**写预算兜底**：单日写超过 600 次自动降频到 15 分钟并提示，防止意外打爆额度。

---

## ⚠️ 已知限制

- **5~10 分钟精度在息屏/Doze 下会漂**。Android 的 Doze 会让精确闹钟最低间隔变成约 9 分钟，某些低电耗模式下更长。亮屏时最准。
- **HyperOS / MIUI 等国产系统杀后台严重**，非 root 无法根治。App 内提供电池白名单、精确闹钟、自启动引导，建议全部开启。自启动状态没有本项目可依赖的跨厂商公开查询接口，项目未采用厂商非公开接口（详见 `docs/design.md` 9.8）。App 引导你去设置，再由你点「我已开启」；首页与设置页显示「未确认 / 你已确认」并支持撤销确认。这不是系统检测结果，确认或撤销均不修改系统开关。云端 30 分钟阈值给了容错窗口（漏 2 次不判掉线）。
- **KV 是最终一致**的，读缓存固定 30 秒，所以网页数据最多可能滞后约 10 分钟 + 30 秒。网页上如实标注，不宣称「实时」。
- **多设备/多人共用一台需要升级** Cloudflare Workers Paid（$5/月），否则 KV 写额度不够。

---

## 📄 许可

见 [LICENSE](LICENSE)

---

<p align="center"><em>Rainy Family · 雨晴系列工具</em></p>
