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
 */
export async function readBoth(
  kv: KVNamespace,
): Promise<{ device: DeviceStatus | null; mood: CurrentMood | null }> {
  const result = await kv.get([KV_KEY_DEVICE, KV_KEY_MOOD], { cacheTtl: KV_CACHE_TTL });
  if (result === null || typeof result !== 'object' || Array.isArray(result)) {
    return { device: null, mood: null };
  }
  const map = result as Record<string, string | null>;
  return {
    device: parseJson<DeviceStatus>(map[KV_KEY_DEVICE] ?? null),
    mood: parseJson<CurrentMood>(map[KV_KEY_MOOD] ?? null),
  };
}

export async function writeDeviceStatus(kv: KVNamespace, status: DeviceStatus): Promise<void> {
  // 不传 expiration/expirationTtl
  await kv.put(KV_KEY_DEVICE, JSON.stringify(status));
}

export async function writeCurrentMood(kv: KVNamespace, mood: CurrentMood): Promise<void> {
  await kv.put(KV_KEY_MOOD, JSON.stringify(mood));
}
