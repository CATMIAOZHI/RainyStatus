// GET /api/history?range=24h —— 公开只读，但需要过 Turnstile 人机验证。
//
// 三道门，顺序刻意从便宜到昂贵：
//   1. 档位白名单（纯字符串判断，0 成本）—— 不接受任意 from/to/limit
//   2. Turnstile 校验（一次外部请求）—— 挡住脚本批量刷
//   3. 按主键读**预生成的 1 行导出** —— 绝不现场扫描历史表
//
// 这样攻击者最坏也只能让 Worker 多跑几次「读一行」，而不是让数据库去扫一年数据。

import { historyConfig, siteConfig } from '../config';
import { readExport } from '../lib/db';
import { RANGE_SPECS, parseRange } from '../lib/history';
import { jsonError, jsonOk, methodNotAllowed } from '../lib/response';
import { verifyTurnstile } from '../lib/turnstile';
import type { Env } from '../types';

/** 导出超过这个时长没更新就标记 stale：前端要显示「数据可能不是最新」，而不是假装实时 */
const STALE_AFTER_MS = 45 * 60 * 1000;

export async function handleHistory(request: Request, env: Env): Promise<Response> {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return methodNotAllowed('GET, HEAD');
  }

  const config = historyConfig(env);

  // 未开启时不暴露任何历史：返回 enabled:false，前端据此隐藏图表卡
  if (!config.enabled) {
    return jsonOk({ enabled: false, ranges: [] });
  }

  const url = new URL(request.url);
  const range = parseRange(url.searchParams.get('range'));
  if (range === null) {
    // 参数在任何数据库操作之前就被拒掉
    return jsonError(400, 'invalid_payload', `range must be one of ${Object.keys(RANGE_SPECS).join('/')}`);
  }

  // 白名单之外还要过站点开关：`HISTORY_RANGES` 是「对外公开哪些档位」的唯一口径。
  // 只看实现里有哪些档位的话，运维把 24h 从配置里去掉时接口依然会照常返回。
  if (!config.ranges.includes(range)) {
    return jsonError(404, 'not_found', 'This range is not published');
  }

  if (config.requireTurnstile) {
    const token = request.headers.get('cf-turnstile-response') ?? '';
    const result = await verifyTurnstile(config.secret ?? '', token, request.headers.get('cf-connecting-ip'));
    if (!result.ok) {
      // 403 而不是 401：这里不是「你是谁」的问题，而是「你是不是人」的问题。
      // 不回显 reason 的内部细节，避免给探测者当反馈信号。
      return jsonError(403, 'challenge_failed', 'Human verification failed');
    }
  }

  if (!env.HISTORY_DB) {
    return jsonError(503, 'history_unavailable', 'History storage is not configured');
  }

  let row: Awaited<ReturnType<typeof readExport>> = null;
  try {
    row = await readExport(env.HISTORY_DB, range);
  } catch {
    return jsonError(503, 'history_unavailable', 'History is temporarily unavailable');
  }

  // 还没有生成过导出（刚部署、Cron 还没跑）：这是「暂时不可用」，不是「没有数据」
  if (row === null) {
    return jsonError(503, 'history_unavailable', 'History export is not ready yet');
  }

  const now = Date.now();

  // 导出的 JSON 是**预生成**的：如果 SHOW_MOOD 之后被关掉，旧导出里仍然带着心情。
  // 隐私开关必须在这里再执行一次（按当前配置过滤），不能等 Cron 重建 ——
  // Cron 失败或没跑的时候，那个「已经关掉的开关」就成了一句空话。
  let data: Record<string, unknown>;
  try {
    const parsed: unknown = JSON.parse(row.payload);
    // 只认「普通对象」：JSON.parse 能成功不代表形状对（`null`、数组、数字都是合法 JSON），
    // 后面还要按字段读，形状不对就得当「导出损坏」处理，而不是抛出去变成 500。
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
      return jsonError(503, 'history_unavailable', 'History export is corrupted');
    }
    data = parsed as Record<string, unknown>;
  } catch {
    return jsonError(503, 'history_unavailable', 'History export is corrupted');
  }
  if (!siteConfig(env).showMood) data.mood = [];

  return jsonOk({
    enabled: true,
    challenge: config.requireTurnstile ? 'turnstile' : 'none',
    stale: now - row.generatedAt > STALE_AFTER_MS,
    exportGeneratedAt: row.generatedAt,
    // payload 是预生成的完整导出（含 points / coverage），直接透传，不在请求里加工
    data,
  });
}