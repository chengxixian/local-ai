package com.mnnkit.app.speech

import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.chat.LlmEngine
import com.mnnkit.core.speech.SttEngine
import com.mnnkit.core.speech.TtsEngine
import com.mnnkit.core.speech.VoiceTurnEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * 语音对话回合协调器：把 **STT → LLM → TTS** 串成一次"语音对话回合"，
 * 同时把 **只做 STT**、**只做 TTS**（以及文字输入 + 语音回复）做成独立可调用的路径。
 *
 * 事件顺序（[turn]）：
 * ```
 * PartialTranscript* → FinalTranscript → ReplyToken* → ReplyComplete
 * ```
 * 任一步失败发一次 [VoiceTurnEvent.Failed] 并结束流。**唯一不算失败的降级**：
 * TTS 不可用/合成失败时仍然发 `ReplyComplete(text, null, 0)`，让 UI 至少能显示文字。
 *
 * 三个引擎都可以为 null（例如只做文字朗读时不需要 STT/LLM），
 * 也可以在加载模型之前就构造协调器——运行时会给出可读的 Failed 事件。
 */
class VoiceTurnCoordinator(
    private val stt: SttEngine? = null,
    private val tts: TtsEngine? = null,
    private val llm: LlmEngine? = null,
    private val config: GenerationConfig = GenerationConfig(),
) {

    // ==================================================================
    // 路径 1：完整语音闭环（STT → LLM → TTS）
    // ==================================================================

    /**
     * 一次完整语音回合。
     *
     * @param audio 录音 PCM 块流（任意采样率，[sampleRate] 说明其采样率）
     * @param history 已有的对话历史（不含本轮用户消息，内部会追加）
     */
    fun turn(
        audio: Flow<FloatArray>,
        sampleRate: Int = SpeechAudio.SHERPA_SAMPLE_RATE,
        history: List<ChatMessage> = emptyList(),
        speakerId: Int = 0,
        speed: Float = 1.0f,
    ): Flow<VoiceTurnEvent> = flow {
        val sttEngine = readyStt()
        if (sttEngine == null) {
            emit(VoiceTurnEvent.Failed(sttProblem()))
            return@flow
        }

        val finals = ArrayList<String>()
        var lastPartial = ""
        try {
            sttEngine.stream(audio, sampleRate).collect { segment ->
                if (segment.isFinal) {
                    if (segment.text.isNotBlank()) finals.add(segment.text)
                    emit(VoiceTurnEvent.FinalTranscript(segment.text))
                } else {
                    lastPartial = segment.text
                    emit(VoiceTurnEvent.PartialTranscript(segment.text))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(VoiceTurnEvent.Failed("语音识别失败：${reason(e)}"))
            return@flow
        }

        // 引擎可能因为上游不是"端点结束"而没有产出 final 段，这里用最后一次 partial 兜底
        val userText = (finals.ifEmpty { listOf(lastPartial) })
            .joinToString(separator = "")
            .trim()
        if (userText.isEmpty()) {
            emit(VoiceTurnEvent.Failed("没有识别到有效的语音内容"))
            return@flow
        }

        generateAndSpeak(userText, history, speakerId, speed)
    }

    // ==================================================================
    // 路径 2：只做 STT（独立使用，不接 LLM）
    // ==================================================================

    /** 流式转写：只做语音识别，事件为 PartialTranscript/FinalTranscript（或 Failed）。 */
    fun transcribeOnly(
        audio: Flow<FloatArray>,
        sampleRate: Int = SpeechAudio.SHERPA_SAMPLE_RATE,
    ): Flow<VoiceTurnEvent> = flow {
        val sttEngine = readyStt()
        if (sttEngine == null) {
            emit(VoiceTurnEvent.Failed(sttProblem()))
            return@flow
        }

        var gotFinal = false
        var lastPartial = ""
        try {
            sttEngine.stream(audio, sampleRate).collect { segment ->
                if (segment.isFinal) {
                    gotFinal = true
                    emit(VoiceTurnEvent.FinalTranscript(segment.text))
                } else {
                    lastPartial = segment.text
                    emit(VoiceTurnEvent.PartialTranscript(segment.text))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(VoiceTurnEvent.Failed("语音识别失败：${reason(e)}"))
            return@flow
        }

        if (!gotFinal && lastPartial.isBlank()) {
            emit(VoiceTurnEvent.Failed("没有识别到有效的语音内容"))
        }
    }

    /**
     * 整段音频转文字（"录音文件转文字"独立功能，不经过流式接口）。
     * 失败原因在 [Result] 的异常里，可直接展示。
     */
    suspend fun transcribeOnce(
        pcm: FloatArray,
        sampleRate: Int = SpeechAudio.SHERPA_SAMPLE_RATE,
    ): Result<String> {
        val sttEngine = readyStt() ?: return Result.failure(SpeechException(sttProblem()))
        return try {
            Result.success(sttEngine.transcribe(pcm, sampleRate))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(SpeechException("语音识别失败：${reason(e)}", e))
        }
    }

    // ==================================================================
    // 路径 3：只做 TTS（文字朗读，不接 STT / LLM）
    // ==================================================================

    /** 朗读一段文字：只做语音合成，事件为 ReplyComplete(text, pcm, sampleRate)（或 Failed）。 */
    fun speakOnly(
        text: String,
        speakerId: Int = 0,
        speed: Float = 1.0f,
    ): Flow<VoiceTurnEvent> = flow {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            emit(VoiceTurnEvent.Failed("要朗读的文本为空"))
            return@flow
        }
        val ttsEngine = readyTts()
        if (ttsEngine == null) {
            emit(VoiceTurnEvent.Failed(ttsProblem()))
            return@flow
        }
        try {
            val pcm = ttsEngine.synthesize(trimmed, speakerId, speed)
            emit(VoiceTurnEvent.ReplyComplete(trimmed, pcm.takeIf { it.isNotEmpty() }, ttsRate(ttsEngine, pcm)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(VoiceTurnEvent.Failed("语音合成失败：${reason(e)}"))
        }
    }

    /** 文字 → 音频（独立功能；UI 拿到 PCM 后交给 [AudioIo.AudioPlayer] 播放）。 */
    suspend fun synthesizeOnce(
        text: String,
        speakerId: Int = 0,
        speed: Float = 1.0f,
    ): Result<FloatArray> {
        val ttsEngine = readyTts() ?: return Result.failure(SpeechException(ttsProblem()))
        return try {
            Result.success(ttsEngine.synthesize(text, speakerId, speed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(SpeechException("语音合成失败：${reason(e)}", e))
        }
    }

    // ==================================================================
    // 路径 4：文字输入 + 语音回复（不接 STT）
    // ==================================================================

    /** 直接用文字提问，走 LLM → TTS。事件：ReplyToken* → ReplyComplete（或 Failed）。 */
    fun replyOnly(
        userText: String,
        history: List<ChatMessage> = emptyList(),
        speakerId: Int = 0,
        speed: Float = 1.0f,
    ): Flow<VoiceTurnEvent> = flow {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            emit(VoiceTurnEvent.Failed("输入文本为空"))
            return@flow
        }
        generateAndSpeak(trimmed, history, speakerId, speed)
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private suspend fun FlowCollector<VoiceTurnEvent>.generateAndSpeak(
        userText: String,
        history: List<ChatMessage>,
        speakerId: Int,
        speed: Float,
    ) {
        val llmEngine = llm
        if (llmEngine == null || !llmEngine.isAvailable) {
            emit(
                VoiceTurnEvent.Failed(
                    "语言模型不可用（原生库未加载），本轮只完成了语音识别：$userText",
                ),
            )
            return
        }
        if (llmEngine.loadedModel == null) {
            emit(VoiceTurnEvent.Failed("语言模型未加载，请先在模型页加载 LLM"))
            return
        }

        val messages = history + ChatMessage(role = ChatMessage.Role.USER, content = userText)
        val full = StringBuilder()
        try {
            llmEngine.stream(messages, config).collect { token ->
                if (token.isNotEmpty()) {
                    full.append(token)
                    emit(VoiceTurnEvent.ReplyToken(token))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(VoiceTurnEvent.Failed("模型生成失败：${reason(e)}"))
            return
        }

        val reply = full.toString().trim()
        if (reply.isEmpty()) {
            emit(VoiceTurnEvent.Failed("模型没有返回任何内容"))
            return
        }

        // ---- TTS：不可用/失败都降级为"只有文字"，而不是整轮失败 ----
        val ttsEngine = readyTts()
        if (ttsEngine == null) {
            emit(VoiceTurnEvent.ReplyComplete(reply, null, 0))
            return
        }
        try {
            val pcm = ttsEngine.synthesize(reply, speakerId, speed)
            if (pcm.isEmpty()) {
                emit(VoiceTurnEvent.ReplyComplete(reply, null, 0))
            } else {
                emit(VoiceTurnEvent.ReplyComplete(reply, pcm, ttsRate(ttsEngine, pcm)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(VoiceTurnEvent.ReplyComplete(reply, null, 0))
        }
    }

    private fun readyStt(): SttEngine? = stt?.takeIf { it.isAvailable && it.loadedModel != null }

    private fun readyTts(): TtsEngine? = tts?.takeIf { it.isAvailable && it.loadedModel != null }

    private fun sttProblem(): String = when {
        stt == null -> "没有配置语音识别引擎"
        !stt.isAvailable -> "语音识别不可用：${SherpaNative.loadError ?: "lib${SherpaNative.LIBRARY}.so 未加载"}"
        else -> "语音识别模型未加载，请先下载并加载 STT 模型"
    }

    private fun ttsProblem(): String = when {
        tts == null -> "没有配置语音合成引擎"
        !tts.isAvailable -> "语音合成不可用：${SherpaNative.loadError ?: "lib${SherpaNative.LIBRARY}.so 未加载"}"
        else -> "语音合成模型未加载，请先下载并加载 TTS 模型"
    }

    private fun ttsRate(engine: TtsEngine, pcm: FloatArray): Int =
        if (pcm.isEmpty()) 0 else (engine as? OutputSampleRateAware)?.outputSampleRate ?: 0

    private fun reason(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
}
