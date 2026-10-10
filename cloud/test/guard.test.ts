// 配额熔断（`runQuotaGuard`）的回归测试。
//
// 为什么值得单独写：它会在生产里**主动改线上配置**（停自定义域 + 关 r2.dev）。误伤的代价是
// 「页面读不到数据」，漏判的代价是「账单无限涨」——两种都只能靠测试钉住行为：
//   · 查不到用量时**必须什么都不做**（红队要求：宁可漏报，不许误伤）；
//   · 拉闸动作失败要留下痕迹（action='failed'），不能静默失败；
//   · 恢复只能人工，所以这里不该出现任何「自动重新启用」的调用。

import { afterEach, describe, expect, it, vi } from 'vitest';

import { guardConfig } from '../src/config.ts';
import { runQuotaGuard, GUARD_STATE_KEY } from '../src/jobs/guard.ts';
import type { Env } from '../src/types.ts';

const ACCOUNT = 'acct-1234';
const BUCKET = 'rainystatus-data';
const HOST = 'data.example.com';

function envOf(overrides: Record<string, string | undefined> = {}): Env {
  return {
    STATUS_KV: { put: async () => undefined } as unknown as KVNamespace,
    GUARD_ENABLED: 'true',
    CF_GUARD_TOKEN: 'guard-token',
    GUARD_ACCOUNT_ID: ACCOUNT,
    GUARD_BUCKET_NAME: BUCKET,
    GUARD_HOSTNAME: HOST,
    ...overrides,
  } as unknown as Env;
}

/** GraphQL 返回「某窗口的请求数」；403 表示查不到（权限/故障） */
function graphQl(total: number | null) {
  if (total === null) return new Response('{}', { status: 403 });
  return new Response(
    JSON.stringify({ data: { viewer: { accounts: [{ r2OperationsAdaptiveGroups: [{ sum: { requests: total } }] }] } } }),
    { status: 200, headers: { 'content-type': 'application/json' } },
  );
}

/** 假的 fetch：按 URL 分派 GraphQL 与「停域」两种调用，并记录后者。
 *  `disableStatus` 给数字 = 两个域都按它返回；给对象 = 分别控制 custom / managed。 */
function stubFetch(
  monthly: number | null,
  hourly: number | null,
  disableStatus: number | { custom?: number; managed?: number } = 200,
) {
  const disabled: string[] = [];
  vi.stubGlobal('fetch', async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes('/graphql')) {
      // 第一次问「本月」、第二次问「最近一小时」，按调用顺序发牌
      const body = typeof init?.body === 'string' ? (JSON.parse(init.body) as { variables?: { since?: string } }) : {};
      const isMonth = (body.variables?.since ?? '').endsWith('-01T00:00:00.000Z');
      return isMonth ? graphQl(monthly) : graphQl(hourly);
    }
    if (url.includes('/domains/custom/') || url.includes('/domains/managed')) {
      disabled.push(url);
      const status =
        typeof disableStatus === 'number'
          ? disableStatus
          : ((url.includes('/domains/managed') ? disableStatus.managed : disableStatus.custom) ?? 200);
      return new Response('{}', { status });
    }
    throw new Error(`unexpected fetch: ${url}`);
  });
  return { disabled };
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('guardConfig 的启用条件', () => {
  it('默认关闭；token / 账号 / 桶 / 主机名缺一都不启用', () => {
    expect(guardConfig({} as Env).enabled).toBe(false);
    for (const missing of ['CF_GUARD_TOKEN', 'GUARD_ACCOUNT_ID', 'GUARD_BUCKET_NAME', 'GUARD_HOSTNAME']) {
      expect(guardConfig(envOf({ [missing]: '' })).enabled).toBe(false);
    }
    expect(guardConfig(envOf()).enabled).toBe(true);
    // 阈值有默认值：月 600 万（免费额度 60%）、小时 200 万
    expect(guardConfig(envOf()).maxMonthly).toBe(6_000_000);
    expect(guardConfig(envOf()).maxHourly).toBe(2_000_000);
  });
});

describe('runQuotaGuard', () => {
  it('未启用时连一次外部请求都不发', async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal('fetch', fetchSpy);

    const state = await runQuotaGuard(envOf({ GUARD_ENABLED: 'false' }), 1_759_211_900_000);

    expect(state).toBe(null);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('用量正常：不拉闸、不写 KV', async () => {
    const { disabled } = stubFetch(1000, 10);
    const put = vi.fn(async () => undefined);
    const env = { ...envOf(), STATUS_KV: { put } as unknown as KVNamespace } as unknown as Env;

    const state = await runQuotaGuard(env, 1_759_211_900_000);

    expect(state?.tripped).toBe(false);
    expect(state?.action).toBe('none');
    expect(disabled.length).toBe(0);
    expect(put).not.toHaveBeenCalled();
  });

  it('月累计超线：把两个门（自定义域 + r2.dev）都关上并留下 state（恢复只能人工）', async () => {
    const { disabled } = stubFetch(6_000_000, 10);
    const put = vi.fn(async (_key: string, _value?: string) => undefined);
    const env = { ...envOf(), STATUS_KV: { put } as unknown as KVNamespace } as unknown as Env;

    const state = await runQuotaGuard(env, 1_759_211_900_000);

    expect(state?.tripped).toBe(true);
    expect(state?.action).toBe('disabled_domain');
    expect(state?.managedDisabled).toBe(true);
    expect(disabled).toEqual([
      `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/r2/buckets/${BUCKET}/domains/custom/${HOST}`,
      `https://api.cloudflare.com/client/v4/accounts/${ACCOUNT}/r2/buckets/${BUCKET}/domains/managed`,
    ]);
    expect(put).toHaveBeenCalledTimes(1);
    expect(put.mock.calls[0]?.[0]).toBe(GUARD_STATE_KEY);
  });

  it('最近一小时超线也算（突发攻击与慢速刷都要拦）', async () => {
    const { disabled } = stubFetch(1000, 2_000_000);

    const state = await runQuotaGuard(envOf(), 1_759_211_900_000);

    expect(state?.tripped).toBe(true);
    expect(disabled.length).toBe(2);
  });

  it('自定义域关成功、r2.dev 没关成：主路径已切断，但 managedDisabled=false 要记进 state', async () => {
    const { disabled } = stubFetch(6_000_000, 10, { managed: 500 });
    const put = vi.fn(async () => undefined);
    const env = { ...envOf(), STATUS_KV: { put } as unknown as KVNamespace } as unknown as Env;

    const state = await runQuotaGuard(env, 1_759_211_900_000);

    expect(state?.action).toBe('disabled_domain');
    expect(state?.managedDisabled).toBe(false);
    expect(disabled.length).toBe(2);
  });

  it('查不到用量（权限不足 / API 挂）：宁可漏报也不动手，只留一条 warning', async () => {
    const { disabled } = stubFetch(null, null);
    const put = vi.fn(async () => undefined);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const env = { ...envOf(), STATUS_KV: { put } as unknown as KVNamespace } as unknown as Env;

    const state = await runQuotaGuard(env, 1_759_211_900_000);

    expect(state).toBe(null);
    expect(disabled.length).toBe(0);
    expect(put).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalledTimes(1);
  });

  it('拉闸动作失败：记成 failed 而不是假装没事', async () => {
    const { disabled } = stubFetch(9_000_000, 10, 500);
    const put = vi.fn(async () => undefined);
    const env = { ...envOf(), STATUS_KV: { put } as unknown as KVNamespace } as unknown as Env;

    const state = await runQuotaGuard(env, 1_759_211_900_000);

    expect(disabled.length).toBe(2);
    expect(state?.action).toBe('failed');
    expect(state?.managedDisabled).toBe(false);
    expect(put).toHaveBeenCalledTimes(1);
  });
});