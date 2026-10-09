// 历史数据的纯函数：分槽与导出口径。
//
// 这里刻意不碰 D1 / KV / fetch —— 全部是纯计算，便于单测覆盖。
// 注意：**小时/日汇总的聚合不在这里**，唯一实现在 `bucket-sql.ts`（SQL 现算，
// 见该文件顶部说明），用 `cloud/scripts/d1check.py` 按原文跑真 SQL 验证。
// 不要再在 JS 里写第二份聚合：两份实现迟早会对不上，而且单测覆盖的那份
// 未必是线上跑的那份。

/** 代表样本的槽宽：10 分钟。心跳间隔可以配到 60 秒，但曲线不需要那么细 */
export const SLOT_MS = 10 * 60 * 1000;
export const HOUR_MS = 60 * 60 * 1000;
export const DAY_MS = 24 * 60 * 60 * 1000;

export type RawSample = {
  slotStart: number;
  receivedAt: number;
  /** 0..100，读不到为 null */
  percent: number | null;
  /** 充电中 / 未充电 / 未知 */
  charging: boolean | null;
};

/** 向下取整到 10 分钟槽。epoch 原点就是 UTC 午夜，所以取模不受设备时区影响 */
export function slotStartOf(receivedAt: number): number {
  return Math.floor(receivedAt / SLOT_MS) * SLOT_MS;
}

export function hourStartOf(t: number): number {
  return Math.floor(t / HOUR_MS) * HOUR_MS;
}

export function dayStartOf(t: number): number {
  return Math.floor(t / DAY_MS) * DAY_MS;
}

/**
 * 一段时间内的充电开始次数（连续样本之间的 0/未知 → 1 跳变）。
 *
 * 窗口按 **`receivedAt`（服务端收到时刻）** 判定，与 `lib/db.ts` 里的 SQL、
 * 以及 `readChargingStateBefore` 完全同一口径。**不要**改用 `slotStart`：
 * 槽是按 10 分钟对齐的，窗口起点却是任意时刻（`now - 24h`），
 * 于是「槽在起点之前、但实际收到时刻在窗口内」的样本会被丢掉——
 * 那一条正好是窗口开始时的充电状态，丢了就会把一次延续误判成一次新充电。
 *
 * @param prevCharging 窗口开始前最后一条**已知**状态；必须由数据库按
 *   「received_at < from AND charging IS NOT NULL ORDER BY received_at DESC LIMIT 1」
 *   取出来（见 `lib/db.readChargingStateBefore`）。**不要**用「窗口前 2 小时的样本」
 *   去近似：断档超过那段就会把「昨晚插着电、今早还在充」误判成一次新充电。
 */
export function countChargeSessions(rows: RawSample[], from: number, to: number, prevCharging: boolean | null): number {
  let sessions = 0;
  let prev = prevCharging;
  for (const row of rows) {
    if (row.receivedAt < from || row.receivedAt > to) continue;
    if (row.charging === null) continue;
    if (row.charging && prev !== true) sessions += 1;
    prev = row.charging;
  }
  return sessions;
}

/** 24 小时曲线的一个点。字段名短是因为它会被序列化进导出 JSON */
export type ChartPoint = {
  /** 服务端收到时刻（epoch ms UTC） */
  t: number;
  /** 电量百分比；null = 这次没读到 */
  p: number | null;
  /** 1 = 充电中，0 = 未充电，null = 这次没读到充电状态（不能当「未充电」） */
  c: 0 | 1 | null;
};

export type MoodEvent = { at: number; text: string; emoji: string | null };

export type HistoryAvailability = 'empty' | 'partial' | 'available';

export type HistoryExport = {
  schemaVersion: 1;
  mode: 'samples';
  range: string;
  windowMs: number;
  resolutionMs: number;
  generatedAt: number;
  /** 数据实际覆盖到的最后时刻；和 generatedAt 差多少就是「多久没上报」 */
  sourceThrough: number | null;
  from: number;
  to: number;
  availability: HistoryAvailability;
  coverage: { firstSampleAt: number | null; lastSampleAt: number | null; sampleCount: number };
  /** 窗口内的充电开始次数 */
  chargeSessions: number;
  points: ChartPoint[];
  mood: MoodEvent[];
};

/** 断档超过这个长度就不连线：图上留缺口，比画一条假曲线诚实 */
const GAP_MS = 30 * 60 * 1000;

/**
 * 生成 24 小时导出。
 *
 * 语义上这是「云端收到的读数」，不是手机真实的采样轨迹：App 在断网时会把旧快照
 * 排队补发（`StatusRepository.kt` 的 flushPending），所以补齐的时间点只代表「云端何时收到」。
 * 因此这里不做插值、不做回填，缺口就是缺口。
 */
export function buildHistoryExport(args: {
  rows: RawSample[];
  mood: MoodEvent[];
  range: string;
  windowMs: number;
  resolutionMs: number;
  now: number;
  prevCharging: boolean | null;
}): HistoryExport {
  const { rows, mood, range, windowMs, resolutionMs, now, prevCharging } = args;
  const from = now - windowMs;
  /**
   * 窗口按 **`receivedAt`（服务端收到时刻）** 判定，不是 `slotStart`。
   *
   * 槽是 10 分钟对齐的，而窗口起点 `now - 24h` 是任意时刻：用 slotStart 过滤会把
   * 「槽落在起点之前、但收到时刻在窗口内」的那一条丢掉，而它恰好带着窗口开始时的
   * 充电状态——丢了就会把一次延续误判成一次新充电（同时也会少画一个点）。
   * 这里与 `readSamplesSince` / `readChargingStateBefore` / `bucket-sql.ts` 口径一致。
   */
  const inWindow = rows.filter((r) => r.receivedAt >= from && r.receivedAt <= now);

  const points: ChartPoint[] = inWindow.map((r) => ({
    t: r.receivedAt,
    p: r.percent,
    // 未知必须保持 null：把它写成 0 会让「没读到」在图上变成一条「未充电」的假事实
    c: r.charging === null ? null : r.charging ? 1 : 0,
  }));

  const first = inWindow[0];
  const last = inWindow[inWindow.length - 1];
  const firstSampleAt = first === undefined ? null : first.receivedAt;
  const lastSampleAt = last === undefined ? null : last.receivedAt;

  let availability: HistoryAvailability = 'available';
  if (inWindow.length === 0) {
    availability = 'empty';
  } else if (firstSampleAt === null || firstSampleAt > from + GAP_MS || lastSampleAt === null || now - lastSampleAt > GAP_MS) {
    // 窗口没被完整覆盖（刚部署、或手机长时间没上报）
    availability = 'partial';
  }

  return {
    schemaVersion: 1,
    mode: 'samples',
    range,
    windowMs,
    resolutionMs,
    generatedAt: now,
    sourceThrough: lastSampleAt,
    from,
    to: now,
    availability,
    coverage: { firstSampleAt, lastSampleAt, sampleCount: inWindow.length },
    chargeSessions: countChargeSessions(rows, from, now, prevCharging),
    points,
    mood,
  };
}

/**
 * 允许的档位白名单。
 *
 * 为什么不接受任意 `from/to/limit`：参数只要能被攻击者控制，就能被用来
 * 逼出「扫一整年」这种昂贵查询。固定档位下，服务端才知道自己会付出多少成本。
 */
export const RANGE_SPECS = {
  '24h': { windowMs: 24 * HOUR_MS, resolutionMs: SLOT_MS },
} as const;

export type RangeKey = keyof typeof RANGE_SPECS;

export function parseRange(value: string | null): RangeKey | null {
  if (value === null) return '24h';
  return Object.prototype.hasOwnProperty.call(RANGE_SPECS, value) ? (value as RangeKey) : null;
}
