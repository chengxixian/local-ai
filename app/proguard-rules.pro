# MNNKit ProGuard / R8 规则

# JNI 层：native 方法由 C++ 按方法名绑定，绝不能被混淆或移除。
#
# ⚠️ 这条规则**不够**，它只在「类名也被保留」时才保留方法名。
# 踩过的坑：`initNative` 曾被挪进 `companion object` 并加 `@JvmStatic`，
# 而下面只 `-keep` 了外层类 —— R8 于是把 `MnnLlmSession$Companion` 改名成 `kl0`、
# 把 `initNative` 改名成 `load`，JNI 立刻报 "No implementation found"。
# 所以补一条**无条件**规则：任何 native 方法名都不许动，不管它在哪个类里。
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class * {
    native <methods>;
}

# 两个 JNI 门面类（符号名 Java_com_mnnkit_app_* 必须与类名严格对应）
-keep class com.mnnkit.app.llm.MnnLlmSession { *; }
# 内部类也要保（companion / lambda 所在类），否则同名的 native 会被一起改名
-keep class com.mnnkit.app.llm.MnnLlmSession$* { *; }
-keep class com.mnnkit.app.llm.TokenCallback { *; }
-keep class com.mnnkit.app.convert.NativeConverter { *; }
-keep class com.mnnkit.app.convert.NativeConverter$* { *; }

# sherpa-mnn 的 JNI 绑定（符号名 Java_com_k2fsa_sherpa_mnn_*）
-keep class com.k2fsa.sherpa.mnn.** { *; }

# 反射用到的类（模型目录缓存的反序列化）
-keep class com.mnnkit.core.model.** { *; }
-keep class com.mnnkit.core.chat.** { *; }
-keep class com.mnnkit.core.memory.** { *; }
-keep class com.mnnkit.core.speech.** { *; }
-keep class com.mnnkit.core.image.** { *; }

# Kotlin 协程
-dontwarn kotlinx.coroutines.**

# Compose / miuix 在 R8 下已知的安全告警
-dontwarn androidx.compose.**
-dontwarn top.yukonga.miuix.**

# 保留行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
