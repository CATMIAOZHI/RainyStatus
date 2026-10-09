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
// 5. **KV 异常返回 503，不再伪装成「没有数据」**：以前返回 200 + 空数据会让网页
//    显示「从未上报」，还会把本地缓存里的旧数据清掉——把「服务故障」说成「设备没上报」，
//    这是最不该有的误导。

import { offlineThresholdMs, siteConfig } from '../config';
import { readBoth } from '../lib/kv';
import { jsonError, jsonOk, methodNotAllowed } from '../lib/response';
import { SCHEMA_VERSION, type DeviceStatus, type Env, type SiteConfig } from '../types';

/** 机房内缓存时长（秒）。KV 自身的 cacheTtl 是 30 秒，两者对齐即可 */
const CACHE_TTL_SECONDS = 30;
/**
 * 缓存键用固定的内部地址，而不是原始 URL。
 * 用 `request.url` 当键会被 `?lang=`、随机参数、不同 Host 拆成一堆冷缓存，
 * 缓存就等于没有——这也是「参数能控制的东西不能当缓存键」的一般规则。
 */
const CACHE_KEY = 'https://rainystatus.invalid/api/status';

/**
 * 只暴露允许公开的字段。
 *
 * `SHOW_TEMPERATURE` / `SHOW_NETWORK` 必须是**服务端**的过滤：只在前端隐藏元素，
 * 任何人直接 curl 这个接口还是能拿到温度与网络 —— 那是隐私开关的假象。
 */
function publicDevice(device: DeviceStatus, site: SiteConfig) {
  return {
    batteryPercent: device.batteryPercent ?? null,
    charging: device.charging ?? null,
    chargeSource: device.chargeSource ?? null,
    temperatureC: site.showTemperature ? device.temperatureC ?? null : null,
    network: site.showNetwork ? device.network ?? null : null,
    deviceName: device.deviceName ?? null,
    appVersion: device.appVersion ?? null,
    clientTs: device.clientTs ?? null,
  };
}

function isHead(request: Request): boolean {
  return request.method === 'HEAD';
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
      // HEAD 复用 GET 的缓存，但不回传 body
      return respond(hit);
    }
  }

  const now = Date.now();
  const site = siteConfig(env);
  const threshold = offlineThresholdMs(env);

  let device: DeviceStatus | null;
  let mood: Awaited<ReturnType<typeof readBoth>>['mood'];
  try {
    const both = await readBoth(env.STATUS_KV);
    device = both.device;
    mood = both.mood;
  } catch {
    // 读不到就是读不到：明确告诉前端「服务暂时不可用」，让它保留上一次的数据。
    // 503 不写缓存，避免把故障状态在机房内粘住 30 秒。
    return respond(jsonError(503, 'upstream_unavailable', 'Status storage is temporarily unavailable'));
  }

  const lastSeenAt = device?.lastSeenAt ?? null;
  const offlineForMs = lastSeenAt === null ? null : Math.max(0, now - lastSeenAt);
  const online = offlineForMs !== null && offlineForMs <= threshold;

  const response = jsonOk({
    schemaVersion: SCHEMA_VERSION,
    generatedAt: now,
    online,
    offlineThresholdMs: threshold,
    lastSeenAt,
    offlineForMs,
    device: device === null ? null : publicDevice(device, site),
    // SHOW_MOOD 关闭时连心情本身都不下发，而不是让前端藏起来
    mood:
      mood === null || !site.showMood
        ? null
        : { text: mood.text, emoji: mood.emoji ?? null, updatedAt: mood.updatedAt },
    // 站点配置（标题/主人名/头像/展示开关/历史能力）由服务端下发
    site,
  });

  // 只缓存成功响应：503/5xx 一律不缓存（上面已经提前返回）
  response.headers.set('cache-control', `public, max-age=${CACHE_TTL_SECONDS}`);
  if (cache) {
    // 用 waitUntil：缓存写入失败不影响这次响应
    ctx.waitUntil(cache.put(CACHE_KEY, response.clone()).catch(() => {}));
  }

  return respond(response);
}