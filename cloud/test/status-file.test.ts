// 静态数据文件发布（`status.json`）的回归测试。
//
// 为什么值得单独写：这个功能把「公开数据」写进了对象存储，一旦出问题就不是
// 报错而是**静默地公开了不该公开的东西**（比如 SHOW_MOOD 关掉后文件里还带着心情），
// 或者在 KV 故障时用空数据把上一版好数据覆盖掉。这里把两条都钉死。

import { afterEach, describe, expect, it, vi } from 'vitest';

import { dataConfig } from '../src/config.ts';
import { publishStatusFile, STATUS_FILE_KEY, STATUS_FILE_MAX_AGE_SECONDS } from '../src/jobs/status-file.ts';
import type { Env } from '../src/types.ts';

const DEVICE = {
  schemaVersion: 1,
  lastSeenAt: 1_759_211_880_000,
  clientTs: 1_759_211_879_500,
  batteryPercent: 87,
  charging: true,
  chargeSource: 'ac',
  temperatureC: 31.5,
  network: 'wifi',
  deviceName: 'My-Phone',
  appVersion: '1.0.0',
  seq: 42,
};

const MOOD = { schemaVersion: 1, text: '困了喵…', emoji: '😴', updatedAt: 1_759_200_000_000 };

/** 只实现发布器用到的 `kv.get([...], { cacheTtl })` → Map 这条链 */
function fakeKv(device: string | null = JSON.stringify(DEVICE), mood: string | null = JSON.stringify(MOOD)) {
  const kv = {
    get: async () =>
      new Map<string, string | null>([
        ['device_status', device],
        ['current_mood', mood],
      ]),
    put: async () => undefined,
  } as unknown as KVNamespace;
  return kv;
}

/** 记录 put 了什么：键、正文、以及对象头上的缓存指令 */
function fakeBucket() {
  const puts: Array<{ key: string; body: string; cacheControl?: string; contentType?: string }> = [];
  const bucket = {
    put: async (key: string, body: string, options?: { httpMetadata?: { cacheControl?: string; contentType?: string } }) => {
      puts.push({
        key,
        body,
        cacheControl: options?.httpMetadata?.cacheControl,
        contentType: options?.httpMetadata?.contentType,
      });
    },
  } as unknown as R2Bucket;
  return { bucket, puts };
}

function envOf(options: { enabled?: boolean; kv?: KVNamespace; bucket?: R2Bucket } = {}): Env {
  return {
    STATUS_KV: options.kv ?? fakeKv(),
    DATA_ENABLED: options.enabled === false ? 'false' : options.enabled === true ? 'true' : undefined,
    DATA_BASE_URL: 'https://data.example.com',
    DATA_BUCKET: options.bucket ?? fakeBucket().bucket,
  } as unknown as Env;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('dataConfig 的启用条件', () => {
  it('默认关闭；DATA_ENABLED / 绑定 / 合法地址三者缺一都不算启用', () => {
    expect(dataConfig({} as Env).enabled).toBe(false);
    const bucket = fakeBucket().bucket;
    expect(dataConfig({ DATA_ENABLED: 'true', DATA_BASE_URL: 'https://data.example.com' } as unknown as Env).enabled).toBe(false);
    expect(dataConfig({ DATA_ENABLED: 'true', DATA_BUCKET: bucket, DATA_BASE_URL: 'http://data.example.com' } as unknown as Env).enabled).toBe(false);
    expect(dataConfig({ DATA_ENABLED: 'true', DATA_BUCKET: bucket, DATA_BASE_URL: 'https://data.example.com/' } as unknown as Env).enabled).toBe(true);
  });

  it('只收 https、去尾斜杠、拒带凭据地址（http 会被浏览器当混合内容拦掉）', () => {
    expect(dataConfig({ DATA_BASE_URL: 'https://data.example.com' } as unknown as Env).baseUrl).toBe('https://data.example.com');
    expect(dataConfig({ DATA_BASE_URL: 'https://data.example.com//' } as unknown as Env).baseUrl).toBe('https://data.example.com');
    expect(dataConfig({ DATA_BASE_URL: 'http://data.example.com' } as unknown as Env).baseUrl).toBe(null);
    expect(dataConfig({ DATA_BASE_URL: 'https://user:pass@data.example.com' } as unknown as Env).baseUrl).toBe(null);
  });
});

describe('publishStatusFile', () => {
  it('未启用时一个字节都不写', async () => {
    const { bucket, puts } = fakeBucket();
    const wrote = await publishStatusFile(envOf({ enabled: false, bucket }), 1_759_211_900_000);
    expect(wrote).toBe(false);
    expect(puts.length).toBe(0);
  });

  it('启用后写入 status.json：带 60 秒缓存头，正文与 /api/status 同款载荷', async () => {
    const { bucket, puts } = fakeBucket();
    const wrote = await publishStatusFile(envOf({ enabled: true, bucket }), 1_759_211_900_000);

    expect(wrote).toBe(true);
    expect(puts.length).toBe(1);
    expect(puts.map((p) => p.key)).toEqual([STATUS_FILE_KEY]);
    expect(puts.map((p) => p.cacheControl)).toEqual([`public, max-age=${STATUS_FILE_MAX_AGE_SECONDS}`]);
    expect(puts.map((p) => p.contentType)).toEqual(['application/json; charset=utf-8']);

    const body = JSON.parse(puts.map((p) => p.body)[0] ?? '{}') as {
      ok?: boolean;
      online?: boolean;
      generatedAt?: number;
      mood?: { text?: string } | null;
      site?: { title?: string; dataBaseUrl?: string | null };
    };
    expect(body.ok).toBe(true);
    expect(body.generatedAt).toBe(1_759_211_900_000);
    // lastSeenAt = 1_759_211_880_000，阈值默认 30 分钟 → 仍是「在线」
    expect(body.online).toBe(true);
    expect(body.mood?.text).toBe('困了喵…');
    expect(body.site?.dataBaseUrl).toBe('https://data.example.com');
  });

  it('SHOW_MOOD=false 时文件里连心情都不能有（隐私开关必须在这一层生效）', async () => {
    const { bucket, puts } = fakeBucket();
    const env = { ...envOf({ enabled: true, bucket }), SHOW_MOOD: 'false' } as unknown as Env;

    await publishStatusFile(env, 1_759_211_900_000);

    const body = JSON.parse(puts.map((p) => p.body)[0] ?? '{}') as { mood?: unknown };
    expect(body.mood).toBe(null);
  });

  it('KV 读失败时跳过：宁可数据旧，也不能用空数据覆盖上一版', async () => {
    const { bucket, puts } = fakeBucket();
    const brokenKv = {
      get: async () => {
        throw new Error('kv down');
      },
      put: async () => undefined,
    } as unknown as KVNamespace;

    const wrote = await publishStatusFile(envOf({ enabled: true, bucket, kv: brokenKv }), 1_759_211_900_000);

    expect(wrote).toBe(false);
    expect(puts.length).toBe(0);
  });
});
