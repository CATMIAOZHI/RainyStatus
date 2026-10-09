package com.rainy.status.data.local

import com.rainy.status.domain.history.BatterySample
import kotlinx.serialization.json.Json

/**
 * 本地历史的编解码。
 *
 * 刻意**不复用** [com.rainy.status.data.remote.ApiJson]：那份配置是给线上契约用的
 * （`encodeDefaults = true`、字段名即协议字段名），而这里只是「本机上的一段缓存」，
 * 两边的演进理由完全无关。混用会导致「改协议顺手改了本地存储格式」这种事。
 *
 * 为什么容错要这么松：
 * - 解码失败返回空列表，而不是抛异常 —— 这是首页画图的输入，一段坏数据不该让界面崩；
 *   下一次采样会把 blob 整个覆盖写回，自然痊愈（本地数据，丢了也只是少一段曲线）。
 * - `ignoreUnknownKeys`：将来加字段（比如记录温度）后，旧版本 App 仍能读出 t/b/c。
 */
internal object HistoryCodec {

    private val json = Json {
        // 与默认值相同的字段不写：null 电量/充电状态就不会出现在 JSON 里，blob 更小
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    fun encode(samples: List<BatterySample>): String = json.encodeToString(ListSerializer, samples)

    fun decode(raw: String?): List<BatterySample> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer, raw) }.getOrDefault(emptyList())
    }

    private val ListSerializer = kotlinx.serialization.builtins.ListSerializer(BatterySample.serializer())
}