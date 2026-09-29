// GET /api/status —— 公开只读
//
// 关键设计：
// 1. 在线/掉线在【读取时】计算，不在写入时计算 —— 掉线时没有任何写入发生，
//    只能靠读取时刻的墙钟推断
// 2. lastSeenAt 是服务端写入时刻，客户端时钟不准也不会影响判定
// 3. 同时下发 site 配置，网页无需硬编码任何个人信息

import { offlineThresholdMs, siteConfig } from '../config';
import { readBoth } from '../lib/kv';
import { jsonOk, methodNotAllowed } from '../lib/response';
import { SCHEMA_VERSION, type DeviceStatus, type Env } from '../types';

/** 只暴露允许公开的字段；未上报的可选字段返回 null */
function publicDevice(device: DeviceStatus) {
  return {
    batteryPercent: device.batteryPercent ?? null,
    charging: device.charging ?? null,
    chargeSource: device.chargeSource ?? null,
    temperatureC: device.temperatureC ?? null,
    network: device.network ?? null,
    deviceName: device.deviceName ?? null,
    appVersion: device.appVersion ?? null,
    clientTs: device.clientTs ?? null,
  };
}

export async function handleStatus(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return methodNotAllowed('GET, HEAD');
  }

  const now = Date.now();
  const threshold = offlineThresholdMs(env);

  let device: DeviceStatus | null = null;
  let mood = null;
  try {
    const both = await readBoth(env.STATUS_KV);
    device = both.device;
    mood = both.mood;
  } catch {
    // KV 异常（含免费额度耗尽）时仍返回结构完整的响应，让网页能降级展示
    device = null;
    mood = null;
  }

  const lastSeenAt = device?.lastSeenAt ?? null;
  const offlineForMs = lastSeenAt === null ? null : Math.max(0, now - lastSeenAt);
  const online = offlineForMs !== null && offlineForMs <= threshold;

  return jsonOk({
    schemaVersion: SCHEMA_VERSION,
    generatedAt: now,
    online,
    offlineThresholdMs: threshold,
    lastSeenAt,
    offlineForMs,
    device: device === null ? null : publicDevice(device),
    mood:
      mood === null
        ? null
        : { text: mood.text, emoji: mood.emoji ?? null, updatedAt: mood.updatedAt },
    // 站点配置（标题/主人名/头像/展示开关）由服务端下发
    site: siteConfig(env),
  });
}