// KV 读写封装
//
// 关键约定：
// 1. 两个 key 都不设 TTL —— 一旦过期就丢掉 lastSeenAt，掉线时长无法计算
// 2. lastSeenAt 用服务端 Date.now()，绝不用客户端时间判掉线
// 3. 读缓存 30 秒（官方最小值），提升新鲜度
// 4. 写入是 last-write-wins，不加读守卫（单设备场景下重试覆盖无害）

import {
  KV_CACHE_TTL,
  KV_KEY_DEVICE,
  KV_KEY_MOOD,
  type CurrentMood,
  type DeviceStatus,
} from '../types';

function parseJson<T>(raw: string | null): T | null {
  if (raw === null) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    // 脏数据不让整个接口挂掉，视作无数据
    return null;
  }
}

export async function readDeviceStatus(kv: KVNamespace): Promise<DeviceStatus | null> {
  const raw = await kv.get(KV_KEY_DEVICE, { cacheTtl: KV_CACHE_TTL });
  return parseJson<DeviceStatus>(raw);
}

export async function readCurrentMood(kv: KVNamespace): Promise<CurrentMood | null> {
  const raw = await kv.get(KV_KEY_MOOD, { cacheTtl: KV_CACHE_TTL });
  return parseJson<CurrentMood>(raw);
}

/**
 * 一次 bulk read 取两个 key。
 * 计费仍按 2 个 key 算，但省一次 KV 操作/subrequest。
 *
 * **返回类型是 `Map`，不是普通对象**（官方签名 `get(keys: string[]) =>
 * Promise<Map<string, string | Object | null>>`）。按普通对象下标取值会全得 `undefined`，
 * 症状是「心跳明明写进去了，网页却永远显示从未上报」——静默失效，最坏的那种 bug。
 * 这里对两种形态都兼容，避免依赖运行时细节再次踩坑。
 */
export async function readBoth(
  kv: KVNamespace,
): Promise<{ device: DeviceStatus | null; mood: CurrentMood | null }> {
  const result = await kv.get([KV_KEY_DEVICE, KV_KEY_MOOD], { cacheTtl: KV_CACHE_TTL });

  const lookup = (key: string): string | null => {
    if (result === null || typeof result !== 'object') return null;
    // 鸭子判定而不是 `instanceof Map`：后者在跨 realm（isolate 边界）时会失败，
    // 而兜底分支按属性取值对 Map **同样**返回 undefined —— 两条分支都返回 null，
    // 于是 P0 会原样复发且无声。按「有没有 get 方法」判定与 realm 无关。
    const getter = (result as { get?: (k: string) => unknown }).get;
    const value = typeof getter === 'function'
      ? getter.call(result, key)
      // 先转 unknown 再转 Record：直接断言会被 tsc 拒绝（Map 没有 string 索引签名），
      // 而这条分支正是「result 不是 Map 形态」时的兜底
      : (result as unknown as Record<string, unknown>)[key];
    return typeof value === 'string' ? value : null;
  };

  return {
    device: parseJson<DeviceStatus>(lookup(KV_KEY_DEVICE)),
    mood: parseJson<CurrentMood>(lookup(KV_KEY_MOOD)),
  };
}

export async function writeDeviceStatus(kv: KVNamespace, status: DeviceStatus): Promise<void> {
  // 不传 expiration/expirationTtl
  await kv.put(KV_KEY_DEVICE, JSON.stringify(status));
}

export async function writeCurrentMood(kv: KVNamespace, mood: CurrentMood): Promise<void> {
  await kv.put(KV_KEY_MOOD, JSON.stringify(mood));
}
