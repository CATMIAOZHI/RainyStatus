#!/usr/bin/env python3
# 用 Python 自带的 sqlite3 校验迁移 SQL 与聚合 UPSERT（D1 就是 SQLite）。
#
# 用法：在 cloud/ 目录下执行 `python3 scripts/d1check.py`
# （为什么不用 `wrangler d1 execute --local`：它要起 workerd，某些环境起不来，
#   而那与 SQL 本身无关；语法与语义层面用 sqlite3 验证是有效的。）
#
# 关键约定：SQL 从 `src/lib/bucket-sql.ts` **原文**里抠出来，这里不复制一份——
# 复制就会漂移成两份不一样的实现，测过的那份也未必是线上跑的那份。
import pathlib
import re
import sqlite3

# cloud/ 目录（本文件在 cloud/scripts/ 下）
root = pathlib.Path(__file__).resolve().parent.parent
DAY = 86_400_000
HOUR = 3_600_000
SLOT = 600_000

src = (root / 'src/lib/bucket-sql.ts').read_text()
matched = re.search(r'export const BUCKET_UPSERT_SQL = `(.*?)`;', src, re.S)
assert matched, '没能从 bucket-sql.ts 里取出 BUCKET_UPSERT_SQL'
UPSERT = matched.group(1)
for column in ('bucket_start', 'charge_sessions', 'ON CONFLICT(bucket_start) DO UPDATE'):
    assert column in UPSERT, f'取出来的 SQL 不完整，缺少 {column}'


def new_db():
    db = sqlite3.connect(':memory:')
    db.executescript((root / 'migrations/0001_history.sql').read_text())
    return db


def insert(db, slot, received, pct, charging):
    db.execute(
        'INSERT OR IGNORE INTO history_raw (slot_start, received_at, battery_percent, charging, charge_source)'
        ' VALUES (?, ?, ?, ?, ?)',
        (slot, received, pct, charging, 'ac'),
    )


def upsert(db, table, bucket_start, window_start, window_end):
    # 绑定顺序必须与 bucket-sql.ts 里的 ? 出现顺序一致：
    # 桶起点 → 跳变判定窗口起止 → WHERE 窗口起止
    db.execute(
        UPSERT.replace('{table}', table),
        (bucket_start, window_start, window_end, window_start, window_end),
    )


# ── 1. 同槽去重 ────────────────────────────────────────────────
db = new_db()
insert(db, 0, 60_000, 80, 0)
insert(db, SLOT, SLOT + 60_000, 70, 0)
insert(db, 2 * SLOT, 2 * SLOT + 60_000, 60, 1)
insert(db, 3 * SLOT, 3 * SLOT + 60_000, 95, 1)
insert(db, 3 * SLOT, 3 * SLOT + 90_000, 96, 1)  # 同槽重复：主键应忽略
count = db.execute('SELECT COUNT(*) FROM history_raw').fetchone()[0]
assert count == 4, f'同槽重复没有被忽略：{count}'
print('OK 同槽去重 ->', count, '行')

# ── 2. 聚合结果 + 幂等 ─────────────────────────────────────────
upsert(db, 'history_hourly', 0, 0, HOUR)
upsert(db, 'history_daily', 0, 0, DAY)
for table in ('history_hourly', 'history_daily'):
    upsert(db, table, 0, 0, HOUR if table.endswith('hourly') else DAY)  # 再算一次：结果必须一样
    row = db.execute(
        f'SELECT bucket_start, sample_count, battery_count, battery_sum, battery_min, battery_max,'
        f' charging_known, charging_true, charge_sessions, first_received_at, last_received_at FROM {table}'
    ).fetchone()
    expected = (0, 4, 4, 305, 60, 95, 4, 2, 1, 60_000, 3 * SLOT + 60_000)
    assert row == expected, f'{table} 聚合结果不符：{row} != {expected}'
    print(f'OK {table} 幂等重算 ->', row)

# ── 3. 跨桶的充电延续（关键：昨晚插上电不该在今天算成新的一次）──────
db = new_db()
insert(db, 0, 60_000, 80, 0)
insert(db, 1 * SLOT, 1 * SLOT + 60_000, 70, 1)  # 拔着 → 插上：今天第 1 次
insert(db, 2 * SLOT, 2 * SLOT + 60_000, 60, 1)  # 一直在充：延续
insert(db, 3 * SLOT, 3 * SLOT + 60_000, 55, None)  # 未知：既不参与判断，也不算「未充电」
insert(db, 6 * SLOT, 6 * SLOT + 60_000, 50, 1)  # 上一小时就在充：跨桶延续，不算新的一次
insert(db, 7 * SLOT, 7 * SLOT + 60_000, 45, 0)
insert(db, 8 * SLOT, 8 * SLOT + 60_000, 95, 1)  # 拔了又插：第 2 次
insert(db, 9 * SLOT, 9 * SLOT + 60_000, 96, None)

# 一小时 = 6 个 10 分钟槽，所以第二个小时桶的窗口是 [6*SLOT, 6*SLOT + HOUR)
upsert(db, 'history_hourly', 6 * SLOT, 6 * SLOT, 6 * SLOT + HOUR)
h1 = db.execute(
    'SELECT sample_count, battery_count, battery_sum, battery_min, battery_max,'
    ' charging_known, charging_true, charge_sessions FROM history_hourly'
).fetchone()
assert h1 == (4, 4, 286, 45, 96, 3, 2, 1), (
    f'第二个小时桶不符：{h1}\n原始行：{db.execute("SELECT slot_start, received_at, battery_percent, charging FROM history_raw ORDER BY received_at").fetchall()}'
)
print('OK 跨小时充电延续 ->', h1)

upsert(db, 'history_daily', 0, 0, DAY)
day = db.execute(
    'SELECT sample_count, battery_count, battery_sum, battery_min, battery_max,'
    ' charging_known, charging_true, charge_sessions FROM history_daily'
).fetchone()
assert day == (8, 8, 551, 45, 96, 6, 4, 2), f'日桶不符：{day}'
print('OK 日桶充电次数为 2（跨小时不重复计）->', day)

# ── 4. 导出的「窗口前最后一条已知状态」（readChargingStateBefore 的 SQL）─────
db = new_db()
insert(db, 0, 60_000, 80, 1)
insert(db, SLOT, SLOT + 60_000, 70, None)  # 未知：不能算状态
insert(db, 2 * SLOT, 2 * SLOT + 60_000, 60, 0)
last = db.execute(
    'SELECT charging FROM history_raw WHERE received_at < ? AND charging IS NOT NULL'
    ' ORDER BY received_at DESC LIMIT 1',
    (100 * DAY,),
).fetchone()
assert last == (0,), f'窗口前最后一条已知状态取错：{last}'

# 关键：中间那条 charging 为 NULL 的样本必须被跳过，取到更早的已知状态
skipped = db.execute(
    'SELECT charging FROM history_raw WHERE received_at < ? AND charging IS NOT NULL'
    ' ORDER BY received_at DESC LIMIT 1',
    (2 * SLOT + 60_000,),
).fetchone()
assert skipped == (1,), f'NULL 样本没有被跳过：{skipped}'

none_before = db.execute(
    'SELECT charging FROM history_raw WHERE received_at < ? AND charging IS NOT NULL'
    ' ORDER BY received_at DESC LIMIT 1',
    (0,),
).fetchone()
assert none_before is None, '窗口之前没有任何样本时必须返回 null（不能当「未充电」）'
print('OK 窗口前最后已知充电状态（跳过未知）->', last, skipped)

# ── 5. 导出表按主键覆盖写 ──────────────────────────────────────
db = new_db()
db.execute('INSERT INTO history_exports (range_key, generated_at, payload) VALUES (?, ?, ?)', ('24h', 123, '{"points":[]}'))
db.execute(
    'INSERT INTO history_exports (range_key, generated_at, payload) VALUES (?, ?, ?)'
    ' ON CONFLICT(range_key) DO UPDATE SET generated_at = excluded.generated_at, payload = excluded.payload',
    ('24h', 456, '{"points":[1]}'),
)
assert db.execute('SELECT COUNT(*), MAX(generated_at) FROM history_exports').fetchone() == (1, 456)
print('OK history_exports 覆盖写 ->', db.execute('SELECT range_key, generated_at FROM history_exports').fetchall())

# ── 6. 租约：先插入成功，未过期时抢不到，过期后能抢到 ────────────────
db = new_db()
db.execute('INSERT INTO history_tasks (task, leased_at) VALUES (?, ?)', ('history-maintenance', 1000))
assert db.execute('UPDATE history_tasks SET leased_at = 2000 WHERE task = ? AND leased_at < ?', ('history-maintenance', 500)).rowcount == 0
assert db.execute('UPDATE history_tasks SET leased_at = 2000 WHERE task = ? AND leased_at < ?', ('history-maintenance', 1500)).rowcount == 1
print('OK 租约抢占有条件生效')

print('ALL SQL CHECKS PASSED')