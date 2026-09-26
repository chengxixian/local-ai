package com.mnnkit.core.speech

import com.mnnkit.core.model.ModelItem
import kotlinx.coroutines.flow.Flow

/** 语音识别的分段结果。 */
data class RecognitionSegment(
    val text: String,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val isFinal: Boolean = true,
)

/**
 * 语音识别（STT）引擎。
 *
 * 两种使用方式都必须支持：
 *  1. 独立使用——用户录一段音/选一个音频文件，转成文字；
 *  2. 与 LLM 组合——边录边转写，转写完成后送入 LLM 形成语音输入。
 */
interface SttEngine {

    val isAvailable: Boolean

    var loadedModel: ModelItem?

    suspend fun load(model: ModelItem)

    suspend fun unload()

    /**
     * 识别一段完整音频（PCM 16kHz 单声道 float 或 16bit）。
     * 用于"语音转文字"独立功能。
     */
    suspend fun transcribe(pcm: FloatArray, sampleRate: Int = 16_000): String

    /**
     * 流式识别：边喂音频边产出中间结果。
     * 用于语音输入场景，让用户看到实时转写。
     */
    fun stream(pcmChunks: Flow<FloatArray>, sampleRate: Int = 16_000): Flow<RecognitionSegment>
}

/** 语音合成（TTS）引擎。 */
interface TtsEngine {

    val isAvailable: Boolean

    var loadedModel: ModelItem?

    suspend fun load(model: ModelItem)

    suspend fun unload()

    /**
     * 合成语音。[text] 可为长文本，实现内部负责分句以免单次过长。
     * 返回单声道 PCM float 采样（[-1, 1]）。
     */
    suspend fun synthesize(
        text: String,
        speakerId: Int = 0,
        speed: Float = 1.0f,
    ): FloatArray

    /** 可用的说话人列表（多说话人模型才有）。 */
    val speakerCount: Int
}

/** 端到端语音对话的一步结果，供 UI 展示链路状态。 */
sealed interface VoiceTurnEvent {
    /** 录音中的实时转写片段 */
    data class PartialTranscript(val text: String) : VoiceTurnEvent

    /** 最终识别文本 */
    data class FinalTranscript(val text: String) : VoiceTurnEvent

    /** LLM 的增量回复 */
    data class ReplyToken(val text: String) : VoiceTurnEvent

    /** 回复完毕，附带合成好的音频，可直接播放 */
    data class ReplyComplete(val text: String, val pcm: FloatArray?, val sampleRate: Int) : VoiceTurnEvent

    data class Failed(val message: String) : VoiceTurnEvent
}
