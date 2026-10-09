package com.rainy.status.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatterySampleDao {

    /**
     * 最近的 [windowMs] 毫秒里的样本，按时间升序。
     *
     * 窗口下界是**相对最新一条**算的（子查询每次执行都会重新求值），不是「订阅时刻往前推」：
     * Room 的 Flow 参数在订阅那一刻就固定了，而常驻服务的进程可能几周不重启，
     * 用固定下界的话 7 天窗口的左端迟早会掉到查询范围之外，曲线会悄悄少一天。
     */
    @Query(
        "SELECT * FROM battery_samples " +
            "WHERE t >= (SELECT MAX(t) FROM battery_samples) - :windowMs " +
            "ORDER BY t ASC"
    )
    fun observeRecent(windowMs: Long): Flow<List<BatterySampleEntity>>

    /** 已记录条数（设置页显示「已记录 N 个点」） */
    @Query("SELECT COUNT(*) FROM battery_samples")
    fun observeCount(): Flow<Int>

    /** 最新一条；用于「值没变就不重复记」的判断 */
    @Query("SELECT * FROM battery_samples ORDER BY t DESC LIMIT 1")
    suspend fun latest(): BatterySampleEntity?

    /** 主键冲突就忽略：同一时刻重复写入不会变成两条 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BatterySampleEntity): Long

    /** 迁移专用：一次插一批。逐条插 2000 行会走 2000 次事务，第一次采样要卡好几秒 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<BatterySampleEntity>)

    /** 清空全部本机历史（**只有用户手动点「清空」才会走到这里**，没有自动清理） */
    @Query("DELETE FROM battery_samples")
    suspend fun clear(): Int
}