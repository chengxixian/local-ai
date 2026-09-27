package com.mnnkit.app.llm

import android.util.Log
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import java.io.File

/**
 * token 回调。对齐官方 `GenerateProgressListener.onProgress(String?)`：
 *
 *  - `onToken(text != null)`：一个增量片段；
 *  - **`onToken(null)`：本轮生成结束**（C++ 侧收到 `<eop>` 哨兵，对齐官方
 *    `onProgress(null)` 语义）。`<eop>` 字符串本身完全在 C++ 内部消化，
 *    Kotlin 侧永远不会看到它。
 *
 *  返回 `true` 表示请求停止生成（协作式取消）。**回调发生在调用
 *  [MnnLlmSession.submit] 的那个线程上**（JNI 层不起线程），必须快速返回：
 *  不要在这里做 IO / 阻塞操作，否则会拖慢逐 token 解码。
 */
fun interface TokenCallback {
    fun onToken(text: String?): Boolean
}

/**
 * MNN LLM 原生会话的 Kotlin 封装。
 *
 * 复用 MNN 官方 `MnnLlmChat` 的 JNI 实现（见 `app/src/main/cpp/`），仅把
 * 包名/类名改为 `com.mnnkit.app.llm.MnnLlmSession` 并裁剪掉 benchmark / TTS /
 * diffusion 等无关导出。
 *
 * ### 线程模型（与官方完全一致）
 * `initNative` 与 `submitNative` 都是**同步阻塞**的，token 回调发生在同一个
 * 调用线程上。**原生层不起任何线程** —— 后台执行由调用方（例如 `Dispatchers.IO`）
 * 负责。
 *
 * ### 取消
 * 没有 `stopNative()`。停止生成靠 [TokenCallback.onToken] 返回 `true`：C++ 主循环
 * 在下一轮 `generate(1)` 前退出。
 *
 * ### 原生库缺失
 * 用 [loadLibrary] 传入的加载器探测；失败时 [isAvailable] 为 `false`，所有方法
 * 都走优雅降级（抛 [IllegalStateException] 或 no-op），不会崩溃。
 */
class MnnLlmSession internal constructor(
    /** 动态库加载器，便于测试注入；null 视为“未加载”。 */
    private val libraryLoader: (() -> Boolean)?,
) {

    /** 原生句柄；0 表示未加载或已释放。 */
    @Volatile
    var nativeHandle: Long = 0L
        private set

    /** 原生库是否可用。 */
    /**
     * 原生库是否可用。
     *
     * ⚠️ 不能写成 libraryLoader != null —— 那个 lambda 永不为 null，
     * 会让 isAvailable 恒为 true，掩盖「库其实没加载」的事实（详见 [checkAvailable]）。
     * 这里**真的触发一次加载**（进程内只尝试一次，失败会缓存）。
     */
    val isAvailable: Boolean get() = libraryLoader?.invoke() == true

    /** 是否已成功加载模型。 */
    val isLoaded: Boolean get() = nativeHandle != 0L

    /**
     * 是否正在生成。**非线程安全**的提示位：调用方与 [onToken] 在同一线程上，
     * 另开线程读会有竞态，仅供 UI 做「正在生成」的乐观展示。
     */
    @Volatile
    var isGenerating: Boolean = false
        private set

    /** 最近一次生成中途收到取消请求（[TokenCallback.onToken] 返回 true）。 */
    @Volatile
    var cancelRequested: Boolean = false
        private set

    /**
     * 加载模型（**阻塞**，必须在 IO 线程调用）。
     *
     * @param configPath 模型目录下 `config.json` 的完整路径
     * @param runtimeConfigJson 运行时配置，例如
     *   `{"system_prompt":"...","max_new_tokens":512,"is_r1":false,"keep_history":true,"mmap_dir":""}`
     * @return 原生句柄
     * @throws IllegalStateException 原生库不可用，或加载失败
     */
    fun load(configPath: String, runtimeConfigJson: String): Long {
        checkAvailable()
        check(nativeHandle == 0L) { "session already loaded; call release() first" }
        val handle = initNative(configPath, runtimeConfigJson)
        if (handle == 0L) {
            throw IllegalStateException("initNative returned 0 for config: $configPath")
        }
        nativeHandle = handle
        Log.i(TAG, "loaded native session: handle=$handle config=$configPath")
        return handle
    }

    /** 释放模型与 KV cache。可重复调用。 */
    fun release() {
        val handle = nativeHandle
        nativeHandle = 0L
        if (handle != 0L && libraryLoader != null) {
            runCatching { releaseNative(handle) }
                .onFailure { Log.e(TAG, "releaseNative failed", it) }
        }
        isGenerating = false
        cancelRequested = false
    }

    /**
     * 流式生成（**同步阻塞**，必须在 IO 线程调用）。
     *
     * @param history 非空时走「完整会话」路径：`history[0]` 应为
     *   `("system", ...)`，其后按 user/assistant 交替；C++ 侧整体替换内部
     *   history 后再生成。为空时退化为官方的单 prompt 路径。
     * @return 完整回复文本（即使用户中途取消，也返回已生成的部分）
     */
    fun submit(
        prompt: String,
        keepHistory: Boolean,
        history: List<Pair<String, String>> = emptyList(),
        callback: TokenCallback,
    ): String {
        checkAvailable()
        val handle = nativeHandle
        if (handle == 0L) {
            throw IllegalStateException("session not loaded")
        }
        val roles = if (history.isEmpty()) null else Array(history.size) { history[it].first }
        val contents = if (history.isEmpty()) null else Array(history.size) { history[it].second }
        isGenerating = true
        cancelRequested = false
        try {
            val result = submitNative(
                handle,
                prompt,
                keepHistory,
                roles,
                contents,
                TokenCallback { text ->
                    // text == null 表示 <eop>（本轮结束），不视为取消。
                    if (text != null && cancelRequested) {
                        true
                    } else {
                        callback.onToken(text)
                    }
                },
            )
            return result ?: ""
        } finally {
            isGenerating = false
        }
    }

    /** 请求停止生成。线程安全；对下一次 [onToken] 立即生效。 */
    fun requestCancel() {
        cancelRequested = true
    }

    /** Runtime template context; never changes downloaded model files. */
    fun applyGenerationConfig(config: GenerationConfig, enableThinking: Boolean) {
        checkAvailable()
        check(nativeHandle != 0L) { "session not loaded" }
        val json = Json.Obj(mapOf(
            "jinja" to Json.Obj(mapOf("context" to Json.Obj(mapOf(
                "enable_thinking" to Json.Bool(enableThinking),
            )))),
        )).stringify()
        setConfigNative(nativeHandle, json)
    }

    /** Runtime evidence, not a copy of requested backend_type. */
    fun backendDiagnostics(): String {
        if (nativeHandle == 0L) return "{}"
        return backendDiagnosticsNative(nativeHandle)
    }
    private external fun backendDiagnosticsNative(handle: Long): String

    /** 清空 KV cache 与对话历史（保留 system 轮）。 */
    fun reset() {
        val handle = nativeHandle
        if (handle != 0L && libraryLoader != null) {
            resetNative(handle)
        }
    }

    /** 清空对话历史（`numToKeep = 1`，即只留 system 轮）。 */
    fun clearHistory() {
        val handle = nativeHandle
        if (handle != 0L && libraryLoader != null) {
            clearHistoryNative(handle)
        }
    }

    /** 调整最大新生成 token 数。 */
    fun setMaxNewTokens(maxNewTokens: Int) {
        val handle = nativeHandle
        if (handle != 0L && libraryLoader != null) {
            updateMaxNewTokensNative(handle, maxNewTokens)
        }
    }

    /** 运行时替换 system prompt。 */
    fun setSystemPrompt(systemPrompt: String) {
        val handle = nativeHandle
        if (handle != 0L && libraryLoader != null) {
            updateSystemPromptNative(handle, systemPrompt)
        }
    }

    fun systemPrompt(): String? {
        val handle = nativeHandle
        if (handle == 0L || libraryLoader == null) return null
        return runCatching { getSystemPromptNative(handle) }.getOrNull()
    }

    /** 最近一轮的 prompt / 完整回复（调试用）。 */
    fun debugInfo(): String {
        val handle = nativeHandle
        if (handle == 0L || libraryLoader == null) return ""
        return runCatching { getDebugInfoNative(handle) }.getOrDefault("")
    }

    /** `Llm::dump_config()` 的结果 —— 排查 backend 是否生效时非常有用。 */
    fun dumpConfig(): String {
        val handle = nativeHandle
        if (handle == 0L || libraryLoader == null) return "{}"
        return runCatching { dumpConfigNative(handle) }.getOrDefault("{}")
    }

    /**
     * 最近一轮生成的统计：`prompt_len` / `decode_len` / `prefill_time` /
     * `decode_time`（后两者单位为微秒）。未生成过时返回空 Map。
     */
    fun lastStats(): Map<String, Long> {
        val handle = nativeHandle
        if (handle == 0L || libraryLoader == null) return emptyMap()
        val raw = runCatching { submitStatsNative(handle) }.getOrNull() ?: return emptyMap()
        return raw.mapNotNull { (key, value) ->
            (value as? Number)?.let { key to it.toLong() }
        }.toMap()
    }

    /**
     * 调任何 native 方法前的守卫。
     *
     * ⚠️ **必须真的调用一次加载器**，不能只判断它是否为 null。
     *
     * 踩过的坑：这里原来写的是 `if (libraryLoader == null) throw ...`。
     * 而 `MnnLlmEngine` 构造时传的是 `MnnLlmSession.lazyLibraryLoader()` ——
     * 一个**永不为 null 的 lambda**。于是：
     *
     *   * `isAvailable`（`libraryLoader != null`）恒为 **true**；
     *   * `System.loadLibrary` **从来没被调用过**（那个 lambda 没人执行）；
     *   * 走到 `initNative(...)` 时库根本没加载，JNI 报
     *     「No implementation found ... - is the library loaded?」。
     *
     * 这条错误信息字面上就是答案（"library loaded?"），
     * 但因为 `isAvailable` 显示 true，很容易被误导去查符号/混淆/签名 ——
     * 我为此绕了很久。守卫必须**真的把库载进来**。
     */
    private fun checkAvailable() {
        val loader = libraryLoader
            ?: throw IllegalStateException(
                "libmnnkitbridge.so 未加载（可能缺少 arm64-v8a 原生库）；" +
                    "请调用 MnnLlmEngine.isAvailable 判断后再使用。",
            )
        if (!loader()) {
            throw IllegalStateException(
                "libmnnkitbridge.so 加载失败；请确认 APK 内含 arm64-v8a 原生库。",
            )
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // native 声明
    //
    // ⚠️ 必须声明在**类体**里（即实例方法，JNI 第二参数是 jobject），
    // 与 MNN 官方 `MnnLlmChat` 的 `LlmSession.kt` 一致 —— 官方同样写在类体里，
    // 对应 C++ 侧的 `Java_..._initNative(JNIEnv* env, jobject thiz, ...)`。
    //
    // 曾经把它们挪进 companion object 并加 `@JvmStatic`（理由是「JNI 签名第二参数
    // 该是 jclass」）。**那是错的**，而且引入了一个更隐蔽的问题：
    //  proguard 规则只 `-keep` 了 `MnnLlmSession`，**没有覆盖 `$Companion`**，
    //  于是 R8 把 `MnnLlmSession$Companion` 改名成 `kl0`、
    //  把 `initNative` 改名成 `load`（见 mapping.txt），JNI 自然找不到符号。
    //
    // 结论：照官方写就对了。别为了「签名好看」去动 native 方法的声明位置 ——
    // 在类体里天然被 `-keep class com.mnnkit.app.llm.MnnLlmSession { *; }` 覆盖。
    // ────────────────────────────────────────────────────────────────────────

    private external fun initNative(configPath: String, runtimeConfigJson: String): Long

    private external fun submitNative(
        handle: Long,
        prompt: String,
        keepHistory: Boolean,
        roles: Array<String>?,
        contents: Array<String>?,
        callback: TokenCallback,
    ): String?

    private external fun submitStatsNative(handle: Long): HashMap<String, Any>?

    private external fun resetNative(handle: Long)

    private external fun releaseNative(handle: Long)

    private external fun getDebugInfoNative(handle: Long): String

    private external fun dumpConfigNative(handle: Long): String

    private external fun updateMaxNewTokensNative(handle: Long, maxNewTokens: Int)

    private external fun updateSystemPromptNative(handle: Long, systemPrompt: String)

    private external fun getSystemPromptNative(handle: Long): String?

    private external fun clearHistoryNative(handle: Long)

    /** 把一段 JSON 合并进运行时配置（对应 MNN `Llm::set_config`）。 */
    private external fun setConfigNative(handle: Long, configJson: String)

    companion object {
        const val TAG = "MnnLlmSession"



        /** 原生库名，与 CMakeLists 的 `project()` 一致。 */
        const val LIBRARY_NAME = "mnnkitbridge"

        /** `mnn_llm_jni.cpp` 所在 JNI 层的导出前缀。 */
        const val JNI_CLASS = "com.mnnkit.app.llm.MnnLlmSession"

        /** 动态库是否已成功加载过（进程内只尝试一次）。 */
        @Volatile
        private var libraryLoaded = false

        /** 动态库是否不可用（进程内只判定一次，避免反复 loadLibrary）。 */
        @Volatile
        private var libraryUnavailable = false

        /** 映射表是否已打过日志（只打一次，避免刷屏）。 */
        @Volatile
        private var mapsLogged = false

        /**
         * 尝试加载动态库，返回 `true` 表示可用。
         *
         * **惰性**：默认的 [MnnLlmSession] 构造器传进来的是这个函数的方法引用，
         * 不会在构造/类加载阶段就调 `System.loadLibrary`，因此即使原生库缺失也
         * 不会抛 [UnsatisfiedLinkError] 到调用方。
         */
        /** 从 /proc/self/maps 里找出 libmnnkitbridge 的实际文件路径。 */
        private fun mappedFiles(): String = runCatching {
            val hits = java.io.File("/proc/self/maps").readLines()
                .filter { it.contains(LIBRARY_NAME) }
                .map { it.substringAfterLast(' ').trim() }
                .distinct()
            if (hits.isEmpty()) "(未映射！)" else hits.joinToString(" | ")
        }.getOrElse { "读取失败: ${it.message}" }

        @JvmStatic
        fun tryLoadLibrary(): Boolean {
            // 无条件打一次映射表 —— 必须在早返回**之前**。
            // 上一版把它放在 System.loadLibrary 成功之后，而库在这之前就已加载过，
            // 走的是 `if (libraryLoaded) return true` 分支，日志永远不打印。
            if (!mapsLogged) {
                mapsLogged = true
                android.util.Log.i(TAG, "mnnkitbridge 映射 = ${mappedFiles()}")
            }
            if (libraryLoaded) return true
            if (libraryUnavailable) return false
            synchronized(this) {
                if (libraryLoaded) return true
                if (libraryUnavailable) return false
                return try {
                    System.loadLibrary(LIBRARY_NAME)
                    libraryLoaded = true
                    android.util.Log.i(TAG, "loaded lib$LIBRARY_NAME.so; 映射 = ${mappedFiles()}")
                    true
                } catch (error: UnsatisfiedLinkError) {
                    libraryUnavailable = true
                    Log.w(TAG, "lib$LIBRARY_NAME.so unavailable: ${error.message}")
                    false
                } catch (error: SecurityException) {
                    libraryUnavailable = true
                    Log.w(TAG, "lib$LIBRARY_NAME.so not allowed: ${error.message}")
                    false
                }
            }
        }

        /** 供 [MnnLlmEngine] 的默认参数使用：**惰性**加载器，永不抛异常。 */
        fun lazyLibraryLoader(): () -> Boolean = { tryLoadLibrary() }

        /** 立即尝试加载，失败返回 null（调用方据此降级）。 */
        fun libraryLoaderOrNull(): (() -> Boolean)? =
            if (tryLoadLibrary()) lazyLibraryLoader() else null

        /** [libraryLoaderOrNull] 的别名，语义更直观。 */
        fun defaultLibraryLoader(): (() -> Boolean)? = libraryLoaderOrNull()

        /**
         * 从模型目录里的 `config.json` 构造传给 `initNative` 的运行时配置。
         *
         * 保守策略：只透传 MNN `set_config` 确实认识的字段，避免未知键引发解析问题。
         * `system_prompt` / `max_new_tokens` 以 [config] 为准。
         */
        fun buildRuntimeConfigJson(
            modelConfigText: String?,
            config: GenerationConfig,
            keepHistory: Boolean = true,
            mmapDir: String = "",
            /**
             * 推理后端；`null` 表示沿用模型 `config.json` 里自带的 `backend_type`
             * （多数官方 MNN 模型自带 `opencl`）。
             *
             * 取值必须是 MNN `backend_type_convert()` 认识的字符串，
             * 见 [com.mnnkit.app.data.InferenceBackend]。
             */
            backendType: String? = null,
            /** Explicit context: false disables thinking where supported by the model. */
            enableThinking: Boolean = false,
        ): String {
            val passthroughKeys = setOf(
                "llm_model", "llm_weight", "backend_type", "thread_num", "precision",
                "memory", "dynamic_option", "reuse_kv", "use_mmap", "tmp_path",
                "quant_qkv", "sampler_type", "temperature", "topP", "topK", "min_p",
                "penalty", "max_all_tokens", "use_template", "jinja",
                "assistant_prompt_template", "user_prompt_template",
                "system_prompt_template", "is_r1", "skip_special_token",
                "attention_type", "kvcache_mmap", "chunk", "chunk_limits",
                "prefix_cache", "speculative_type", "draft_model", "draft_weight",
                "draft_length", "eagle_path", "ocr_usage", "video_fps",
                "video_max_frames", "max_new_tokens", "system_prompt",
            )
            val fields = LinkedHashMap<String, Json>()
            val parsed = JsonParser.parseOrNull(modelConfigText)
            if (parsed is Json.Obj) {
                for ((key, value) in parsed.fields) {
                    if (key in passthroughKeys) {
                        fields[key] = value
                    }
                }
            }
            // system_prompt / max_new_tokens 由本工程显式控制，覆盖模型自带的同名值；
            // is_r1 若模型 config.json 里有就原样透传（R1 模型需要它来切模板）。
            //
            // backend_type 也在这里覆盖：多数官方 MNN 模型的 config.json 自带
            // `"backend_type": "opencl"`，若只靠透传，用户在设置里选 CPU 就**不生效** ——
            // 那会让「切回 CPU 排查问题」这条最基本的诊断手段失效。
            backendType?.let { fields["backend_type"] = Json.Str(it) }
            fields["system_prompt"] = Json.Str(config.systemPrompt)
            fields["max_new_tokens"] = Json.Num(config.maxNewTokens.toDouble())

            val jinjaFields = (fields["jinja"] as? Json.Obj)?.fields?.toMutableMap() ?: mutableMapOf()
            val ctxFields = (jinjaFields["context"] as? Json.Obj)?.fields?.toMutableMap() ?: mutableMapOf()
            ctxFields["enable_thinking"] = Json.Bool(enableThinking)
            jinjaFields["context"] = Json.Obj(ctxFields)
            fields["jinja"] = Json.Obj(jinjaFields)
            fields["keep_history"] = Json.Bool(keepHistory)
            fields["mmap_dir"] = Json.Str(mmapDir)
            // 采样参数只在模型 config 未提供时兜底，避免覆盖模型自带的最优设置。
            fields.putIfAbsent("sampler_type", Json.Str(config.samplerType))
            fields.putIfAbsent("temperature", Json.Num(config.temperature))
            fields.putIfAbsent("topP", Json.Num(config.topP))
            fields.putIfAbsent("topK", Json.Num(config.topK.toDouble()))
            fields.putIfAbsent("min_p", Json.Num(config.minP))
            fields.putIfAbsent("penalty", Json.Num(config.repetitionPenalty))
            return Json.Obj(fields).stringify()
        }

        /** 把 [ChatMessage] 列表映射成 JNI 需要的 `(role, content)` 对。 */
        fun toNativeHistory(messages: List<ChatMessage>): List<Pair<String, String>> =
            messages.map { it.role.wire to it.content }
    }

    /** 模型目录里的 `config.json`。 */
    fun resolveConfigFile(modelLocalPath: String): File = File(modelLocalPath, "config.json")
}
