// `/api/history` 的路由级回归测试。
//
// 为什么值得单独写：这个接口有三道门（档位白名单 → 人机验证 → 读 1 行导出），
// 每一道漏掉都不是「报错」，而是**静默地放行了不该放行的请求**——
// 例如忘了在服务端过滤心情、或者没拦住未公开的档位。这类问题只有把
// 「门在哪、拒绝长什么样」写死才防得住。
//
// D1 与 Turnstile 都在这里用最小的假对象替身：本测试只关心路由的判定顺序，
// 不关心 SQL（SQL 由 `cloud/scripts/d1check.py` 按原文跑真 SQL 验证）。

import { afterEach, describe, expect, it, vi } from 'vitest';

import { handleHistory } from '../src/routes/history.ts';
import type { Env } from '../src/types.ts';

const TURNSTILE = { TURNSTILE_SECRET: 's3cret', TURNSTILE_SITE_KEY: '0xKEY' };
const ENABLED = { HISTORY_ENABLED: 'true', HISTORY_RANGES: '24h', ...TURNSTILE };

const EXPORT_ROW = {
  generated_at: 1_759_212_000_000,
  payload: JSON.stringify({
    schemaVersion: 1,
    mode: 'samples',
    range: '24h',
    points: [{ t: 1, p: 50, c: null }],
    mood: [{ at: 1, text: '困了喵…', emoji: '😴' }],
  }),
};

/** 只实现 readExport 用到的那条链：prepare().bind().first() */
function fakeDb(row: { generated_at: number; payload: string } | null): D1Database {
  return {
    prepare: () => ({ bind: () => ({ first: async () => row }) }),
  } as unknown as D1Database;
}

function envOf(vars: Record<string, string>, row: { generated_at: number; payload: string } | null = EXPORT_ROW): Env {
  return { ...vars, HISTORY_DB: fakeDb(row) } as unknown as Env;
}

function request(query = '?range=24h', headers: Record<string, string> = {}): Request {
  return new Request(`https://example.com/api/history${query}`, { headers });
}

async function body(res: Response) {
  return (await res.json()) as Record<string, unknown> & { error?: { code?: string }; data?: Record<string, unknown> };
}

function stubTurnstile(success: boolean) {
  vi.stubGlobal('fetch', async () => ({ ok: true, json: async () => ({ success }) }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('GET /api/history', () => {
  it('没开启时只回 enabled:false，不泄露档位', async () => {
    const res = await handleHistory(request(), envOf({}));
    expect(res.status).toBe(200);
    const payload = await body(res);
    expect(payload.enabled).toBe(false);
    expect(payload.ranges).toEqual([]);
  });

  it('档位不在实现的 RANGE_SPECS 里时，参数在碰数据库之前就被拒（400）', async () => {
    const res = await handleHistory(request('?range=1y'), envOf(ENABLED));
    expect(res.status).toBe(400);
    expect((await body(res)).error?.code).toBe('invalid_payload');
  });

  it('档位存在但没在 HISTORY_RANGES 公开时 404：不能靠前端不显示来当开关', async () => {
    const res = await handleHistory(request('?range=24h'), envOf({ ...TURNSTILE, HISTORY_ENABLED: 'true' }));
    expect(res.status).toBe(404);
    expect((await body(res)).error?.code).toBe('not_found');
  });

  it('人机验证没过（含验证服务不可达）一律 403，fail-closed', async () => {
    stubTurnstile(false);
    const denied = await handleHistory(request('?range=24h', { 'cf-turnstile-response': 'bad' }), envOf(ENABLED));
    expect(denied.status).toBe(403);
    expect((await body(denied)).error?.code).toBe('challenge_failed');

    vi.stubGlobal('fetch', async () => {
      throw new Error('network down');
    });
    const unreachable = await handleHistory(request('?range=24h', { 'cf-turnstile-response': 'x' }), envOf(ENABLED));
    expect(unreachable.status).toBe(403);

    // 连令牌都没带
    stubTurnstile(true);
    const missing = await handleHistory(request('?range=24h'), envOf(ENABLED));
    expect(missing.status).toBe(403);
  });

  it('验证通过后返回预生成的导出', async () => {
    stubTurnstile(true);
    const res = await handleHistory(request('?range=24h', { 'cf-turnstile-response': 'good' }), envOf(ENABLED));
    expect(res.status).toBe(200);
    const payload = await body(res);
    expect(payload.enabled).toBe(true);
    expect(payload.challenge).toBe('turnstile');
    expect((payload.data?.points as unknown[]).length).toBe(1);
  });

  it('SHOW_MOOD 关掉后，即使旧导出里还带着心情，响应里也必须为空', async () => {
    stubTurnstile(true);
    const res = await handleHistory(
      request('?range=24h', { 'cf-turnstile-response': 'good' }),
      envOf({ ...ENABLED, SHOW_MOOD: 'false' }),
    );
    const payload = await body(res);
    expect(payload.data?.mood).toEqual([]);
  });

  it('导出还没生成 / 形状坏掉都算「暂时不可用」（503），而不是 500 或空数据', async () => {
    stubTurnstile(true);
    const notReady = await handleHistory(request('?range=24h', { 'cf-turnstile-response': 'good' }), envOf(ENABLED, null));
    expect(notReady.status).toBe(503);
    expect((await body(notReady)).error?.code).toBe('history_unavailable');

    // '[]' 是合法 JSON，但不是导出对象的形状
    const broken = await handleHistory(
      request('?range=24h', { 'cf-turnstile-response': 'good' }),
      envOf(ENABLED, { generated_at: 1, payload: '[]' }),
    );
    expect(broken.status).toBe(503);
  });

  it('只接受 GET / HEAD', async () => {
    const res = await handleHistory(new Request('https://example.com/api/history', { method: 'POST' }), envOf(ENABLED));
    expect(res.status).toBe(405);
  });
});
