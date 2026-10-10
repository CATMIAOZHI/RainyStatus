package com.rainy.status.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2：本机新增心情事件表（`mood_events`）。
 *
 * **只建表，不碰 `battery_samples`**：那张表是永久数据，写错一次 ALTER/RENAME 就再也回不来；
 * 纯新增的迁移失败面最小。
 *
 * 为什么手写而不是 `@AutoMigration`：`HistoryDatabase` 是 `exportSchema = false`，
 * `app/build.gradle.kts` 里也没配 `room.schemaLocation`，仓库里根本没有 v1 的 schema JSON，
 * 而 AutoMigration 在**编译期**就要读它。要启用得先打开 schema 导出、再回退到旧提交重新
 * 生成一次 v1 schema —— 比这三行 DDL 麻烦得多。
 *
 * 列必须与 [MoodEventEntity] 严格对齐（类型、NOT NULL、PRIMARY KEY）：Room 打开库时会用
 * 生成的 schema 校验迁移结果，对不上会直接抛
 * `IllegalStateException: Migration didn't properly handle mood_events`。
 * 列顺序无所谓（按名字比对），但 `emoji` 必须可空、`text` 必须 NOT NULL。
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `mood_events` (" +
                "`at` INTEGER NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`emoji` TEXT, " +
                "PRIMARY KEY(`at`))"
        )
    }
}