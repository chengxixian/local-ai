package com.mnnkit.app.speech

import android.util.Log
import com.k2fsa.sherpa.mnn.FeatureConfig
import com.k2fsa.sherpa.mnn.OfflineRecognizer
import com.k2fsa.sherpa.mnn.OfflineRecognizerConfig
import com.k2fsa.sherpa.mnn.OfflineModelConfig
import com.k2fsa.sherpa.mnn.OfflineTransducerModelConfig
import com.k2fsa.sherpa.mnn.OnlineModelConfig
import com.k2fsa.sherpa.mnn.OnlineRecognizer
import com.k2fsa.sherpa.mnn.OnlineRecognizerConfig
import com.k2fsa.sherpa.mnn.OnlineStream
import com.k2fsa.sherpa.mnn.OnlineTransducerModelConfig
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.speech.RecognitionSegment
import com.mnnkit.core.speech.SttEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 基于 sherpa-mnn 的语音识别引擎（离线 + 流式双后端）。
 *
 * ### 两条独立可用的路径
 *  - [transcribe]：一段完整音频 → 文字（"录音转文字"独立功能）；
 *  - [stream]：边喂音频边出中间结果（语音输入 / 与 LLM 组成语音闭环）。
 *
 * ### 为什么有两个 native 后端
 * `OfflineRecognizer`（离线 transducer）与 `OnlineRecognizer`（流式 transducer）
 * 的权重不通用：流式 encoder 带有 `conv_cache / attn_cache / processed_lens` 等
 * **状态输入**，离线识别器不会提供这些输入，因此把流式权重喂给离线识别器会
 * 得到空结果或直接失败。本引擎在 [load] 时按模型目录名/文件名判定
 * （见 [SpeechModels.looksStreaming]），并对外暴露 [backend] 说明实际用的是哪一个；
 * 若判定的后端创建失败，会自动尝试另一个。
 *
 * ### 采样率
 * sherpa 的声学前端固定 16kHz（[FeatureConfig.sampleRate]）。传入的 PCM 若不是
 * 16kHz，这里先用 [SpeechAudio.resampleLinear] 线性插值重采样到 16kHz 再送入 native，
 * 保证行为与模型训练时一致。
 *
 * ### 容错
 * 原生库缺失时 [isAvailable] 为 false，所有方法抛出带中文原因的 [SpeechException]，
 * 绝不崩溃。[load] / [unload] 可反复调用，每次都先释放旧权重。
 */
class SherpaSttEngine(
    private val numThreads: Int = 2,
    /** 离线后端结果为空时是否再试一次流式后端（用于文件名判据失效的情况）。 */
    private val retryOnlineWhenOfflineBlank: Boolean = true,
) : SttEngine {

    private val mutex = Mutex()

    private var offline: OfflineRecognizer? = null
    private var online: OnlineRecognizer? = null
    private var files: SttModelFiles? = null

    @Volatile
    override var loadedModel: ModelItem? = null

    /** 原生库（libsherpa-mnn-jni.so）是否加载成功。 */
    override val isAvailable: Boolean get() = SherpaNative.available

    /** 当前实际使用的后端："offline" / "online"；未加载为 null。 */
    @Volatile
    var backend: String? = null
        private set

    /** 已发现的模型文件，供 UI/日志展示。 */
    val modelFiles: SttModelFiles? get() = files

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override suspend fun load(model: ModelItem) = withContext(Dispatchers.IO) {
        mutex.withLock {
            SherpaNative.requireAvailable()

            val dir = model.localPath?.takeIf { it.isNotBlank() }?.let { File(it) }
                ?: throw SpeechException("模型「${model.displayName}」还没有下载到本地（localPath 为空）")
            val discovered = SpeechModels.discoverStt(dir, model.repoId).getOrElse { throw it }

            // 先释放旧权重，避免两份模型同时驻留内存
            releaseLocked()
            files = discovered

            val preferStreaming = discovered.looksStreaming
            val primaryKind = if (preferStreaming) "online" else "offline"
            val primaryError = tryCreate(primaryKind, discovered)
            if (primaryError == null) {
                backend = primaryKind
            } else {
                // 兜底：换另一个后端（文件名判据失效时不至于完全不可用）
                val fallbackKind = if (preferStreaming) "offline" else "online"
                val fallbackError = tryCreate(fallbackKind, discovered)
                if (fallbackError == null) {
                    backend = fallbackKind
                    Log.w(TAG, "$primaryKind 后端创建失败，已回退到 $fallbackKind", primaryError)
                } else {
                    releaseLocked()
                    throw SpeechException(
                        "语音识别模型加载失败：$primaryKind — ${primaryError.message}；" +
                            "$fallbackKind — ${fallbackError.message}",
                        fallbackError,
                    )
                }
            }

            loadedModel = model
            Log.i(TAG, "STT 已加载：${discovered.encoder.name}（后端 $backend，线程 $numThreads）")
        }
        // withContext 的返回类型由块内最后一个表达式决定；Log.i 返回 Int，
        // 会让整个 load() 被推断为 Int 而无法覆盖返回 Unit 的接口方法。
        Unit
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            releaseLocked()
            loadedModel = null
            Log.i(TAG, "STT 已卸载")
        }
        Unit
    }

    /** 尝试创建某个后端；成功返回 null，失败返回可读异常（不抛出）。 */
    private fun tryCreate(kind: String, f: SttModelFiles): SpeechException? = try {
        if (kind == "online") {
            online = createOnline(f)
        } else {
            offline = createOffline(f)
        }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        val wrapped = if (t is SpeechException) t else SpeechException(SherpaNative.describe(t), t)
        Log.w(TAG, "创建 $kind 后端失败", wrapped)
        wrapped
    }

    private fun createOffline(f: SttModelFiles): OfflineRecognizer {
        if (!f.isTransducer) {
            throw SpeechException(
                "离线识别需要 transducer 三件套（encoder/decoder/joiner），" +
                    "当前只找到 ${f.encoder.name}（缺少 decoder/joiner）",
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechAudio.SHERPA_SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = f.encoder.absolutePath,
                    decoder = f.decoder!!.absolutePath,
                    joiner = f.joiner!!.absolutePath,
                ),
                tokens = f.tokens.absolutePath,
                // 官方 c-api 用 model_type 选择网络结构；zipformer transducer 即 "transducer"
                modelType = "transducer",
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
        )
        return nativeGuard("创建离线识别器（${f.encoder.name}）") {
            val rec = OfflineRecognizer(config = config)
            // 官方封装不检查 native 空指针；为 0 时后续 createStream(0) 会段错误
            if (nativePtrOf(rec) == 0L) {
                throw SpeechException(
                    "离线识别器创建失败（native 配置校验未通过）：${f.encoder.name}；" +
                        "该权重可能是流式模型，请改用流式后端",
                )
            }
            rec
        }
    }

    private fun createOnline(f: SttModelFiles): OnlineRecognizer {
        if (!f.isTransducer) {
            throw SpeechException(
                "流式识别需要 transducer 三件套（encoder/decoder/joiner），" +
                    "当前只找到 ${f.encoder.name}（缺少 decoder/joiner）",
            )
        }
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechAudio.SHERPA_SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = f.encoder.absolutePath,
                    decoder = f.decoder!!.absolutePath,
                    joiner = f.joiner!!.absolutePath,
                ),
                tokens = f.tokens.absolutePath,
                // 官方示例：streaming-zipformer-bilingual-zh-en-2023-02-20 → modelType = "zipformer"
                modelType = "zipformer",
                numThreads = numThreads,
                debug = false,
                provider = "cpu",
            ),
            // 端点检测：静音 2.4s / 1.4s（含非静音）/ 单段最长 20s 后断句
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return nativeGuard("创建流式识别器（${f.encoder.name}）") { OnlineRecognizer(config = config) }
    }

    private fun releaseLocked() {
        offline?.let { rec -> runCatching { rec.release() }.onFailure { Log.w(TAG, "释放离线识别器失败", it) } }
        online?.let { rec -> runCatching { rec.release() }.onFailure { Log.w(TAG, "释放流式识别器失败", it) } }
        offline = null
        online = null
        files = null
        backend = null
    }

    // ------------------------------------------------------------------
    // 独立路径：整段音频 → 文字
    // ------------------------------------------------------------------

    override suspend fun transcribe(pcm: FloatArray, sampleRate: Int): String =
        withContext(Dispatchers.Default) {
            if (pcm.isEmpty()) return@withContext ""
            val (off, onl) = mutex.withLock { offline to online }
            if (off == null && onl == null) {
                throw SpeechException("语音识别模型未加载，请先调用 load()")
            }

            val samples = SpeechAudio.resampleLinear(pcm, sampleRate, SpeechAudio.SHERPA_SAMPLE_RATE)

            // 判定为流式模型时只加载了 online，直接用流式后端整段解码
            if (off == null && onl != null) return@withContext decodeWithOnline(onl, samples)

            try {
                val text = decodeWithOffline(off!!, samples)
                if (text.isNotEmpty() || !retryOnlineWhenOfflineBlank || onl == null) {
                    return@withContext text
                }
                // 离线后端给出空结果：可能是流式权重被误判，用流式后端再试一次
                Log.w(TAG, "离线识别结果为空，改用流式后端重试")
                decodeWithOnline(onl, samples)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                if (onl == null) throw e
                Log.w(TAG, "离线识别失败，改用流式后端重试：${e.message}")
                decodeWithOnline(onl, samples)
            }
        }

    private fun decodeWithOffline(rec: OfflineRecognizer, samples: FloatArray): String =
        nativeGuard("离线识别") {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SpeechAudio.SHERPA_SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                runCatching { stream.release() }
            }
        }

    /** 用流式识别器整段解码（内部按 0.2s 分块喂入，等价于官方 c-api-examples 的做法）。 */
    private fun decodeWithOnline(rec: OnlineRecognizer, samples: FloatArray): String =
        nativeGuard("流式识别") {
            val stream = rec.createStream()
            try {
                var pos = 0
                while (pos < samples.size) {
                    val n = minOf(STREAM_CHUNK_SAMPLES, samples.size - pos)
                    stream.acceptWaveform(samples.copyOfRange(pos, pos + n), SpeechAudio.SHERPA_SAMPLE_RATE)
                    pos += n
                    drain(rec, stream)
                }
                stream.inputFinished()
                drain(rec, stream)
                rec.getResult(stream).text.trim()
            } finally {
                runCatching { stream.release() }
            }
        }

    private fun drain(rec: OnlineRecognizer, stream: OnlineStream) {
        var guard = 0
        while (rec.isReady(stream)) {
            rec.decode(stream)
            if (++guard > MAX_DECODE_ITERATIONS) {
                Log.w(TAG, "解码循环超过 $MAX_DECODE_ITERATIONS 次，提前退出（模型状态异常？）")
                return
            }
        }
    }

    // ------------------------------------------------------------------
    // 流式路径：边说边出字
    // ------------------------------------------------------------------

    override fun stream(pcmChunks: Flow<FloatArray>, sampleRate: Int): Flow<RecognitionSegment> = flow {
        val rec = mutex.withLock { online }
            ?: throw SpeechException(
                if (files == null) {
                    "语音识别模型未加载，请先调用 load()"
                } else {
                    "当前加载的是离线模型（${files?.encoder?.name}），不支持流式识别；" +
                        "请改用 transcribe()，或下载流式（streaming）模型"
                },
            )

        val stream = nativeGuard("创建流式输入缓冲") { rec.createStream() }
        try {
            var totalSamples = 0L
            var segmentStartSamples = 0L
            var lastPartial = ""

            pcmChunks.collect { chunk ->
                if (chunk.isEmpty()) return@collect
                val samples = SpeechAudio.resampleLinear(
                    chunk,
                    sampleRate,
                    SpeechAudio.SHERPA_SAMPLE_RATE,
                )
                nativeGuard("送入音频") {
                    stream.acceptWaveform(samples, SpeechAudio.SHERPA_SAMPLE_RATE)
                }
                totalSamples += samples.size
                drain(rec, stream)

                val text = nativeGuard("读取识别结果") { rec.getResult(stream).text.trim() }
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    emit(
                        RecognitionSegment(
                            text = text,
                            startMs = msOf(segmentStartSamples),
                            endMs = msOf(totalSamples),
                            isFinal = false,
                        ),
                    )
                }

                if (nativeGuard("端点检测") { rec.isEndpoint(stream) }) {
                    if (lastPartial.isNotEmpty()) {
                        emit(
                            RecognitionSegment(
                                text = lastPartial,
                                startMs = msOf(segmentStartSamples),
                                endMs = msOf(totalSamples),
                                isFinal = true,
                            ),
                        )
                    }
                    nativeGuard("重置流状态") { rec.reset(stream) }
                    lastPartial = ""
                    segmentStartSamples = totalSamples
                }
            }

            // 上游结束：冲刷尾部不足一帧的音频，给出最终结果
            nativeGuard("结束输入") { stream.inputFinished() }
            drain(rec, stream)
            val tail = nativeGuard("读取最终结果") { rec.getResult(stream).text.trim() }
            if (tail.isNotEmpty()) {
                emit(
                    RecognitionSegment(
                        text = tail,
                        startMs = msOf(segmentStartSamples),
                        endMs = msOf(totalSamples),
                        isFinal = true,
                    ),
                )
            }
        } finally {
            runCatching { stream.release() }.onFailure { Log.w(TAG, "释放流缓冲失败", it) }
        }
    }.flowOn(Dispatchers.Default)

    private fun msOf(samples: Long): Long =
        samples * 1000L / SpeechAudio.SHERPA_SAMPLE_RATE

    companion object {
        private const val TAG = "MnnKitSpeech"

        /** 喂给流式识别器的分块长度：0.2s @16kHz。 */
        const val STREAM_CHUNK_SAMPLES = 3_200

        /** 单次 drain 的防御上限，避免模型状态异常时死循环。 */
        const val MAX_DECODE_ITERATIONS = 10_000
    }
}
