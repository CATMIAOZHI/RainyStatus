# 代码混淆规则（当前 release 未开启 minify，先占位保留给未来使用）
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}