// POST /api/heartbeat —— 需 Bearer 鉴权
//
// 关键设计：
// 1. 先验 token 再碰 KV —— 非法请求 0 KV 成本（失效快）
// 2. lastSeenAt 由服务端 Date.now() 生成，客户端 clientTs 仅存作诊断
// 3. 幂等 = last-write-wins，不加读守卫（单设备重试覆盖无害）
// 4. 不设 TTL

import { nextExpectedMs } from '../config';
import { recordHeartbeatHistory } from '../jobs/history';
import { isAuthorized } from '../lib/auth';
import { writeDeviceStatus } from '../lib/kv';
import { jsonError, jsonOk, methodNotAllowed } from '../lib/response';
import { readJson, validateHeartbeat } from '../lib/validate';
import { SCHEMA_VERSION, type DeviceStatus, type Env } from '../types';

export async function handleHeartbeat(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  // 鉴权优先：不合法请求不消耗任何 KV 操作
  if (!isAuthorized(request, env)) {
    return jsonError(401, 'unauthorized', 'Missing or invalid bearer token');
  }

  const body = await readJson(request);
  if (!body.ok) {
    return jsonError(body.code === 'payload_too_large' ? 413 : body.code === 'unsupported_media_type' ? 415 : 400, body.code, body.message);
  }

  const parsed = validateHeartbeat(body.value);
  if (!parsed.ok) {
    return jsonError(400, parsed.code, parsed.message);
  }

  const now = Date.now();
  const input = parsed.value;

  const status: DeviceStatus = {
    schemaVersion: SCHEMA_VERSION,
    lastSeenAt: now,
    clientTs: input.clientTs,
    batteryPercent: input.batteryPercent,
    charging: input.charging,
    chargeSource: input.chargeSource,
    temperatureC: input.temperatureC,
    network: input.network,
    deviceName: input.deviceName,
    appVersion: input.appVersion,
    seq: input.seq,
  };

  try {
    await writeDeviceStatus(env.STATUS_KV, status);
  } catch {
    // 不泄露内部异常细节；可能是 KV 写额度耗尽
    return jsonError(500, 'internal_error', 'Failed to persist heartbeat');
  }

  // 历史写入放在响应之后：不增加上报延迟，失败也不改变这次心跳的结果。
  // 顺序很重要——先 KV（当前状态）后 D1（历史），当前状态永远不会被历史拖累。
  if (env.HISTORY_DB) {
    const db = env.HISTORY_DB;
    ctx.waitUntil(
      recordHeartbeatHistory(env, db, now, {
        batteryPercent: status.batteryPercent,
        charging: status.charging,
        chargeSource: status.chargeSource,
      }).catch(() => {
        // 静默降级：D1 出问题不该让手机的重试逻辑变得更复杂
      }),
    );
  }

  return jsonOk({
    receivedAt: now,
    nextExpectedInMs: nextExpectedMs(env),
  });
}