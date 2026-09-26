package com.mnnkit.app.speech

import android.util.Log

/**
 * `libsherpa-mnn-jni.so` 的加载守卫。
 *
 * 原生库是构建产物（`app/src/main/jniLibs/arm64-v8a/`），缺失时 App 必须
 * **降级运行而不是崩溃**：`System.loadLibrary` 抛的是 `UnsatisfiedLinkError`（Error，
 * 不是 Exception），而且 sherpa 的 Kotlin 类把它放在 companion `init` 里，
 * 所以任何 `OfflineRecognizer(...)` / `OfflineTts(...)` 的首次触碰都可能直接抛 Error。
 *
 * 因此：
 *  - 这里先探测一次并缓存结果；
 *  - 所有 native 调用都经过 [nativeGuard]，把 Error 统一翻译成可读的 [SpeechException]。
 */
internal object SherpaNative {

    private const val TAG = "MnnKitSpeech"

    const val LIBRARY = "sherpa-mnn-jni"

    /** 加载失败原因；[available] 为 true 时为 null。 */
    @Volatile
    var loadError: String? = null
        private set

    /** 原生库是否可用。只探测一次。 */
    val available: Boolean by lazy {
        try {
            System.loadLibrary(LIBRARY)
            true
        } catch (t: Throwable) {
            loadError = describe(t)
            Log.e(TAG, "加载 lib$LIBRARY.so 失败：$loadError", t)
            false
        }
    }

    /** 把各种 Throwable 翻译成一句可读的中文原因。 */
    fun describe(t: Throwable): String = when (t) {
        is UnsatisfiedLinkError ->
            "原生库 lib$LIBRARY.so 未加载（需要 arm64-v8a 的 jniLibs，且 libMNN.so/libc++_shared.so 同目录）：${t.message}"

        is NoClassDefFoundError, is ExceptionInInitializerError ->
            "sherpa-mnn JNI 类初始化失败（通常是原生库缺失或 ABI 不匹配）：${t.message}"

        is OutOfMemoryError ->
            "内存不足（模型过大或已加载多个模型）：${t.message}"

        else -> "${t::class.java.simpleName}: ${t.message ?: "未知错误"}"
    }

    /** 原生库不可用时抛出统一异常，供调用方提前返回可读错误。 */
    fun requireAvailable() {
        if (!available) {
            throw SpeechException("原生语音库不可用：${loadError ?: "lib$LIBRARY.so 加载失败"}")
        }
    }
}

/**
 * 读取 sherpa Kotlin 封装（`OfflineRecognizer` / `OfflineTts` …）里的 `private var ptr: Long`。
 *
 * 为什么需要：官方封装的 `init { ptr = newFromFile(config) }` **不检查 native 返回值**，
 * 配置校验失败时 ptr == 0，此后任何 `createStream(0)` / `generate(0, …)` 都会触发
 * native 空指针解引用 → SIGSEGV，Kotlin 侧 try/catch 抓不住（整个进程崩溃）。
 * 因此在调用任何 native 方法前先反射读出 ptr 判空。
 *
 * @return 0 表示创建失败；-1 表示读不到（反射被裁剪等），调用方按"未知"处理。
 */
internal fun nativePtrOf(instance: Any): Long = try {
    val field = instance.javaClass.getDeclaredField("ptr")
    field.isAccessible = true
    field.getLong(instance)
} catch (t: Throwable) {
    Log.w("MnnKitSpeech", "无法反射读取 ptr 字段，跳过空指针预检：${t.message}")
    -1L
}

/**
 * 包裹一次可能失败的原生调用：把 `UnsatisfiedLinkError` / `RuntimeException`
 * 等统统翻译成带上下文的 [SpeechException]。
 *
 * 协程取消异常必须原样抛出，否则会破坏结构化并发。
 */
internal inline fun <T> nativeGuard(what: String, block: () -> T): T = try {
    block()
} catch (e: SpeechException) {
    throw e
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (t: Throwable) {
    Log.e("MnnKitSpeech", "$what 失败", t)
    throw SpeechException("$what 失败：${SherpaNative.describe(t)}", t)
}
