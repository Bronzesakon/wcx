# ─── Xposed Module Entry Points ────────────────────────────────────
# These MUST be kept with original names — Xposed framework loads them
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep class com.Johnny.wcx.entry.** { *; }
-keep class com.Johnny.wcx.application.** { *; }

# ─── Feature / Hook / Data Classes ─────────────────────────────────
# 不再整体 -keep：功能类由 KSP 生成的 FeaturesProvider 直接引用注册
# （ALL_HOOK_ITEMS 列表逐项引用每个 Feature 类，见 app/build/generated/ksp/**/
# FeaturesProvider.kt），功能名取自 @Feature 注解的 name 字段而非类名，
# R8 只会重命名、不会误删被引用的类；删除整体 keep 后 R8 还可额外摇掉
# 包内未被任何代码路径引用的辅助类，进一步减小体积。
# （下方 @com.Johnny.wcx.annotations.* 成员 keep 保留，供编译期处理器使用）

# Keep annotation-annotated members (used by compile-time processors)
-keepclassmembers,allowobfuscation class * {
    @com.Johnny.wcx.annotations.* *;
}

# ─── Kotlin ────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# kotlin-reflect 在运行时反射路径中使用；暂时保留其内建表，避免反射/脚本功能回归。
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ─── Serialization ──────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
# 不再全量 keep kotlinx.serialization 运行库。运行时入口由普通可达性分析保留，
# 应用内生成的 serializer 仍由下方规则保留，允许 R8 移除未使用的格式/实现代码。
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.Johnny.wcx.**$$serializer { *; }
-keepclassmembers class com.Johnny.wcx.** {
    *** Companion;
}
-keepclasseswithmembers class com.Johnny.wcx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Room ───────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ─── Compose ────────────────────────────────────────────────────────
# 不要全量 keep androidx.compose：Compose 依赖自带 consumer rules，
# 全量 keep 会阻止 R8 删除未使用的 UI/运行时实现，显著增大 release APK。
-dontwarn androidx.compose.**
# Compose 编译器生成的单例字段由运行时按名查找，必须保住字段本身
-keepclassmembers,allowobfuscation class **.ComposableSingletons* {
    public static <fields>;
}

# ─── Third-party (dontwarn only, allow R8 optimization) ──────────────
-dontwarn com.alibaba.fastjson2.**
-dontwarn io.netty.**
-dontwarn com.google.protobuf.**
-dontwarn com.tencent.wcdb.**
-dontwarn org.slf4j.**
-dontwarn org.mozilla.javascript.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn com.materialkolor.**
-dontwarn miuix.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# ─── WeChat Stubs ───────────────────────────────────────────────────
# 微信 API stub 为 compileOnly；不应打入模块 APK，也无需对其设置 keep 规则。

# ─── Obfuscation Enhancements ───────────────────────────────────────
-repackageclasses
-allowaccessmodification
-overloadaggressively
-useuniqueclassmembernames

# ─── Attributes ─────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable

# ─── Keep resource names used in code ───────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# R$plurals 类本身必须保留：R8 会因 R$plurals 成员被常量内联而整体移除该类，
# 但 Kotlin 对 pluralStringResource(R.plurals.*) 的编译引用仍指向它。
-keep class **.R$plurals { *; }