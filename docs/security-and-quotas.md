# 安全与额度边界（R2 静态数据域）

> 给以后动 R2 / 缓存规则 / 熔断的人。接口契约见 `docs/api.md` 的「静态数据域」，规划背景见 `docs/roadmap.md` 的 T1。
> 记录日期：2026-10-10（红队评审后的修订版；数字用的是当时的官方口径，用前复核）。

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
| `r2.dev` 开发域直连 | **关闭**；熔断时也把它一起关（它不走我们的缓存规则与 WAF） |
| 非 GET/HEAD 请求 | 不缓存；WAF 一并拦 |
| 各边缘机房各自回源 | 开 **Smart Tiered Cache**；不开时最坏 ≈ $19.7/月 |
| 多域名 | 桶只保留**一个**自定义域；新增域要同步进熔断清单 |

（Range 请求、对象头冲突等其余清单项见评审记录；核心原则：**别把桶当通用文件服务器用**。）

## 4. 熔断设计（Worker 内 `GUARD_*`）

- **巡检**：cron 每 15 分钟 → GraphQL `r2OperationsAdaptiveGroups`（按账号 + bucket 过滤）→ **月累计 / 滚动 1 小时双阈值**（默认 600 万 / 200 万）。
- **动作**：停用自定义域（`PUT /accounts/{id}/r2/buckets/{bucket}/domains/custom/{host}` body `{"enabled":false}`）+ 关闭 r2.dev（`.../domains/managed`）；两者结果都写进 KV `guard_state` 供事后核对。
- **复位**：只允许人工——不会自动重开（自动恢复＝攻击一停就恢复计费，等于没熔断）。熔断期间页面会先试静态域、失败再回退 `/api/status`：多一次失败请求，但不消耗 Worker 额度，属预期行为。
- **失败策略**：查不到用量 → 不动手（宁可漏报也不误伤），只留 warning；拉闸动作失败 → 记 `failed`，下轮巡检（仍在超限窗口内）自动重试（幂等）。
- **Token 最小权限**：`Account Analytics:Read` + `Workers R2 Storage:Write`，独立 token，别复用部署 token。
- **已知限制**：Analytics 用量数据的新鲜度官方**没有 SLA**（文档自述可能不即时）——**熔断不是实时刹车，是按小时兜底的止损**。开启前先实测滞后。

最坏账单上界（红队估算）：滞后 1 小时 ≈ $4–6.5；6 小时 ≈ $22；12 小时才发现 ≈ $42；**完全没熔断、被刷整月 ≈ $2588**。

## 5. 开启 / 关闭操作清单（顺序不能反）

开启：

1. 控制台开通 R2（可能需绑定支付方式）；顺手配一个**预算提醒邮件**（只是提醒，不是保险）。
2. 建桶（如 `rainystatus-data`）→ 接自定义域（如 `data.example.com`）→ **关闭 r2.dev** → 给桶配 CORS（允许网页域的 GET）。
3. Cache Rule：整域（`http.host eq "data.example.com"`）→ Eligible for cache + Edge TTL「忽略源站、60s」+ 浏览器 TTL override 60s。
4. WAF 规则：只放行 `/status.json` 路径；拦非 GET/HEAD、拦带查询串。
5. 开 Smart Tiered Cache。
6. Worker：`r2_buckets` 绑定 `DATA_BUCKET` + `DATA_*` / `GUARD_*` vars + `npx wrangler secret put CF_GUARD_TOKEN`。页面可加 `<meta name="rainystatus-data-base" content="https://data.example.com">`（不加也行：接口会下发地址，只是每浏览器第一次仍打一次 Worker）。
7. 部署 → 实测：连续请求第 2 次起 `cf-cache-status: HIT`、带 `?rand=` 被边缘挡、HEAD 走缓存、CORS 头正确、`guard_state` 可读。
8. 观察 1–2 天 Analytics 数据滞后，再确认「小时阈值」是否有效。

关闭（任何时候）：

- 先 `DATA_ENABLED=false` 重新部署（页面即时回退 `/api/status`）；或
- 直接控制台停域；想彻底断计费：停自定义域 + 关 r2.dev（+ 确认无其它域）。

## 6. 红线

- **别用 zone 级「大杀器」**（整域 WAF block / 整域停缓存）：会把 status Worker 一起打死，部署流水线的 `/api/health` 探测变红。
- 熔断规则必须**精确限定数据主机名**。
- 别给熔断加「自动恢复」。
