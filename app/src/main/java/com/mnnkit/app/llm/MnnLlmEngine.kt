package com.mnnkit.app.llm

import android.util.Log
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.chat.GenerationMetrics
import com.mnnkit.core.chat.LlmEngine
import com.mnnkit.core.chat.TokenStreamCallback
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Serialized adapter for MNN's synchronous native session. Native callbacks return true to STOP. */
class MnnLlmEngine(
    libraryLoader: (() -> Boolean)? = MnnLlmSession.lazyLibraryLoader(),
    private val backendType: String? = null,
    private val enableThinking: Boolean = true,
    private val backendProvider: (() -> String?)? = null,
    private val thinkingProvider: (() -> Boolean)? = null,
) : LlmEngine {
    private val session = MnnLlmSession(libraryLoader)
    private val nativeMutex = Mutex()
    override val isAvailable: Boolean get() = session.isAvailable
    @Volatile override var loadedModel: ModelItem? = null
    @Volatile override var lastGenerationMetrics: GenerationMetrics? = null
        private set
    /** Requested backend of the LOADED session, not proof of per-op execution. */
    @Volatile var loadedBackend: String? = null
        private set
    @Volatile var backendReport: String = ""
        private set
    val isGenerating: Boolean get() = session.isGenerating
    val isLoaded: Boolean get() = session.isLoaded
    private fun effectiveThinking() = thinkingProvider?.invoke() ?: enableThinking

    override suspend fun load(model: ModelItem, config: GenerationConfig): Unit = withContext(Dispatchers.IO) {
        check(isAvailable) { "MNN 原生库未加载，无法加载模型。" }
        val localPath = model.localPath ?: error("模型没有本地路径")
        val configFile = session.resolveConfigFile(localPath)
        check(configFile.isFile) { "模型缺少 config.json：${configFile.absolutePath}" }
        // Do not rewrite downloaded model files or restore stale backups behind the user's back.
        val modelConfigText = configFile.readText()
        val requestedBackend = backendProvider?.invoke() ?: backendType
        val runtimeConfig = MnnLlmSession.buildRuntimeConfigJson(
            modelConfigText, config, keepHistory = true, mmapDir = "",
            backendType = requestedBackend, enableThinking = effectiveThinking(),
        )
        nativeMutex.withLock {
            session.release()
            loadedModel = null
            loadedBackend = null
            backendReport = ""
            lastGenerationMetrics = null
            try {
                session.load(configFile.absolutePath, runtimeConfig)
                loadedBackend = requestedBackend
                    ?: JsonParser.parseOrNull(session.dumpConfig())?.str("backend_type")
                loadedModel = model
                backendReport = session.backendDiagnostics()
                Log.i(TAG, "model loaded; requestedBackend=$loadedBackend; backendEvidence=$backendReport")
            } catch (error: Throwable) {
                session.release()
                loadedModel = null
                loadedBackend = null
                throw IllegalStateException("MNN 模型加载失败：${error.message}", error)
            }
        }
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        nativeMutex.withLock {
            session.release()
            loadedModel = null
            loadedBackend = null
            backendReport = ""
            lastGenerationMetrics = null
        }
    }

    override fun stream(messages: List<ChatMessage>, config: GenerationConfig): Flow<String> = channelFlow {
        val producer = this
        withContext(Dispatchers.IO) {
            require(messages.isNotEmpty()) { "messages must not be empty" }
            val generationContext = currentCoroutineContext()
            nativeMutex.withLock {
                check(session.isLoaded) { "模型尚未加载" }
                lastGenerationMetrics = null
                session.reset()
                session.setMaxNewTokens(config.maxNewTokens)
                session.applyGenerationConfig(config, effectiveThinking())
                // The model's own native template renders roles once. A debug concatenation
                // is not the tokenized prompt and must not trigger a universal ChatML fallback.
                val history = MnnLlmSession.toNativeHistory(messages)
                try {
                    session.submit(prompt = "", keepHistory = false, history = history) { token ->
                        TokenStreamCallback.shouldStop(token, !generationContext.isActive, producer)
                    }
                } finally {
                    val stats = session.lastStats()
                    lastGenerationMetrics = GenerationMetrics.fromNativeDecode(
                        stats["decode_len"] ?: 0L, stats["decode_time"] ?: 0L,
                    )
                    backendReport = session.backendDiagnostics()
                    // Numeric diagnostics only: never log prompts, attachments or generated text.
                    Log.i(TAG, "generation finished; decode_len=${stats["decode_len"]}; " +
                        "decode_us=${stats["decode_time"]}; prompt_len=${stats["prompt_len"]}; " +
                        "tokens_per_second=${lastGenerationMetrics?.tokensPerSecond}; backendEvidence=$backendReport")
                }
            }
        }
    }.buffer(Channel.UNLIMITED)

    override suspend fun embed(text: String): FloatArray? = null
    override suspend fun resetContext() = withContext(Dispatchers.IO) {
        nativeMutex.withLock { if (session.isLoaded) session.reset() }
    }
    fun requestCancel() { session.requestCancel() }
    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        nativeMutex.withLock { if (session.isLoaded) session.clearHistory() }
    }
    fun debugInfo(): String = session.debugInfo()
    fun dumpConfig(): String = session.dumpConfig()
    companion object { const val TAG = "MnnLlmEngine" }
}
