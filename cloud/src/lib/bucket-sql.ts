// 桶汇总的 UPSERT SQL。
//
// 为什么把 SQL 单独放一个文件：它要能被验证脚本**按原文**取出来跑（见 scripts/d1check.py），
// 复制一份到测试里迟早会漂移成两份不一样的实现。
//
// 这份 SQL 的要点：
//  1. 汇总直接 `SELECT ... FROM history_raw` 现算，不是「读出来算好再写回去」——
//     所以它天然幂等，重复执行、重试、并发都不会把计数写重。
//  2. 它和「插入原始样本」放在同一个 D1 batch（= 同一个事务）里执行，见 lib/db.ts。
//     这样就不会出现「A 读到旧集合 → B 写入新数据 → A 把旧结果盖回去」的丢更新。
//  3. 充电次数用相关子查询找「上一条已知状态」，**不受桶边界限制**：
//     昨晚插上电一直充到今天，今天的第一次充电不会被误记成一次新充电。
//  4. 未知（charging IS NULL）的样本不参与跳变判断，也不会被当成「未充电」。
export const BUCKET_UPSERT_SQL = `
INSERT INTO {table} (
  bucket_start, sample_count, battery_count, battery_sum, battery_min, battery_max,
  charging_known, charging_true, charge_sessions, first_received_at, last_received_at
)
SELECT
  ?,
  COUNT(*),
  COUNT(battery_percent),
  COALESCE(SUM(battery_percent), 0),
  MIN(battery_percent),
  MAX(battery_percent),
  COUNT(charging),
  COALESCE(SUM(charging), 0),
  (
    SELECT COUNT(*) FROM history_raw r
    WHERE r.received_at >= ? AND r.received_at < ? AND r.charging = 1
      AND COALESCE((
        SELECT r2.charging FROM history_raw r2
        WHERE r2.received_at < r.received_at AND r2.charging IS NOT NULL
        ORDER BY r2.received_at DESC LIMIT 1
      ), 0) = 0
  ),
  MIN(received_at),
  MAX(received_at)
FROM history_raw
WHERE received_at >= ? AND received_at < ?
ON CONFLICT(bucket_start) DO UPDATE SET
  sample_count = excluded.sample_count,
  battery_count = excluded.battery_count,
  battery_sum = excluded.battery_sum,
  battery_min = excluded.battery_min,
  battery_max = excluded.battery_max,
  charging_known = excluded.charging_known,
  charging_true = excluded.charging_true,
  charge_sessions = excluded.charge_sessions,
  first_received_at = excluded.first_received_at,
  last_received_at = excluded.last_received_at
`;