// readBoth 的回归测试。
//
// 存在的唯一理由：P0 曾经是「KV bulk get 返回 Map，代码按普通对象取值」→
// 两个 key 永远读成 null → 网页永远显示「从未收到心跳」，而心跳写入本身是成功的。
// 静态审计抓到了它，但下次不能再靠运气，所以这里用**与 Cloudflare 同构的返回形态**钉住行为。

import { describe, expect, it } from 'vitest';

import { KV_KEY_DEVICE, KV_KEY_MOOD, KV_CACHE_TTL, type DeviceStatus, type CurrentMood } from '../src/types.ts';
import { readBoth } from '../src/lib/kv.ts';

/** 用 Map 模拟官方的 bulk get 返回（Promise<Map<string, string | null>>） */
function kvReturning(pairs: Array<[string, string | null]>): KVNamespace {
  return {
    get: async (keys: unknown, options?: unknown) => {
      void keys;
      void options;
      return new Map(pairs);
    },
  } as unknown as KVNamespace;
}

const device: DeviceStatus = {
  schemaVersion: 1,
  lastSeenAt: 1_759_211_879_500,
  clientTs: 1_759_211_879_000,
  batteryPercent: 87,
  charging: true,
  chargeSource: 'ac',
  temperatureC: 31.5,
  network: 'wifi',
  deviceName: 'My-Phone',
  appVersion: '1.0.0',
  seq: 42,
};

const mood: CurrentMood = {
  schemaVersion: 1,
  text: '困了喵…',
  emoji: '😴',
  updatedAt: 1_759_200_000_000,
};

describe('readBoth', () => {
  it('reads both keys from a Map response (the P0 regression)', async () => {
    const kv = kvReturning([
      [KV_KEY_DEVICE, JSON.stringify(device)],
      [KV_KEY_MOOD, JSON.stringify(mood)],
    ]);

    const result = await readBoth(kv);

    // 这两条断言正是旧实现会失败的断言
    expect(result.device).not.toBeNull();
    expect(result.device?.batteryPercent).toBe(87);
    expect(result.mood?.text).toBe('困了喵…');
  });

  it('passes cacheTtl so reads do not amplify KV quota', async () => {
    let seenTtl: number | undefined;
    const kv = {
      get: async (_keys: unknown, options?: { cacheTtl?: number }) => {
        seenTtl = options?.cacheTtl;
        return new Map<string, string | null>();
      },
    } as unknown as KVNamespace;

    await readBoth(kv);
    expect(seenTtl).toBe(KV_CACHE_TTL);
  });

  it('treats a missing key as null without throwing', async () => {
    const kv = kvReturning([[KV_KEY_DEVICE, JSON.stringify(device)]]);
    const result = await readBoth(kv);
    expect(result.device?.batteryPercent).toBe(87);
    expect(result.mood).toBeNull();
  });

  it('returns nulls when a key holds null', async () => {
    const kv = kvReturning([
      [KV_KEY_DEVICE, null],
      [KV_KEY_MOOD, null],
    ]);
    const result = await readBoth(kv);
    expect(result.device).toBeNull();
    expect(result.mood).toBeNull();
  });

  it('survives corrupt JSON instead of failing the whole endpoint', async () => {
    const kv = kvReturning([
      [KV_KEY_DEVICE, '{not json'],
      [KV_KEY_MOOD, JSON.stringify(mood)],
    ]);
    const result = await readBoth(kv);
    expect(result.device).toBeNull();
    expect(result.mood?.text).toBe('困了喵…');
  });
});
