// 定时维护任务：生成公开导出、清理过期数据。
//
// 为什么聚合不放在这里：Cron 会漏跑、会重叠、Free 版每次 CPU 只有 10ms。
// 把「聚合」放在写心跳的同一个事务里（见 lib/db.recordSample）就没有
// 「原始数据被删了但还没汇总」的窗口；Cron 只做可以慢、可以失败重来的事。

import {
  DEFAULT_RETENTION,
  acquireLease,
  pruneHistory,
  readChargingStateBefore,
  readMoodSince,
  readSamplesSince,
  recomputeCurrentBuckets,
  recordSample,
  writeExport,
} from '../lib/db';
import { RANGE_SPECS, buildHistoryExport, type RangeKey } from '../lib/history';
import { historyConfig } from '../config';
import type { Env } from '../types';

const LEASE_MS = 10 * 60 * 1000;

/**
 * 生成一份导出并写回 `history_exports`。
 *
 * 注意读的行数上限：24h 档一次约 150 行。档位越长，导出代价越大（这也是
 * 「网页第一期只开 24h」的原因之一），扩档位前请先按 docs/roadmap.md 核对额度。
 */
async function buildExport(db: D1Database, range: RangeKey, now: number, includeMood: boolean): Promise<void> {
  const spec = RANGE_SPECS[range];
  const from = now - spec.windowMs;
  const rows = await readSamplesSince(db, from);
  const mood = includeMood ? await readMoodSince(db, from) : [];
  // 窗口之前最后一条已知充电状态：单独查 1 行，不用「多读一段样本」去近似。
  // 用近似值的话，断档超过那段就会把「昨晚插着电、今早还在充」多算成一次充电，
  // 而且和小时/日汇总（bucket-sql.ts，回看长度不限）口径不一致。
  const prevCharging = await readChargingStateBefore(db, from);

  const payload = buildHistoryExport({
    rows,
    mood,
    range,
    windowMs: spec.windowMs,
    resolutionMs: spec.resolutionMs,
    now,
    prevCharging,
  });

  await writeExport(db, range, now, JSON.stringify(payload));
}

/**
 * 心跳带来的历史写入（在响应返回之后用 waitUntil 跑，不拖慢上报）。
 *
 * 三条不变量：
 *  1. **先写当前状态，再写历史**：KV 失败时根本不会走到这里（调用方已经返回失败）。
 *  2. 历史失败不影响心跳结果，也不让 App 重发——重发只会再写一次 KV，白白多花额度。
 *  3. 同一个 10 分钟槽只留第一个样本，所以重试、补发都不会让曲线多出假点。
 *  4. 原始行与两个汇总桶在**同一个 D1 batch（事务）**里落盘：不存在「写了一半」的
 *     状态，也不会用旧快照盖掉新汇总。
 */
export async function recordHeartbeatHistory(
  env: Env,
  db: D1Database,
  now: number,
  sample: {
    batteryPercent: number | null;
    charging: boolean | null;
    chargeSource: string | null;
  },
): Promise<void> {
  if (!historyConfig(env).collect) return;

  await recordSample(db, now, sample.batteryPercent, sample.charging, sample.chargeSource);
}

/**
 * Cron 入口。
 *
 * 失败策略：**永远不让上一版好数据被空数据覆盖**。生成或写回失败就保持原样，
 * 下一轮直接重建「现在的窗口」，不补跑错过的每一次。
 */
export async function runHistoryMaintenance(
  env: Env,
  now: number,
  ranges: RangeKey[],
  includeMood: boolean,
): Promise<{ ran: boolean; exported: RangeKey[] }> {
  const db = env.HISTORY_DB;
  if (!db) return { ran: false, exported: [] };

  // 重叠的 Cron 直接退出：两个任务同时生成导出没有意义，还会争抢写额度
  if (!(await acquireLease(db, 'history-maintenance', now, LEASE_MS))) {
    return { ran: false, exported: [] };
  }

  // 自愈一次当前桶：正常路径已由心跳事务写好，这里只在极端情况下（batch 整体失败、
  // 手工补数据）把汇总修正回来。放在生成导出之前，保证导出看到的是修好的桶。
  try {
    await recomputeCurrentBuckets(db, now);
  } catch {
    // 自愈失败不影响导出与清理
  }

  const exported: RangeKey[] = [];
  for (const range of ranges) {
    try {
      await buildExport(db, range, now, includeMood);
      exported.push(range);
    } catch {
      // 单档失败不影响其它档：长档最容易超时，不该拖累 24h
    }
  }

  try {
    await pruneHistory(db, now, DEFAULT_RETENTION);
  } catch {
    // 清理失败只是多留了些数据，下一轮再来
  }

  return { ran: true, exported };
}