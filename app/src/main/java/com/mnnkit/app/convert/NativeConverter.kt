package com.mnnkit.app.convert

import android.util.Log
import com.mnnkit.core.model.ModelFormat
import java.io.File

/**
 * 设备端 MNN 转换器。
 *
 * ## 为什么这个能跑在手机上
 *
 * MNN 的转换器（`tools/converter`）是**纯 C++**实现 —— `CMakeLists.txt` 里没有任何
 * Python / pybind 引用，输入解析、图构建、pass 优化、量化全部由 C++ 完成。
 * 因此它可以直接交叉编译进 APK，不需要在手机上跑 PyTorch。
 *
 * （对比：LLM 的 `llmexport.py` 第一行就 `import torch`，那条路无法端侧运行 ——
 *  这也是为什么应用里对 safetensors/bin/gguf 会明确提示"请用电脑端转换服务"。）
 *
 * ## 支持的输入格式
 *
 * ONNX / TFLite / Caffe / TorchScript / TensorFlow(pb) —— 全部走本地转换。
 *
 * ## 原生库
 *
 * `libMNNConvertDeps.so`（转换器，5.5MB）+ `libMNN.so`（MNN 运行时）。
 * 两者由同一个构建产出，保证 ABI 一致。
 */
object NativeConverter {

    private const val TAG = "NativeConverter"

    /** 转换器原生库名（见 app/src/main/cpp/CMakeLists.txt）。 */
    private const val LIB_CONVERT = "mnnkitconvert"

    @Volatile
    private var loadState: LoadState? = null

    private data class LoadState(val ok: Boolean, val error: String?)

    /** 惰性加载，幂等，永不抛异常。 */
    private fun ensureLoaded(): LoadState {
        loadState?.let { return it }
        synchronized(this) {
            loadState?.let { return it }
            val result = try {
                // libMNN.so 必须先加载：转换器依赖它
                System.loadLibrary("MNN")
                System.loadLibrary(LIB_CONVERT)
                LoadState(true, null)
            } catch (t: Throwable) {
                Log.w(TAG, "原生转换库加载失败", t)
                LoadState(false, t.message ?: t::class.java.simpleName)
            }
            loadState = result
            return result
        }
    }

    val isAvailable: Boolean get() = ensureLoaded().ok

    val unavailableReason: String? get() = ensureLoaded().error

    /**
     * 检查某格式能否在设备上转换。
     * @return null 表示可以转换；否则返回不可转换的原因（可直接展示给用户）。
     */
    fun checkFormat(format: ModelFormat): String? {
        if (!isAvailable) {
            return "本地转换器不可用：${unavailableReason ?: "原生库未加载"}"
        }
        return try {
            nativeCheckFormat(format.id)
        } catch (t: Throwable) {
            "检查格式失败：${t.message}"
        }
    }

    /**
     * 执行一次转换。**这是耗时操作，必须在后台线程调用。**
     */
    fun convert(request: ConvertRequest): ConvertResult {
        if (!isAvailable) {
            return ConvertResult(false, null, "", "本地转换器不可用：${unavailableReason ?: "原生库未加载"}")
        }
        val input = File(request.inputPath)
        if (!input.isFile) {
            return ConvertResult(false, null, "", "输入文件不存在：${request.inputPath}")
        }
        request.outputFile.parentFile?.mkdirs()

        return try {
            val raw = nativeConvert(
                inputPath = input.absolutePath,
                outputPath = request.outputFile.absolutePath,
                format = request.format.id,
                quantBits = request.quantBits,
                quantBlock = request.quantBlock,
                saveHalfFloat = request.saveHalfFloat,
                transformerFuse = request.transformerFuse,
            )
            if (raw == null || raw.size < 4) {
                ConvertResult(false, null, "", "原生转换未返回结果")
            } else {
                ConvertResult(
                    ok = raw[0] == "1",
                    outputFile = raw[1].takeIf { it.isNotBlank() }?.let { File(it) },
                    log = raw[2],
                    error = raw[3],
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "转换失败", t)
            ConvertResult(false, null, "", t.message ?: "转换异常")
        }
    }

    /** 把 .mnn 反解成 JSON，用于排查模型结构（调试用）。 */
    fun dumpJson(mnnFile: File, jsonFile: File): Boolean {
        if (!isAvailable) return false
        return try {
            jsonFile.parentFile?.mkdirs()
            nativeMnn2Json(mnnFile.absolutePath, jsonFile.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "mnn2json 失败", t)
            false
        }
    }

    // ---------------- native ----------------

    @JvmStatic
    private external fun nativeIsAvailable(): Boolean

    @JvmStatic
    private external fun nativeCheckFormat(format: String): String?

    @JvmStatic
    private external fun nativeConvert(
        inputPath: String,
        outputPath: String,
        format: String,
        quantBits: Int,
        quantBlock: Int,
        saveHalfFloat: Boolean,
        transformerFuse: Boolean,
    ): Array<String>?

    @JvmStatic
    private external fun nativeMnn2Json(mnnFile: String, jsonFile: String): Boolean
}

/** 一次本地转换请求。 */
data class ConvertRequest(
    val inputPath: String,
    val outputFile: File,
    val format: ModelFormat,
    /** 权重量化位宽；0 表示不量化 */
    val quantBits: Int = 0,
    val quantBlock: Int = -1,
    val saveHalfFloat: Boolean = false,
    /** 对 transformer 类模型开启融合优化（LLM 相关） */
    val transformerFuse: Boolean = false,
)

data class ConvertResult(
    val ok: Boolean,
    val outputFile: File?,
    val log: String,
    val error: String,
) {
    val outputSize: Long get() = outputFile?.length() ?: 0L

    val summary: String
        get() = if (ok) {
            "转换完成：${outputFile?.name}（${com.mnnkit.core.model.ModelItem.formatSize(outputSize)}）"
        } else {
            "转换失败：$error"
        }
}
