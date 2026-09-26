package com.mnnkit.app.llm

import android.util.Log
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.chat.LlmEngine
import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于 MNN 官方 JNI 封装（[MnnLlmSession] + `libmnnkitbridge.so`）的 [LlmEngine] 实现。
 *
 * ## 设计要点
 *
 * 1. **同步 → 异步**：`initNative` / `submitNative` 都是同步阻塞的原生调用，
 *    因此 [load] / [stream] 全部跑在 [Dispatchers.IO] 上。协程取消 → 原生
 *    token 回调返回 `true` → C++ 解码主循环立即退出（协作式取消）。
 * 2. **完整历史**：每轮生成前先 `resetNative`（清 KV + 原生内部历史），再把
 *    `system + messages` 整体交给原生层重放。这样 Kotlin 侧的
 *    `List<ChatMessage>` 是上下文的唯一真相来源，原生层不自行累积状态 ——
 *    不会出现「编辑历史后原生 KV 与 UI 不一致」的问题。
 * 3. **降级**：[isAvailable] 为 `false` 时 [stream] 抛 [IllegalStateException]、
 *    [embed] 返回 null，不崩溃。
 * 4. **串行化**：原生 `mls::LlmSession` 不是线程安全的，用一个 [Mutex] 保证
 *    同一时刻只有一次生成。（官方用 `synchronized(this)` + `wait/notify`，
 *    其中 `wait()` 依赖别处 `notifyAll()`、而 `generate()` 结束时并不 notify，
 *    有死锁风险；这里换成语义等价的协程原语。）
 *
 * @param libraryLoader 原生库加载器；默认 [MnnLlmSession.defaultLibraryLoader]。
 *   传入一个返回 `null` 的加载器即可在无原生库的环境下测试降级路径。
 */
class MnnLlmEngine(
    libraryLoader: (() -> Boolean)? = MnnLlmSession.lazyLibraryLoader(),
    /**
     * 推理后端。从构造注入而不是加到 [LlmEngine.load] 的参数上 ——
     * 后者要改 core 的接口，牵动所有实现与调用点，而这里本来就是 `by lazy` 构造的，
     * 用户改设置后重建引擎即可。
     *
     * 取值见 [com.mnnkit.app.data.InferenceBackend]。
     */
    private val backendType: String? = null,
    /**
     * 是否开启思考模式。对本地模型通过 [ModelConfigPatcher] 落到模型目录的配置文件；
     * 对 API 则映射为 	hinking.reasoning_effort。
     */
    private val enableThinking: Boolean = false,
) : LlmEngine {

    private val session = MnnLlmSession(libraryLoader)

    /** 原生 `mls::LlmSession` 非线程安全：所有原生调用串行化。 */
    private val nativeMutex = Mutex()

    /** 当前生效的生成配置（[load] 时记录，[stream] 时更新）。 */
    @Volatile
    private var generationConfig: GenerationConfig = GenerationConfig()

    override val isAvailable: Boolean get() = session.isAvailable

    override var loadedModel: ModelItem? = null

    /** 当前是否正在生成（乐观提示位，供 UI 展示「停止」按钮）。 */
    val isGenerating: Boolean get() = session.isGenerating

    /** 模型是否已加载到原生侧。 */
    val isLoaded: Boolean get() = session.isLoaded

    /**
     * 加载模型。`suspend` + [Dispatchers.IO]，内部的原生调用是阻塞的
     * （模型加载耗时可达数十秒）。
     *
     * @throws IllegalStateException 原生库不可用 / 模型目录或 config.json 缺失 /
     *   原生加载失败
     */
    override suspend fun load(model: ModelItem, config: GenerationConfig) {
        withContext(Dispatchers.IO) {
            check(isAvailable) {
                "MNN 原生库（lib${MnnLlmSession.LIBRARY_NAME}.so）未加载，无法加载模型。"
            }
            val localPath = model.localPath
                ?: throw IllegalStateException("模型 ${model.id} 没有本地路径（localPath == null）")
            val modelDir = File(localPath)
            check(modelDir.isDirectory) { "模型目录不存在：$localPath" }

            // 先修正模型自带的配置。必须在 resolveConfigFile 之前做 ——
            // 这一步会改 `config.json` / `llm_config.json` 的内容，
            // 而后面读的就是这两个文件。原因见 [ModelConfigPatcher] 的注释：
            // MNN 是浅合并，通过 extra config 传 `enable_thinking` **不会生效**。
            runCatching { ModelConfigPatcher.patch(modelDir) }
                .onSuccess { r ->
                    if (r.patched.isNotEmpty()) {
                        Log.i(TAG, "模型配置已修正：${r.patched.joinToString(", ")}")
                    }
                    r.note?.let { Log.w(TAG, it) }
                }
                .onFailure { Log.w(TAG, "模型配置修正失败（继续尝试加载）", it) }

            val configFile = session.resolveConfigFile(localPath)
            check(configFile.isFile) { "模型缺少 config.json：${configFile.absolutePath}" }

            val modelConfigText = runCatching { configFile.readText() }
                .getOrElse { throw IllegalStateException("读取 ${configFile.absolutePath} 失败", it) }
            val runtimeConfigJson = MnnLlmSession.buildRuntimeConfigJson(
                modelConfigText = modelConfigText,
                config = config,
                keepHistory = true,
                mmapDir = "",
                // 用户在设置里选的后端；null 时沿用模型 config.json 自带的值。
                backendType = backendType,
            )

            nativeMutex.withLock {
                // 先释放旧会话，保证原生侧不残留旧模型的 KV cache。
                session.release()
                try {
                    session.load(configFile.absolutePath, runtimeConfigJson)
                } catch (error: Throwable) {
                    session.release()
                    throw IllegalStateException(
                        "MNN 模型加载失败：${configFile.absolutePath}（${error.message}）",
                        error,
                    )
                }
                generationConfig = config
                loadedModel = model
                Log.i(TAG, "model loaded: ${model.id} @ $localPath")
                Log.d(TAG, "runtime config: $runtimeConfigJson")
                Log.d(TAG, "effective MNN config: ${session.dumpConfig()}")
            }
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
            nativeMutex.withLock {
                session.release()
                loadedModel = null
                Log.i(TAG, "model unloaded")
            }
        }
    }

    /**
     * 流式生成。返回增量文本 [Flow]；流正常结束即生成结束。
     *
     * 取消收集该 Flow 的协程会立刻让原生解码循环停止（token 回调返回 `true`），
     * 已生成的文本不会丢失 —— 原生层返回的完整回复文本会写进日志，并可通过
     * [debugInfo] 取回。取消本身**不**向调用方抛异常。
     */
    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = channelFlow {
        // 整个上游都跑在 IO 上：原生 submit 是同步阻塞调用。
        withContext(Dispatchers.IO) {
            check(isAvailable) {
                "MNN 原生库（lib${MnnLlmSession.LIBRARY_NAME}.so）未加载，无法生成。"
            }
            require(messages.isNotEmpty()) { "messages must not be empty" }
            generationConfig = config

            nativeMutex.withLock {
                check(session.nativeHandle != 0L) { "模型尚未加载（未调用 load()）" }

                // (1) 清 KV cache 与原生内部历史；上下文交给原生按模板重放。
                session.reset()
                // (2) 本轮配置：最大 token 数以传入的 config 为准。
                session.setMaxNewTokens(config.maxNewTokens)

                // ⚠️⚠️ **绝对不要传 history 数组** —— 这是本地模型「只吐 1 个 token」
                // 的真正根因，踩了很久。
                //
                // 原生 `mls::LlmSession` 有两个入口，行为**根本不同**：
                //
                //   `Response(prompt, cb)`            ← 单条字符串
                //       内部把 prompt 追加进 `history_`，再用 **chat template
                //       渲染整段对话**后才喂给模型。**官方走的就是这条。**
                //
                //   `ResponseWithHistory(items, cb)`  ← PromptItem 数组
                //       官方代码 `llm_session.cpp:517`：
                //           llm_->response(temp_history, &output_ostream, "<eop>", 0);
                //       **完全不套模板**，把 role/content 直接交给 tokenizer。
                //
                // 我们此前传了 history 数组 → 命中第二条 → 模型收到的是**没有
                // `<|im_start|>` / `<|im_end|>` 包装的裸文本**，等于一段不成形的输入，
                // 于是第一个 token 就输出 `<eop>`。真机日志可证：
                //   `decode_len=1`、`full_text=3 chars`、输出是残缺的 `Thinking…`。
                //
                // 现在只传最后一条 user 文本 ⇒ 走官方那条带模板的路径。
                // system prompt 由原生从 `config_["system_prompt"]` 自己插入
                // （`llm_session.cpp:182-183`），多轮上下文由原生 `history_` 维护
                // （`keep_history = true`）。
                val nativeHistory: List<Pair<String, String>> = emptyList()

                // 取消信号：收集端一被取消（下游提前 return / 抛异常），
                // 下一次 token 回调就会看到并请求原生层停止生成。
                val cancelSignal = Channel<Unit>(Channel.CONFLATED)
                val watcher = currentCoroutineContext().job.invokeOnCompletion { cause ->
                    if (cause is CancellationException) {
                        cancelSignal.trySend(Unit)
                    }
                }
                val producer = this@channelFlow
                try {
                    val fullText = session.submit(
                        prompt = messages.last().content,
                        keepHistory = true,
                        history = nativeHistory,
                    ) { token ->
                        if (cancelSignal.tryReceive().isSuccess) {
                            return@submit true
                        }
                        if (token == null) {
                            // <eop>：本轮结束哨兵，不对上层发射（对齐官方 onProgress(null)）。
                            return@submit false
                        }
                        // 原生回调可能比收集端快；下游用 buffer(UNLIMITED) 兜住，
                        // 因此这里 trySend 基本不会失败。
                        producer.trySend(token).isSuccess
                    }
                    Log.d(
                        TAG,
                        "generation finished: ${fullText.length} chars, stats=${session.lastStats()}",
                    )
                } finally {
                    watcher.dispose()
                    cancelSignal.close()
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    /** MVP 阶段不做 embedding：MNN 的 Embedding 需要单独的 embedding 模型文件。 */
    override suspend fun embed(text: String): FloatArray? = null

    override suspend fun resetContext() {
        withContext(Dispatchers.IO) {
            if (isAvailable && session.nativeHandle != 0L) {
                nativeMutex.withLock { session.reset() }
            }
        }
    }

    /** 请求停止当前生成。对下一次原生 token 回调立即生效。 */
    fun requestCancel() {
        session.requestCancel()
    }

    /** 清空历史（保留 system 轮）。 */
    suspend fun clearHistory() {
        withContext(Dispatchers.IO) {
            if (isAvailable && session.nativeHandle != 0L) {
                nativeMutex.withLock { session.clearHistory() }
            }
        }
    }

    /** 最近一轮的 prompt / 完整回复（调试）。 */
    fun debugInfo(): String = session.debugInfo()

    /** `Llm::dump_config()`：排查后端/精度是否生效。 */
    fun dumpConfig(): String = session.dumpConfig()

    companion object {
        const val TAG = "MnnLlmEngine"
    }
}
