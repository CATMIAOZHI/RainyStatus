-- ═══════════════════════════════════════════════════════
-- RainyStatus 历史数据（v1.2.0）
--
-- 设计要点（改之前请先读 docs/roadmap.md 第 3 节「踩坑记录」）：
--  1. 心跳仍然只写 1 次 KV；D1 写入是**尽力而为**的附加动作，失败不影响当前状态。
--  2. 聚合不靠定时任务「补算」，而是**由刚写入的样本重算当前小时/当前 UTC 日**。
--     重算是幂等的：同样的原始行算两次结果一样，所以重试、并发、重复上报都无害。
--  3. 重算只在原始行**完整存在**的窗口内进行 —— 也就是「当前」桶。
--     超过原始保留期（7 天）的日汇总一旦冻结就不再重算，避免用残缺的原始行把汇总算小。
--  4. 时间一律用服务端 epoch 毫秒（UTC），客户端时间只作诊断，不参与分桶。
-- ═══════════════════════════════════════════════════════

-- 原始层：每个 10 分钟槽最多 1 个代表样本（主键冲突即忽略）。
-- 为什么按「槽」而不是每条都存：心跳间隔可配到 60 秒，不设槽会让写入量翻 10 倍，
-- 而电量曲线用 10 分钟粒度已经足够。
CREATE TABLE IF NOT EXISTS history_raw (
  slot_start      INTEGER PRIMARY KEY,  -- 10 分钟槽起点（epoch ms UTC）
  received_at     INTEGER NOT NULL,     -- 服务端收到时刻（同一个槽内先到者胜）
  battery_percent INTEGER,              -- 0..100，或 NULL（用户没开电量上报）
  charging        INTEGER,              -- 1 / 0 / NULL
  charge_source   TEXT
);

-- 清理与范围查询都走这个索引
CREATE INDEX IF NOT EXISTS idx_history_raw_received ON history_raw (received_at);

-- 小时层：由当前小时的原始行重算
CREATE TABLE IF NOT EXISTS history_hourly (
  bucket_start      INTEGER PRIMARY KEY, -- 小时起点（epoch ms UTC）
  sample_count      INTEGER NOT NULL,
  battery_count     INTEGER NOT NULL,
  battery_sum       INTEGER NOT NULL,
  battery_min       INTEGER,
  battery_max       INTEGER,
  charging_known    INTEGER NOT NULL,    -- charging 非 NULL 的样本数
  charging_true     INTEGER NOT NULL,    -- 其中充电中的样本数
  charge_sessions   INTEGER NOT NULL,    -- 本桶内「充电开始」的次数
  first_received_at INTEGER,
  last_received_at  INTEGER
);

-- 日层：由当前 UTC 日的原始行重算。
-- 注意：日桶边界是 **UTC 日**，不是访客本地日。长周期图上一天 = 08:00～08:00（UTC+8），
-- 对「每天电量区间」这种统计量影响很小，但不要在文案里声称「本地零点」。
CREATE TABLE IF NOT EXISTS history_daily (
  bucket_start      INTEGER PRIMARY KEY, -- UTC 日起点（epoch ms UTC）
  sample_count      INTEGER NOT NULL,
  battery_count     INTEGER NOT NULL,
  battery_sum       INTEGER NOT NULL,
  battery_min       INTEGER,
  battery_max       INTEGER,
  charging_known    INTEGER NOT NULL,
  charging_true     INTEGER NOT NULL,
  charge_sessions   INTEGER NOT NULL,
  first_received_at INTEGER,
  last_received_at  INTEGER
);

-- 心情事件：只在内容变化时写入（App 端已去重），所以这张表很稀疏。
-- 不设 TTL，靠定时清理；心情比电量更敏感，是否公开由 SHOW_MOOD 控制。
CREATE TABLE IF NOT EXISTS history_mood (
  event_id   INTEGER PRIMARY KEY AUTOINCREMENT,
  updated_at INTEGER NOT NULL,
  text       TEXT NOT NULL,
  emoji      TEXT
);

CREATE INDEX IF NOT EXISTS idx_history_mood_updated ON history_mood (updated_at);

-- 导出层：公开接口只按主键读这里的一行 JSON，**绝不现场扫描历史表**。
-- 这样即使有人拿参数逼最大范围，一次请求的数据库成本也只有 1 行。
CREATE TABLE IF NOT EXISTS history_exports (
  range_key   TEXT PRIMARY KEY,  -- 24h / 7d / 30d / 1y
  generated_at INTEGER NOT NULL,
  payload     TEXT NOT NULL
);

-- 维护任务的租约：防止两次 Cron 重叠生成/清理
CREATE TABLE IF NOT EXISTS history_tasks (
  task       TEXT PRIMARY KEY,
  leased_at  INTEGER NOT NULL,
  note       TEXT
);
