# ── 加密 / 数据库 ─────────────────────────────────────────────
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# ── Compose ───────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── Kotlin 协程 ───────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── 本项目：反射调用的实体类（Room/Autofill 解析） ────────────
-keep class com.yg.vaultbook.data.entity.** { *; }

# ── 不让行号信息泄露内部结构（保留行号便于定位崩溃） ──────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 移除 release 中的所有日志调用 ─────────────────────────────
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}
