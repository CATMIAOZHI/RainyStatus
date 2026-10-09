// 历史与导出口径的纯函数单测。
//
// 这些是最容易「写错了也看不出来」的地方：空桶被当成 0%、未知被当成未充电、
// 跨桶的充电被多算一次。线上发现不了，只能在这里挡住。
//
// 注意：小时/日汇总的聚合**不在这里测**——它已经改成 SQL（`src/lib/bucket-sql.ts`），
// 由 `cloud/scripts/d1check.py` 按原文跑真 SQL 验证。留一份 JS 版聚合只会让覆盖率说谎。

import { describe, expect, it } from 'vitest';
import {
  DAY_MS,
  HOUR_MS,
  SLOT_MS,
  buildHistoryExport,
  countChargeSessions,
  dayStartOf,
  hourStartOf,
  parseRange,
  slotStartOf,
  type RawSample,
} from '../src/lib/history';

const T0 = Date.UTC(2026, 0, 2, 3, 0, 0); // 固定基准时刻，避免测试随当前时间漂移

function sample(index: number, percent: number | null, charging: boolean | null): RawSample {
  const slot = T0 + index * SLOT_MS;
  return { slotStart: slot, receivedAt: slot + 60_000, percent, charging };
}

describe('时间分桶', () => {
  it('按 10 分钟取整，且不受本地时区影响', () => {
    expect(slotStartOf(T0 + 5 * 60_000)).toBe(T0 + 0 * SLOT_MS);
    expect(slotStartOf(T0 + 9 * 60_000 + 59_000)).toBe(T0);
    expect(slotStartOf(T0 + 10 * 60_000)).toBe(T0 + SLOT_MS);
  });

  it('小时与 UTC 日按 epoch 取整', () => {
    expect(hourStartOf(T0)).toBe(T0);
    expect(hourStartOf(T0 + 59 * 60_000)).toBe(T0);
    expect(hourStartOf(T0 + 60 * 60_000)).toBe(T0 + HOUR_MS);
    // UTC 午夜起点必然整除
    expect(dayStartOf(T0) % DAY_MS).toBe(0);
  });
});

describe('充电次数', () => {
  it('数「未充 → 充电」的跳变', () => {
    const rows = [
      sample(0, 50, false),
      sample(1, 60, true), // 第 1 次
      sample(2, 80, true),
      sample(3, 90, false),
      sample(4, 70, true), // 第 2 次
    ];
    expect(countChargeSessions(rows, T0, T0 + HOUR_MS, null)).toBe(2);
  });

  it('未知样本既不算开始也不算结束', () => {
    const rows = [sample(0, 50, true), sample(1, 55, null), sample(2, 60, true)];
    // 前一条是充电中，中间未知不改变状态 → 不额外计数
    expect(countChargeSessions(rows, T0, T0 + HOUR_MS, null)).toBe(1);
  });

  it('窗口起点前已经充着电（即使中间断了很久）不算新的一次', () => {
    // prevCharging 由数据库单独查「上一条已知状态」，与回看多久无关
    const rows = [sample(0, 60, true), sample(1, 70, true)];
    expect(countChargeSessions(rows, T0, T0 + HOUR_MS, true)).toBe(0);
  });

  it('窗口起点前没有已知状态时，第一个充电样本算一次（无法证明是延续）', () => {
    expect(countChargeSessions([sample(0, 60, true)], T0, T0 + HOUR_MS, null)).toBe(1);
  });

  it('窗口外的样本不参与计数', () => {
    const rows = [sample(-1, 60, false), sample(0, 61, true), sample(6, 62, false)];
    expect(countChargeSessions(rows, T0, T0 + HOUR_MS, null)).toBe(1);
  });

  it('窗口按「收到时刻」划界：非整槽边界上的「未充」样本必须参与跳变判断', () => {
    // 窗口起点不是 10 分钟整槽（真实场景：now - 24h 落在任意时刻）
    const from = T0 + 15 * 60_000;
    const rows = [
      // 槽在起点之前（T0+10min），但收到时刻在窗口内。丢了它就会把「延续」当成新充电。
      { slotStart: T0 + 10 * 60_000, receivedAt: T0 + 16 * 60_000, percent: 50, charging: false },
      { slotStart: T0 + 20 * 60_000, receivedAt: T0 + 21 * 60_000, percent: 60, charging: true },
    ];
    // 起点前是「充电中」→ 中间断开了 → 又插上：应计 1 次（丢样本会算成 0）
    expect(countChargeSessions(rows, from, from + HOUR_MS, true)).toBe(1);
  });
});

describe('24 小时导出', () => {
  const now = T0 + 24 * HOUR_MS;

  it('窗口外的样本不进 points', () => {
    // 槽 -10（约 1 小时 40 分钟前）在窗口外，槽 140 在窗口内
    const rows = [sample(-10, 10, false), sample(140, 55, false)];
    const payload = buildHistoryExport({
      rows,
      mood: [],
      range: '24h',
      windowMs: 24 * HOUR_MS,
      resolutionMs: SLOT_MS,
      now,
      prevCharging: null,
    });
    expect(payload.points).toHaveLength(1);
    expect(payload.points[0]?.p).toBe(55);
    expect(payload.coverage.sampleCount).toBe(1);
  });

  it('没有任何样本时 availability = empty，而不是画一条 0% 的线', () => {
    const payload = buildHistoryExport({
      rows: [],
      mood: [],
      range: '24h',
      windowMs: 24 * HOUR_MS,
      resolutionMs: SLOT_MS,
      now,
      prevCharging: null,
    });
    expect(payload.availability).toBe('empty');
    expect(payload.points).toHaveLength(0);
    expect(payload.sourceThrough).toBeNull();
  });

  it('窗口起点没被覆盖时 availability = partial（刚部署的情况）', () => {
    const rows = [sample(140, 55, false)];
    const payload = buildHistoryExport({
      rows,
      mood: [],
      range: '24h',
      windowMs: 24 * HOUR_MS,
      resolutionMs: SLOT_MS,
      now,
      prevCharging: null,
    });
    expect(payload.availability).toBe('partial');
  });

  it('窗口按「收到时刻」判定：槽在窗口外、但收到时刻在窗口内的样本要画出来', () => {
    const from = now - 24 * HOUR_MS;
    const rows = [
      // 槽比窗口起点早（10 分钟对齐的槽横跨了窗口起点），但确实是窗口开始后才收到的
      { slotStart: from - SLOT_MS, receivedAt: from + 60_000, percent: 42, charging: false },
      // 窗口起点之前收到的：属于上一段时间，不进本次导出
      { slotStart: from - 3 * SLOT_MS, receivedAt: from - 60_000, percent: 10, charging: false },
    ];
    const payload = buildHistoryExport({
      rows,
      mood: [],
      range: '24h',
      windowMs: 24 * HOUR_MS,
      resolutionMs: SLOT_MS,
      now,
      prevCharging: null,
    });
    expect(payload.points).toHaveLength(1);
    expect(payload.points[0]?.p).toBe(42);
    expect(payload.coverage.firstSampleAt).toBe(from + 60_000);
  });

  it('充电标记与心情事件原样带出', () => {
    const rows = [sample(140, 55, true)];
    const payload = buildHistoryExport({
      rows,
      mood: [{ at: now - 1000, text: '困了喵', emoji: '😴' }],
      range: '24h',
      windowMs: 24 * HOUR_MS,
      resolutionMs: SLOT_MS,
      now,
      prevCharging: false,
    });
    expect(payload.points[0]?.c).toBe(1);
    expect(payload.chargeSessions).toBe(1);
    expect(payload.mood[0]?.text).toBe('困了喵');
  });
});

describe('档位白名单', () => {
  it('只接受已知档位', () => {
    expect(parseRange(null)).toBe('24h');
    expect(parseRange('24h')).toBe('24h');
    expect(parseRange('1y')).toBeNull(); // 尚未开放
    expect(parseRange('24h&x=1')).toBeNull();
    expect(parseRange('')).toBeNull();
    expect(parseRange('__proto__')).toBeNull();
  });
});
