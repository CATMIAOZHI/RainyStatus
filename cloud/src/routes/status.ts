// GET /api/status —— 公开只读
//
// 关键设计：
// 1. 在线/掉线在【读取时】计算，不在写入时计算 —— 掉线时没有任何写入发生，
//    只能靠读取时刻的墙钟推断
// 2. lastSeenAt 是服务端写入时刻，客户端时钟不准也不会影响判定
// 3. 同时下发 site 配置，网页无需硬编码任何个人信息
// 4. **读缓存 30 秒（机房内）**：这个接口每次请求要读 2 个 KV key，
//    而 KV 免费额度只有 10 万读/天 —— 不缓存的话约 5 万次请求就能把它打光。
//    缓存命中时既不打 KV，也不重复序列化。
//    ⚠️ 这层缓存**只服务 KV 额度**，不能顺带让浏览器也缓存：Cloudflare 的站点级
//    Browser Cache TTL（默认 4 小时）会在命中缓存时改写 `Cache-Control` —— 曾经
//    这里的 `max-age=30` 就这么被放大成 `max-age=14400`，有的浏览器于是敢把
//    状态存 4 小时，「刷新网页也不更新」。所以：**缓存副本**带 `max-age=30`，
//    回给浏览器的响应一律 `no-store`（见下面的 withoutBrowserCache）。
// 5. **KV 异常返回 503，不再伪装成「没有数据」**：以前返回 200 + 空数据会让网页
//    显示「从未上报」，还会把本地缓存里的旧数据清掉——把「服务故障」说成「设备没上报」，
//    这是最不该有的误导。
// 6. 载荷由 `lib/status-payload.ts` 统一组装：静态数据域 `status.json` 用的是
//    **同一个函数**，否则字段与过滤口径迟早分叉（详见那里的注释）。

import { buildStatusPayload } from '../lib/status-payload';
import { jsonError, jsonOk, methodNotAllowed } from '../lib/response';
import type { Env } from '../types';

/** 机房内缓存时长（秒）。KV 自身的 cacheTtl 是 30 秒，两者对齐即可。
 *  **只写在缓存副本上**：回给浏览器的响应永远是 no-store（见文件头第 4 条）。 */
const CACHE_TTL_SECONDS = 30;
/**
 * 缓存键用固定的内部地址，而不是原始 URL。
 * 用 `request.url` 当键会被 `?lang=`、随机参数、不同 Host 拆成一堆冷缓存，
 * 缓存就等于没有——这也是「参数能控制的东西不能当缓存键」的一般规则。
 */
const CACHE_KEY = 'https://rainystatus.invalid/api/status';

function isHead(request: Request): boolean {
  return request.method === 'HEAD';
}

/**
 * 回给浏览器的响应：把 `Cache-Control` 压成 `no-store`。
 *
 * 命中机房缓存时拿到的副本带的是 `max-age=30`，**不能原样回给浏览器**：
 * Cloudflare 的站点级 Browser Cache TTL 会在命中缓存时改写它（默认 4 小时），
 * 浏览器一旦当真，「刷新页面」就只是重新跑了一遍 JS、数据还是旧的。
 * 机房缓存的职责是省 KV 读，不是替浏览器决定缓存多久。
 */
function withoutBrowserCache(response: Response): Response {
  const copy = new Response(response.body, { status: response.status, headers: response.headers });
  copy.headers.set('cache-control', 'no-store');
  return copy;
}

export async function handleStatus(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  // HEAD 一律不带 body（协议要求）；缓存命中也走这里，避免把 GET 的 body 回给 HEAD
  const respond = (response: Response): Response =>
    isHead(request) ? new Response(null, { status: response.status, headers: response.headers }) : response;

  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return respond(methodNotAllowed('GET, HEAD'));
  }

  const cache = typeof caches !== 'undefined' && caches.default !== undefined ? caches.default : null;

  if (cache) {
    // 缓存读取失败**不能**让整个接口 500：它只是加速手段，
    // 读不到就当没命中，继续走 KV（KV 失败才是真的 503）。
    const hit = await cache.match(CACHE_KEY).catch(() => null);
    if (hit) {
      // 副本头上带的是机房用的 max-age：原样回给浏览器会被站点级
      // Browser Cache TTL 放大成几小时，所以这里统一压回 no-store。
      // HEAD 复用 GET 的缓存，但不回传 body（由 respond 收口）
      return respond(withoutBrowserCache(hit));
    }
  }

  // 载荷与静态数据域共用同一份实现（lib/status-payload.ts）
  const payload = await buildStatusPayload(env, Date.now());
  if (payload === null) {
    // 读不到就是读不到：明确告诉前端「服务暂时不可用」，让它保留上一次的数据。
    // 503 不写缓存，避免把故障状态在机房内粘住 30 秒。
    return respond(jsonError(503, 'upstream_unavailable', 'Status storage is temporarily unavailable'));
  }

  const response = jsonOk(payload);

  // 只缓存成功响应：503/5xx 一律不缓存（上面已经提前返回）。
  // 存进机房缓存的是一份**副本**，只有它带 max-age；回给浏览器的响应保持
  // jsonOk 默认的 no-store —— 别把「省 KV 读」写成「让浏览器也缓存 30 秒」。
  if (cache) {
    const cached = response.clone();
    cached.headers.set('cache-control', `public, max-age=${CACHE_TTL_SECONDS}`);
    // 用 waitUntil：缓存写入失败不影响这次响应
    ctx.waitUntil(cache.put(CACHE_KEY, cached).catch(() => {}));
  }

  return respond(response);
}