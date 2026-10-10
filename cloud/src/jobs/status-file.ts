// 静态数据文件发布（可选功能，默认关闭，见 config.ts 的 dataConfig）
//
// 为什么要有它：主状态接口每个访客都要打一次 Worker（免费额度 10 万请求/天），
// 而「R2 + 独立域 + CDN 缓存」这条路**不经过 Worker** —— 静态文件的读取既不消耗
// Worker 请求、也不消耗 KV 读。代价是缓存 TTL 内的数据是旧的（默认 60 秒）。
//
// 三条不变量：
//  1. **失败绝不覆盖上一版**：KV 读不到就直接跳过，宁可让数据旧一点，
//     也不能把「服务故障」写成「设备没上报」永久留在对象存储里；
//  2. 对象头只写缓存指令，TTL 以部署侧 Cache Rule 为准（见 docs/api.md 的静态数据域一节）；
//  3. 内容就是 `/api/status` 的公开响应体，**不带任何秘密**：能不能公开由
//     `lib/status-payload.ts` 里那几个 `SHOW_*` 开关决定。

import { dataConfig } from '../config';
import { buildStatusPayload } from '../lib/status-payload';
import type { Env } from '../types';

/** 对象键。部署侧的缓存规则、WAF 白名单都要与它一致，改这里等于改契约 */
export const STATUS_FILE_KEY = 'status.json';
/** 对象自带的缓存时长（秒）。Cache Rule 里应显式设成同一个值，别指望对象头 */
export const STATUS_FILE_MAX_AGE_SECONDS = 60;

/**
 * 发布一次 `status.json`。返回是否真的写了。
 *
 * 调用点有两处：cron（每轮兜底，负责把「已掉线」翻转过去）与心跳成功后（让页面
 * 尽快看到新数值）。两处都必须 `catch` 掉失败——它只是加速手段，不是主链路。
 */
export async function publishStatusFile(env: Env, now: number): Promise<boolean> {
  const { enabled } = dataConfig(env);
  const bucket = env.DATA_BUCKET;
  if (!enabled || bucket === undefined) return false;

  const payload = await buildStatusPayload(env, now);
  if (payload === null) return false;

  await bucket.put(STATUS_FILE_KEY, JSON.stringify({ ok: true, ...payload }), {
    httpMetadata: {
      contentType: 'application/json; charset=utf-8',
      cacheControl: `public, max-age=${STATUS_FILE_MAX_AGE_SECONDS}`,
    },
  });
  return true;
}