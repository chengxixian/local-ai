package com.mnnkit.app.speech

import android.util.Log
import com.k2fsa.sherpa.mnn.OfflineTts
import com.k2fsa.sherpa.mnn.OfflineTtsConfig
import com.k2fsa.sherpa.mnn.OfflineTtsModelConfig
import com.k2fsa.sherpa.mnn.OfflineTtsVitsModelConfig
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.speech.TtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [TtsEngine] 接口没有暴露合成采样率，播放端需要它来配置 `AudioTrack`。
 * 本模块的引擎额外实现该接口（[com.mnnkit.app.speech.AudioIo.AudioPlayer] 会自动读取）。
 */
interface OutputSampleRateAware {
    /** 合成音频的采样率（Hz）；未知时为 0。 */
    val outputSampleRate: Int
}

/**
 * 基于 sherpa-mnn `OfflineTts` 的语音合成引擎（VITS / bert-vits2 生成器）。
 *
 * ### 关键行为
 *  - **分句合成**：文本先按 `。！？.!?\n` 切句、超长句在软标点处硬切、相邻短句合并
 *    （[SpeechAudio.chunkForTts]），逐句调用 `OfflineTts.generate()` 后拼接，
 *    句间插入 [interSentenceSilenceMs] 静音。避免单次文本过长导致的失败或内存峰值。
 *  - **说话人数**：优先用 config.json 里的字段（[SpeechModels.parseSpeakerCount]），
 *    加载后用 native `OfflineTts.numSpeakers()` 覆盖（以 native 为准）；都没有则 0。
 *  - **容错**：原生库缺失 / 模型不完整 / native 生成失败都抛可读 [SpeechException]；
 *    `load`、`unload` 可反复调用。
 */
class SherpaTtsEngine(
    private val numThreads: Int = 2,
    /** 分句之间插入的静音时长，让听感更自然。 */
    private val interSentenceSilenceMs: Int = 120,
) : TtsEngine, OutputSampleRateAware {

    private val mutex = Mutex()

    private var tts: OfflineTts? = null
    private var files: TtsModelFiles? = null

    @Volatile
    override var loadedModel: ModelItem? = null

    /** 原生库（libsherpa-mnn-jni.so）是否加载成功。 */
    override val isAvailable: Boolean get() = SherpaNative.available

    @Volatile
    override var speakerCount: Int = 0
        private set

    @Volatile
    override var outputSampleRate: Int = 0
        private set

    /** 已发现的模型文件，供 UI/日志展示。 */
    val modelFiles: TtsModelFiles? get() = files

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override suspend fun load(model: ModelItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            SherpaNative.requireAvailable()

            val dir = model.localPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: throw SpeechException("模型「${model.displayName}」还没有下载到本地（localPath 为空）")
            val discovered = SpeechModels.discoverTts(dir).getOrElse { throw it }
            val tokens = discovered.tokens
                ?: throw SpeechException(
                    "语音合成模型缺少词表文件（tokenizer.txt / tokens.txt）：${dir.absolutePath}",
                )

            releaseLocked()
            files = discovered

            // VITS 系列（含 bert-vits2 生成器）：model + tokens，其余字段按目录里实际存在的资源填。
            // 详细的字段语义与"bert-vits2 需要 MNNTTSSDK"这一坑见 docs/impl-speech.md。
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = discovered.generator.absolutePath,
                        tokens = tokens.absolutePath,
                        lexicon = discovered.lexicon?.absolutePath ?: "",
                        dataDir = discovered.dataDir?.absolutePath ?: "",
                        dictDir = discovered.dictDir?.absolutePath ?: "",
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = numThreads,
                    debug = false,
                    provider = "cpu",
                ),
                // 分句由 Kotlin 侧完成，这里固定单句，避免依赖 rule FST
                maxNumSentences = 1,
                silenceScale = 0.2f,
            )

            val engine = nativeGuard("创建语音合成器（${discovered.generator.name}）") {
                OfflineTts(config = config)
            }

            // 官方 Kotlin 封装不检查 native 返回的空指针；为 0 时继续调用会直接段错误
            // （try/catch 抓不住 SIGSEGV），所以先用反射判空。
            if (nativePtrOf(engine) == 0L) {
                throw SpeechException(
                    "语音合成器创建失败（native 配置校验未通过）：${discovered.generator.name}；" +
                        "常见原因是该权重不是 sherpa VITS 结构（bert-vits2 需要 MNNTTSSDK 前端）",
                )
            }
            tts = engine

            // 说话人数：config.json 的猜测值先用，native 值可用时以 native 为准
            var count = discovered.speakerCountHint
            val nativeCount = try {
                nativeGuard("读取说话人数") { engine.numSpeakers() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                Log.w(TAG, "numSpeakers() 失败：${e.message}")
                0
            }
            if (nativeCount > 0) count = nativeCount
            speakerCount = count

            val rate = try {
                nativeGuard("读取合成采样率") { engine.sampleRate() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                Log.w(TAG, "sampleRate() 失败：${e.message}")
                0
            }
            if (rate > 0) outputSampleRate = rate

            loadedModel = model
            Log.i(
                TAG,
                "TTS 已加载：${discovered.generator.name}（tokens=${tokens.name}，" +
                    "speakers=$count，sampleRate=$outputSampleRate）",
            )
        }
        // 显式返回 Unit：块内最后一句 Log.i 返回 Int，否则 load() 会被推断为 Int
        Unit
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            releaseLocked()
            loadedModel = null
            Log.i(TAG, "TTS 已卸载")
        }
        Unit
    }

    private fun releaseLocked() {
        tts?.let { engine ->
            runCatching { engine.release() }.onFailure { Log.w(TAG, "释放语音合成器失败", it) }
        }
        tts = null
        files = null
        speakerCount = 0
        outputSampleRate = 0
    }

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    override suspend fun synthesize(
        text: String,
        speakerId: Int,
        speed: Float,
    ): FloatArray = withContext(Dispatchers.Default) {
        val engine = mutex.withLock { tts }
            ?: throw SpeechException("语音合成模型未加载，请先调用 load()")

        val chunks = SpeechAudio.chunkForTts(text)
        if (chunks.isEmpty()) return@withContext FloatArray(0)

        val parts = ArrayList<FloatArray>(chunks.size)
        var rate = 0
        var lastError: SpeechException? = null
        chunks.forEachIndexed { index, chunk ->
            val audio = try {
                nativeGuard("合成第 ${index + 1}/${chunks.size} 句（${chunk.take(12)}…）") {
                    engine.generate(chunk, speakerId, speed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                // 单句失败不整体失败：其余句子照常合成，最后一句都没成功才抛异常
                Log.w(TAG, e.message ?: "单句合成失败", e)
                lastError = e
                null
            }
            if (audio == null) return@forEachIndexed
            if (rate == 0 && audio.sampleRate > 0) rate = audio.sampleRate
            if (audio.samples.isNotEmpty()) parts.add(audio.samples)
        }

        if (rate > 0) outputSampleRate = rate
        if (parts.isEmpty()) {
            throw lastError
                ?: SpeechException("语音合成没有产出音频（文本：${text.take(20)}…）")
        }

        val gap = SpeechAudio.silence(interSentenceSilenceMs, rate.coerceAtLeast(1)).size
        SpeechAudio.concat(parts, gap)
    }

    companion object {
        private const val TAG = "MnnKitSpeech"
    }
}
