// D1 访问层：样本写入、桶重算、导出读写、清理、任务租约。
//
// 约定：本文件的函数**只负责把 SQL 发出去**，失败由调用方决定怎么处理。
// 历史永远是「尽力而为」——它挂掉不能让当前状态的上报跟着挂。

import { BUCKET_UPSERT_SQL } from './bucket-sql';
import { DAY_MS, HOUR_MS, SLOT_MS, dayStartOf, hourStartOf, slotStartOf, type RawSample } from './history';

type RawRow = {
  slot_start: number;
  received_at: number;
  battery_percent: number | null;
  charging: number | null;
};

function toSample(row: RawRow): RawSample {
  return {
    slotStart: row.slot_start,
    receivedAt: row.received_at,
    percent: row.battery_percent,
    charging: row.charging === null ? null : row.charging === 1,
  };
}
/**
 * 两个汇总桶的 UPSERT 语句（小时 + 日），绑定参数按 bucket-sql.ts 里的 `?` 顺序：
 * 桶起点、跳变判定窗口起止、WHERE 窗口起止（两处窗口必然相同，否则口径就分叉了）。
 */
function bucketUpserts(db: D1Database, now: number): D1PreparedStatement[] {
  const hourStart = hourStartOf(now);
  const dayStart = dayStartOf(now);
  return [
    db
      .prepare(BUCKET_UPSERT_SQL.replace('{table}', 'history_hourly'))
      .bind(hourStart, hourStart, hourStart + HOUR_MS, hourStart, hourStart + HOUR_MS),
    db
      .prepare(BUCKET_UPSERT_SQL.replace('{table}', 'history_daily'))
      .bind(dayStart, dayStart, dayStart + DAY_MS, dayStart, dayStart + DAY_MS),
  ];
}


/**
 * 记一条样本，并在**同一个事务**里重算所在的小时桶与日桶。
 *
 * 关键：三条语句放进一个 D1 batch。batch 即事务，所以「插入原始行」和
 * 「按原始行重算汇总」之间不会被别的写入插进来——这消灭了「读到旧集合、
 * 把新汇总盖回去」的丢更新窗口（审计 P1）。
 *
 * 汇总本身是 `INSERT ... SELECT FROM history_raw` 现算（见 bucket-sql.ts），
 * 因此幂等：重试、补发、同槽重复都不会让计数写重。
 */
export async function recordSample(
  db: D1Database,
  now: number,
  percent: number | null,
  charging: boolean | null,
  chargeSource: string | null,
): Promise<void> {
  await db.batch([
    db
      .prepare(
        `INSERT OR IGNORE INTO history_raw (slot_start, received_at, battery_percent, charging, charge_source)
         VALUES (?, ?, ?, ?, ?)`,
      )
      .bind(slotStartOf(now), now, percent, charging === null ? null : charging ? 1 : 0, chargeSource),
    ...bucketUpserts(db, now),
  ]);
}

/**
 * 自愈：按原始行重算「当前小时」和「当前 UTC 日」两个桶。
 *
 * 正常路径下这两个桶在 `recordSample` 的同一个事务里已经算好了，这里只是兜底
 * （例如某次 batch 整体失败、或迁移后补写数据）。和 recordSample 用的是同一份
 * SQL，所以不会出现「修复出来的口径和正式写入的口径不一样」。
 *
 * 为什么只重算「当前」桶：原始行只保留 7 天。去重算一个已经被清理掉一半的旧桶，
 * 只会把汇总算小——历史桶一旦冻结就不再碰。
 *
 * 成本：两条 INSERT...SELECT，各扫当天约 144 行原始数据，远小于免费额度。
 */
export async function recomputeCurrentBuckets(db: D1Database, now: number): Promise<void> {
  // 同一个 batch 里两个 upsert：要么都生效，要么都不生效，不会只更新小时漏掉日
  await db.batch(bucketUpserts(db, now));
}

/** 记一条心情事件。内容由 App 端去重，这里不做「读-比较-写」 */
export async function insertMoodEvent(
  db: D1Database,
  updatedAt: number,
  text: string,
  emoji: string | null,
): Promise<void> {
  await db
    .prepare(`INSERT INTO history_mood (updated_at, text, emoji) VALUES (?, ?, ?)`)
    .bind(updatedAt, text, emoji)
    .run();
}

export type HistoryRow = { slotStart: number; receivedAt: number; percent: number | null; charging: boolean | null };

/**
 * 读一段窗口内的原始样本（按**接收时刻**，与导出、前置状态查询同一口径）。
 * 走 `received_at` 索引，读回行数与窗口长度成正比 —— 这是公开接口成本的唯一来源。
 */
export async function readSamplesSince(db: D1Database, from: number): Promise<RawSample[]> {
  const result = await db
    .prepare(
      `SELECT slot_start, received_at, battery_percent, charging
       FROM history_raw WHERE received_at >= ? ORDER BY received_at`,
    )
    .bind(from)
    .all<RawRow>();
  return (result.results ?? []).map(toSample);
}

export async function readMoodSince(db: D1Database, from: number) {
  const result = await db
    .prepare(`SELECT updated_at, text, emoji FROM history_mood WHERE updated_at >= ? ORDER BY updated_at`)
    .bind(from)
    .all<{ updated_at: number; text: string; emoji: string | null }>();
  return (result.results ?? []).map((row) => ({ at: row.updated_at, text: row.text, emoji: row.emoji }));
}

/**
 * 取「某时刻之前」最后一条**已知**充电状态（1 行，走 received_at 索引倒序）。
 *
 * 为什么要专门查一次，而不是把窗口前的样本一起读回来算：
 * 一是省行数（一次 1 行 vs 多读 2 小时 ≈ 12 行），二是**口径**——回看长度一旦写死，
 * 断档超过它时就会把「昨晚插着电、今早还在充」误判成一次新充电。
 * 这里和 `bucket-sql.ts` 里的相关子查询是同一个语义：不限回看长度，找到为止。
 */
export async function readChargingStateBefore(db: D1Database, before: number): Promise<boolean | null> {
  const row = await db
    .prepare(
      `SELECT charging FROM history_raw
       WHERE received_at < ? AND charging IS NOT NULL
       ORDER BY received_at DESC LIMIT 1`,
    )
    .bind(before)
    .first<{ charging: number }>();
  return row === null ? null : row.charging === 1;
}

/** 按主键读导出：公开接口的数据库成本就是这一行 */
export async function readExport(
  db: D1Database,
  rangeKey: string,
): Promise<{ generatedAt: number; payload: string } | null> {
  const row = await db
    .prepare(`SELECT generated_at, payload FROM history_exports WHERE range_key = ?`)
    .bind(rangeKey)
    .first<{ generated_at: number; payload: string }>();
  return row === null ? null : { generatedAt: row.generated_at, payload: row.payload };
}

export async function writeExport(
  db: D1Database,
  rangeKey: string,
  generatedAt: number,
  payload: string,
): Promise<void> {
  await db
    .prepare(
      `INSERT INTO history_exports (range_key, generated_at, payload) VALUES (?, ?, ?)
       ON CONFLICT(range_key) DO UPDATE SET generated_at = excluded.generated_at, payload = excluded.payload`,
    )
    .bind(rangeKey, generatedAt, payload)
    .run();
}

export type RetentionPolicy = { rawMs: number; hourlyMs: number; dailyMs: number };

/**
 * 分层保留期。
 *
 * - 原始 7 天：`/api/history?range=24h` 与「重算当前桶」的唯一数据源；
 * - 小时 35 天：日级曲线的细节余量，也为将来的 7d/30d 档位留料；
 * - 日 5 年（1826 天）：**每天一行，5 年也才 1826 行**，几 MB，D1 免费给 5 GB。
 *   所以「以后要做 3 年 / 5 年历史」不需要改架构，这个常量就是全部代价；
 *   反过来，留短了就是把以后想看的旧数据提前删掉，得不偿失。
 *
 * 注意：保留期 ≠ 公开档位。现在对外只开 `24h`（`RANGE_SPECS`），
 * 长档位属于 `docs/roadmap.md` 的 T2。
 */
export const DEFAULT_RETENTION: RetentionPolicy = {
  rawMs: 7 * DAY_MS,
  hourlyMs: 35 * DAY_MS,
  dailyMs: 1826 * DAY_MS,
};

/** 按索引分批删除过期数据；返回删掉的批次数，便于观察是否追上 */
export async function pruneHistory(db: D1Database, now: number, policy: RetentionPolicy): Promise<void> {
  await db.batch([
    db.prepare(`DELETE FROM history_raw WHERE received_at < ?`).bind(now - policy.rawMs),
    db.prepare(`DELETE FROM history_hourly WHERE bucket_start < ?`).bind(now - policy.hourlyMs),
    db.prepare(`DELETE FROM history_daily WHERE bucket_start < ?`).bind(now - policy.dailyMs),
    db.prepare(`DELETE FROM history_mood WHERE updated_at < ?`).bind(now - policy.dailyMs),
  ]);
}

/**
 * 任务租约：防止两次 Cron 重叠执行（生成导出与清理同时跑）。
 *
 * 用 D1 而不是内存变量：Workers 的 isolate 会随时被回收、还会跨机房并行，
 * 内存锁在 Cloudflare 上等于没有锁。
 */
export async function acquireLease(db: D1Database, task: string, now: number, ttlMs: number): Promise<boolean> {
  const inserted = await db
    .prepare(`INSERT OR IGNORE INTO history_tasks (task, leased_at) VALUES (?, ?)`)
    .bind(task, now)
    .run();
  if ((inserted.meta?.changes ?? 0) > 0) return true;

  const stolen = await db
    .prepare(`UPDATE history_tasks SET leased_at = ? WHERE task = ? AND leased_at < ?`)
    .bind(now, task, now - ttlMs)
    .run();
  return (stolen.meta?.changes ?? 0) > 0;
}

export { SLOT_MS };
