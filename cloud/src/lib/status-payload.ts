// 状态响应体：`/api/status` 与静态 `status.json` **共用同一份实现**。
//
// 为什么不各写一份：字段与过滤口径一旦分叉，就会出现「关掉 SHOW_MOOD、静态文件
// 里却还带着心情」这类问题——两边都得记得改，迟早漏一处。静态数据域的隐私承诺
// 完全依赖这里：`SHOW_*` 必须在**这一层**就把字段抹掉，而不是靠前端隐藏。

import { offlineThresholdMs, siteConfig } from '../config';
import { readBoth } from './kv';
import { SCHEMA_VERSION, type DeviceStatus, type Env, type SiteConfig } from '../types';

/**
 * 只暴露允许公开的字段。
 *
 * `SHOW_TEMPERATURE` / `SHOW_NETWORK` 必须是**服务端**的过滤：只在前端隐藏元素，
 * 任何人直接 curl 这个接口还是能拿到温度与网络 —— 那是隐私开关的假象。
 */
export function publicDevice(device: DeviceStatus, site: SiteConfig) {
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

/**
 * 组装一份状态载荷（`ok:true` 由调用方补）。
 *
 * 返回 `null` 表示 KV 读失败——调用方各自决定怎么处理：
 * 接口回 503（不写缓存），静态发布器直接跳过（**宁可旧也不要用空数据覆盖**）。
 *
 * 在线/掉线在**读取时**计算：掉线时没有任何写入发生，只能靠读取时刻的墙钟推断。
 * 静态文件的 `online` 因此是「生成时刻」的快照，页面还会按 `lastSeenAt` 本地重算。
 */
export async function buildStatusPayload(env: Env, now: number): Promise<Record<string, unknown> | null> {
  const site = siteConfig(env);
  const threshold = offlineThresholdMs(env);

  let device: DeviceStatus | null;
  let mood: Awaited<ReturnType<typeof readBoth>>['mood'];
  try {
    const both = await readBoth(env.STATUS_KV);
    device = both.device;
    mood = both.mood;
  } catch {
    return null;
  }

  const lastSeenAt = device?.lastSeenAt ?? null;
  const offlineForMs = lastSeenAt === null ? null : Math.max(0, now - lastSeenAt);
  const online = offlineForMs !== null && offlineForMs <= threshold;

  return {
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
  };
}
