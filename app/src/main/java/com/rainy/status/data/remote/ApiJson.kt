package com.rainy.status.data.remote

import kotlinx.serialization.json.Json

/**
 * 线上 JSON 配置的**唯一来源**。
 *
 * 之所以单独抽出来：单测过去各自 `Json { ... }` 自建一份配置，测试全绿但和真机跑的
 * 配置不是同一个对象——[encodeDefaults] 这类开关一漂移，测试就变成「自娱自乐」。
 * 现在 DI（`AppModule.provideJson`）与单测都调 [create]，改一处两边同时生效。
 *
 * 三个开关各自解决一个真实故障：
 * - [Json.ignoreUnknownKeys]：云端将来加字段时，旧 App 必须能继续解析响应，
 *   否则一次服务端升级会让所有旧版本同时「上报失败」。
 * - [Json.coerceInputValues]：显式 `null` 落到带默认值的非空属性上时取默认值而不是抛异常。
 * - [Json.encodeDefaults]：让请求体**始终带上 `schemaVersion`**（它有默认值，不开这个开关
 *   会被省略），与 `docs/api.md` 的请求示例保持一致；同时用户关掉的字段以显式 `null`
 *   上线，而不是「整个键消失」——云端对两者判定相同，但显式 `null` 在抓包时能一眼看出
 *   是「这一项被关了」而不是「这一项忘了发」。
 */
object ApiJson {

    fun create(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
}
