// ============================================================================
// ⚠️ 本文件的物理路径在 com/mnnkit/app/speech/，但**包名必须是 com.k2fsa.sherpa.mnn**。
//
// 原因：JNI 静态注册的符号名由类的二进制名决定——
//   Java_com_k2fsa_sherpa_mnn_OnlineRecognizer_newFromFile
//   Java_com_k2fsa_sherpa_mnn_OnlineStream_acceptWaveform
// （见 apps/frameworks/sherpa-mnn/sherpa-mnn/jni/online-recognizer.cc:286、
//   jni/online-stream.cc:16；本工程 libsherpa-mnn-jni.so 已实测导出这两个符号。）
// 因此包装类只能叫 `com.k2fsa.sherpa.mnn.OnlineRecognizer` / `OnlineStream`。
//
// 为什么不直接放进 com/k2fsa/sherpa/mnn/：那个目录是官方 kotlin-api 的**逐字拷贝**，
// 不允许新增/修改（任务硬性约束）。Kotlin 不要求文件路径与包名一致，故把这份
// "缺失的官方流式封装" 放在本模块目录下，包名照旧。
//
// 注意：若日后把官方 kotlin-api/OnlineRecognizer.kt 与 OnlineStream.kt 拷进
// com/k2fsa/sherpa/mnn/，会与本文件重复定义类，必须删除本文件。
//
// 字段名 / 方法签名与官方逐字一致（JNI 侧用 GetFieldID("decodingMethod","Ljava/lang/String;")
// 之类的方式按名字取值，改名会导致 NoSuchFieldError）。
// ============================================================================
package com.k2fsa.sherpa.mnn

import android.content.res.AssetManager

data class EndpointRule(
    var mustContainNonSilence: Boolean,
    var minTrailingSilence: Float,
    var minUtteranceLength: Float,
)

data class EndpointConfig(
    var rule1: EndpointRule = EndpointRule(false, 2.4f, 0.0f),
    var rule2: EndpointRule = EndpointRule(true, 1.4f, 0.0f),
    var rule3: EndpointRule = EndpointRule(false, 0.0f, 20.0f),
)

data class OnlineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
)

data class OnlineParaformerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
)

data class OnlineZipformer2CtcModelConfig(
    var model: String = "",
)

data class OnlineNeMoCtcModelConfig(
    var model: String = "",
)

data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    var zipformer2Ctc: OnlineZipformer2CtcModelConfig = OnlineZipformer2CtcModelConfig(),
    var neMoCtc: OnlineNeMoCtcModelConfig = OnlineNeMoCtcModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = "",
)

data class OnlineLMConfig(
    var model: String = "",
    var scale: Float = 0.5f,
)

data class OnlineCtcFstDecoderConfig(
    var graph: String = "",
    var maxActive: Int = 3000,
)

data class OnlineRecognizerConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var lmConfig: OnlineLMConfig = OnlineLMConfig(),
    var ctcFstDecoderConfig: OnlineCtcFstDecoderConfig = OnlineCtcFstDecoderConfig(),
    var endpointConfig: EndpointConfig = EndpointConfig(),
    var enableEndpoint: Boolean = true,
    var decodingMethod: String = "greedy_search",
    var maxActivePaths: Int = 4,
    var hotwordsFile: String = "",
    var hotwordsScore: Float = 1.5f,
    var ruleFsts: String = "",
    var ruleFars: String = "",
    var blankPenalty: Float = 0.0f,
)

data class OnlineRecognizerResult(
    val text: String,
    val tokens: Array<String>,
    val timestamps: FloatArray,
)

/**
 * 流式识别器（官方 `kotlin-api/OnlineRecognizer.kt` 的同名类）。
 *
 * `newFromFile` 在配置校验失败时返回 0，官方 Kotlin 侧不检查，
 * 这里补上检查：构造后 ptr == 0 立刻抛 [IllegalStateException]，
 * 由 [com.mnnkit.app.speech.SherpaSttEngine] 翻译成中文错误。
 */
class OnlineRecognizer(
    assetManager: AssetManager? = null,
    val config: OnlineRecognizerConfig,
) {
    private var ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        check(ptr != 0L) {
            "OnlineRecognizer 创建失败（native 返回空指针，通常是模型路径/配置不合法）"
        }
    }

    val isValid: Boolean get() = ptr != 0L

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun createStream(hotwords: String = ""): OnlineStream {
        val p = createStream(ptr, hotwords)
        check(p != 0L) { "OnlineStream 创建失败（native 返回空指针）" }
        return OnlineStream(p)
    }

    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)
    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)
    fun isEndpoint(stream: OnlineStream): Boolean = isEndpoint(ptr, stream.ptr)
    fun isReady(stream: OnlineStream): Boolean = isReady(ptr, stream.ptr)

    fun getResult(stream: OnlineStream): OnlineRecognizerResult {
        val objArray = getResult(ptr, stream.ptr)
        return OnlineRecognizerResult(
            text = objArray[0] as String,
            tokens = objArray[1] as Array<String>,
            timestamps = objArray[2] as FloatArray,
        )
    }

    private external fun delete(ptr: Long)

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: OnlineRecognizerConfig,
    ): Long

    private external fun newFromFile(config: OnlineRecognizerConfig): Long

    private external fun createStream(ptr: Long, hotwords: String): Long
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun isEndpoint(ptr: Long, streamPtr: Long): Boolean
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun getResult(ptr: Long, streamPtr: Long): Array<Any>

    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}

/** 流式输入缓冲（官方 `kotlin-api/OnlineStream.kt` 的同名类）。 */
class OnlineStream(var ptr: Long = 0) {

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-mnn-jni")
        }
    }
}
