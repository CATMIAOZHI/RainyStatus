// `/api/status` 的路由级回归测试。
//
// 为什么值得单独写：这个接口有**两层缓存**，混在一起出过一次真实事故——
// 机房缓存（Cache API）的 30 秒是给 KV 额度挡量的，而**回给浏览器**的响应必须是
// no-store：Cloudflare 站点级的 Browser Cache TTL（默认 4 小时）会在命中缓存时
// 改写 `Cache-Control`，那个 `max-age=30` 曾被放大成 `max-age=14400`，
// 有的浏览器于是把状态存 4 小时，「刷新页面也不更新」。
// 这里把「谁拿到什么头」写死，防止下次再退化。

import { afterEach, describe, expect, it, vi } from 'vitest';

import { handleStatus } from '../src/routes/status.ts';
import type { Env } from '../src/types.ts';

const DEVICE = {
  schemaVersion: 1,
  batteryPercent: 87,
  charging: true,
  chargeSource: 'ac',
  temperatureC: 31.5,
  network: 'wifi',
  deviceName: 'My-Phone',
  appVersion: '1.0.0',
  clientTs: 1_759_211_879_500,
  seq: 42,
  lastSeenAt: 1_759_211_880_000,
};

/** 只实现 readBoth 用到的那条链：`kv.get([...], { cacheTtl })` → Map */
function fakeKv(raw: string | null) {
  const calls = { get: 0 };
  const kv = {
    get: async () => {
      calls.get += 1;
      return new Map<string, string | null>([
        ['device_status', raw],
        ['current_mood', null],
      ]);
    },
  } as unknown as KVNamespace;
  return { kv, calls };
}

function envOf(raw: string | null = JSON.stringify(DEVICE)) {
  const { kv, calls } = fakeKv(raw);
  return { env: { STATUS_KV: kv } as unknown as Env, calls };
}

/** 用假 caches.default 记录「写进机房缓存的那份」长什么样 */
function stubCaches(hit: Response | null) {
  const puts: Response[] = [];
  vi.stubGlobal('caches', {
    default: {
      match: async () => hit,
      put: async (_key: string, response: Response) => {
        puts.push(response);
      },
    },
  });
  return puts;
}

/** waitUntil 在这里只是普通 Promise：收起来，测试里显式等它 */
function makeCtx() {
  const pending: Promise<unknown>[] = [];
  const ctx = {
    waitUntil: (promise: Promise<unknown>) => {
      pending.push(promise);
    },
    passThroughOnException: () => {},
  } as unknown as ExecutionContext;
  return { ctx, pending };
}

function request(method = 'GET') {
  return new Request('https://example.com/api/status', { method });
}

/** 模拟机房缓存里那份副本（带 max-age，是我们自己写的） */
function cachedCopy(): Response {
  return new Response(JSON.stringify({ ok: true, online: true }), {
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=30' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GET /api/status 的缓存边界', () => {
  it('命中机房缓存：浏览器拿到的必须是 no-store（否则被 Browser Cache TTL 放大成几小时），且不再打 KV', async () => {
    const puts = stubCaches(cachedCopy());
    const { env, calls } = envOf();
    const { ctx } = makeCtx();

    const res = await handleStatus(request(), env, ctx);

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    // 重建响应时不能把 content-type 也弄丢（丢了前端 res.json() 直接炸）
    expect(res.headers.get('content-type')).toContain('application/json');
    expect((await res.json()).online).toBe(true);
    expect(calls.get).toBe(0);
    expect(puts.length).toBe(0);
  });

  it('未命中：写进机房缓存的副本带 max-age=30，而回给浏览器的仍是 no-store', async () => {
    const puts = stubCaches(null);
    const { env, calls } = envOf();
    const { ctx, pending } = makeCtx();

    const res = await handleStatus(request(), env, ctx);
    await Promise.all(pending);

    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(calls.get).toBe(1);
    expect(puts.map((r) => r.headers.get('cache-control'))).toEqual(['public, max-age=30']);
  });

  it('HEAD 命中缓存：复用缓存但不回 body，头同样是 no-store', async () => {
    stubCaches(cachedCopy());
    const { env } = envOf();
    const { ctx } = makeCtx();

    const res = await handleStatus(request('HEAD'), env, ctx);

    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.text()).toBe('');
  });

  it('KV 读失败：503 且什么都没写进缓存（故障不许被粘住 30 秒）', async () => {
    const puts = stubCaches(null);
    const kv = {
      get: async () => {
        throw new Error('kv down');
      },
    } as unknown as KVNamespace;
    const { ctx } = makeCtx();

    const res = await handleStatus(request(), { STATUS_KV: kv } as unknown as Env, ctx);

    expect(res.status).toBe(503);
    // 故障响应更不能被任何一层缓存粘住
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(puts.length).toBe(0);
  });

  it('只接受 GET / HEAD', async () => {
    stubCaches(null);
    const { env } = envOf();
    const { ctx } = makeCtx();
    const res = await handleStatus(new Request('https://example.com/api/status', { method: 'POST' }), env, ctx);
    expect(res.status).toBe(405);
    expect(res.headers.get('allow')).toBe('GET, HEAD');
    expect(res.headers.get('cache-control')).toBe('no-store');
  });
});