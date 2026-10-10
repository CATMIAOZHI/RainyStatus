# 安全与额度边界（R2 静态数据域）

> 给以后动 R2 / 缓存规则 / 熔断的人。接口契约见 `docs/api.md` 的「静态数据域」，规划背景见 `docs/roadmap.md` 的 T1。
> 记录日期：2026-10-10（红队评审 + 上线实测 + 独立安全审计后的修订版；数字用的是当时的官方口径，用前复核）。

## 1. 为什么需要它

`/api/status` 每个访客都要打一次 Worker。Workers 免费额度是 **10 万请求/天（账号级共享）** ≈ 1.16 次/秒持续跑一天；而**机房缓存（`caches.default`）命中照样算 1 次请求**，只省 KV 读。

「人多 / 被打」时唯一的免费出路是把**读**搬出 Worker：静态资源请求免费且不限量——网页本身已经这么做了（assets），`status.json` 上 R2 + CDN 是同一思路的延伸。

## 2. 数字（官方口径）

| 项 | 免费额度/月 | 超出 | 备注 |
|---|---|---|---|
| R2 存储 | 10 GB | $0.015/GB | 我们只有一份几 KB 的 JSON |
| R2 Class A（写） | 100 万次 | $4.50/百万 | 心跳 + cron 各写一次 |
| R2 Class B（读） | 1000 万次 | $0.36/百万 | **CDN 缓存命中不落桶、不计** |
| R2 出网流量 | **不限量** | 免费 | 「R2 免费不限量」说的是这一条 |
| Workers 请求 | 10 万次/**天** | — | 账号级；静态资源请求不计 |
| KV 读 | 10 万次/天 | — | `/api/status` 每请求读 2 个 key |

攻击数学：把 R2 免费额度打穿只需 ≈ **3.9 次/秒跑一个月**（1000 万 ÷ 30 天 ≈ 每秒 3.9 次）。到 1k rps ≈ $930/月、10k rps ≈ $9.3k/月。

**官方没有「免费用完自动停」**：只有预算提醒（budget alerts，官方原文 *does not pause or cap usage*）与用量通知。拉闸必须自建（本仓库 `GUARD_*`）。

## 3. 攻击面与防线（红队结论）

缓存规则是第一道防线，WAF 是第二道，熔断是兜底：

| 攻击面 | 反制 |
|---|---|
| 随机查询串（`?x=`）绕过缓存 | 免费版 Cache Key **不支持忽略查询串** → 用 WAF 拦掉带 `?` 的请求 |
| 随机路径泛洪 | WAF 白名单只放行 `/status.json` |
| 归一化路径变体（`//x`、`%2E`、`/x/` 等）绕过路径检查 | 路径要用 **`raw.http.request.uri.path` 原样精确匹配**：只查归一化后的 `http.request.uri.path` 会被绕过——WAF 看的是归一化路径，缓存键/回源未必，每个新变体都会各自回源（读数放大器）。复测补充：`raw` 保编码差异（`%2E`、`%20`、大小写、`;`、尾点都被拦）；**点段（`/./x`、`/a/../x`）在 raw 层也被归一化**，会放行，但与正常路径**同缓存键**（命中同一条目，非放大） |
| 默认缓存键请求头增殖（`Origin` + 九头：`x-http-method-override`、`x-http-method`、`x-method-override`、`x-forwarded-host`、`x-host`、`x-forwarded-scheme`、`x-original-url`、`x-rewrite-url`、`forwarded`） | 官方 cache-keys 明列这十类头进 **CF 默认缓存键**（不只是 `Vary: Origin`）：每个新值各占一条缓存条目、缺失时回源（读数放大器）。WAF⑤ = `Origin` 白名单 + 九头**存在即拒**（`any([*] ne "") or any([*] eq "")`，空值/重复/多值一并拦）；实测换值即 MISS、修复后九头全 403 |
| host 写法变体（大小写 / 端口 / 尾点） | 大小写、`:443`、`Host:` 变体实测**同缓存键**（CF 归一化，无放大）；尾点 `host.`：非法形态被 WAF 403，合法 `/status.json` 形态 401 + 不缓存（未读出对象，R2 host 校验拒绝） |
| `r2.dev` 开发域直连 | **关闭**；熔断时也把它一起关（它不走我们的缓存规则与 WAF） |
| 非 GET/HEAD 请求 | 不缓存；WAF 一并拦 |
| 各边缘机房各自回源 | 开 **Smart Tiered Cache**；不开时最坏 ≈ $19.7/月 |
| 多域名 | 桶只保留**一个**自定义域；新增域要同步进熔断清单 |

（Range 请求、对象头冲突等其余清单项见评审记录；核心原则：**别把桶当通用文件服务器用**。）

## 4. 熔断设计（Worker 内 `GUARD_*`）

- **巡检**：cron 每 15 分钟 → GraphQL `r2OperationsAdaptiveGroups`（按账号 + bucket 过滤）→ **月累计 / 滚动 1 小时双阈值**（默认 600 万 / 200 万）。
- **动作**：停用自定义域（`PUT /accounts/{id}/r2/buckets/{bucket}/domains/custom/{host}` body `{"enabled":false}`）+ 关闭 r2.dev（`.../domains/managed`）；两者结果都写进 KV `guard_state` 供事后核对。
- **复位**：只允许人工——不会自动重开（自动恢复＝攻击一停就恢复计费，等于没熔断）。熔断期间页面显示上次数据 + 「降级」标记：**不立刻回退打 Worker**（否则全站会变回 60 秒一次的 Worker 轮询）；仅当完全没有数据、或本地数据超过 30 分钟未更新时才回退一次、且同一标签页 10 分钟内至多一次（长期熔断不会把页面冻结在旧数据）。
- **失败策略**：查不到用量 → 不动手（宁可漏报也不误伤），只留 warning；拉闸动作失败 → 记 `failed`，下轮巡检（仍在超限窗口内）自动重试（幂等）。
- **Token 最小权限**：`Account Analytics:Read` + `Workers R2 Storage:Write`，独立 token，别复用部署 token。
- **已知限制**：Analytics 用量数据的新鲜度官方**没有 SLA**（文档自述可能不即时）——**熔断不是实时刹车，是按小时兜底的止损**。开启前先实测滞后。

费用估算（红队/审计口径，**不是硬上界**）：滞后 1 小时 ≈ $4–6.5；6 小时 ≈ $22；12 小时才发现 ≈ $42；完全没熔断、被刷整月 ≈ $2588。
数字依赖「Analytics 在估算窗口内送达」的假设——官方无新鲜度 SLA，且查询失败时熔断 fail-open（只 warn 不动手）。**防付费的真正保证来自回源面封闭**：能影响缓存键的输入只有 URL（scheme/host/path/query）、`Origin` 与那九个请求头，全部受 WAF + 缓存规则约束；熔断只是兜底止损。

## 5. 开启 / 关闭操作清单（顺序不能反）

开启：

1. 控制台开通 R2（可能需绑定支付方式）；顺手配一个**预算提醒邮件**（只是提醒，不是保险）。
2. 建桶（如 `rainystatus-data`）→ 接自定义域（如 `data.example.com`）→ **关闭 r2.dev** → 给桶配 CORS（允许网页域的 GET）→ 顺手开 zone 级 **Always Use HTTPS**（实测自定义域的 `http://` 也会 301）。
3. Cache Rule：整域（`http.host eq "data.example.com"`）→ Eligible for cache + Edge TTL「忽略源站、60s」+ 浏览器 TTL override 60s。
4. WAF 规则（五条，都限定数据主机名）：拦非 GET/HEAD、拦带查询串、`http.request.uri.path` 恰为 `/status.json`、**`raw.http.request.uri.path` 恰为 `/status.json`**（防编码类归一化变体）、**`Origin` 白名单 + 九个默认缓存键头存在即拒**（`any([*] ne "") or any([*] eq "")`；防按值增殖回源）。
5. 开 Smart Tiered Cache。
6. Worker：`r2_buckets` 绑定 `DATA_BUCKET` + `DATA_*` / `GUARD_*` vars + `npx wrangler secret put CF_GUARD_TOKEN`。页面可加 `<meta name="rainystatus-data-base" content="https://data.example.com">`（不加也行：接口会下发地址，只是每浏览器第一次仍打一次 Worker）。
7. 部署 → 实测：连续请求第 2 次起 `cf-cache-status: HIT`、带 `?rand=` 被边缘挡、HEAD 走缓存、CORS 头正确、`guard_state` 可读；再补测：编码类路径变体（`//x`、`%2E`、`/x/`）被拦（点段 `/./x` 与正常路径同键、放行不算漏）、非网页域 `Origin` 被拦、**九个头各发一次全 403**（含空值头 `-H 'X-Forwarded-Host;'` 与重复头）。
8. 观察 1–2 天 Analytics 数据滞后，再确认「小时阈值」是否有效。

关闭（任何时候）：

- 先 `DATA_ENABLED=false` 重新部署：`/api/status` 不再下发数据域、发布器停止写桶；新访客从此直接走 `/api/status`（若曾用 meta 方式配置，需同时移除该 meta——前端 meta 优先级最高）。
- 再停域收尾：控制台停自定义域 + 关 r2.dev（+ 确认无其它域）——既停掉计费，也让已直读静态的旧页面在数据陈旧后按限频切回 Worker（最长约 30 分钟）。只改 flag 不停域不彻底：域还在时，老访客会继续读那份不再更新的静态文件。

## 6. 红线

- **别用 zone 级「大杀器」**（整域 WAF block / 整域停缓存）：会把 status Worker 一起打死，部署流水线的 `/api/health` 探测变红。
- 熔断规则必须**精确限定数据主机名**。
- 别给熔断加「自动恢复」。
