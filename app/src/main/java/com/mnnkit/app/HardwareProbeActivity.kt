package com.mnnkit.app

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.mnnkit.app.llm.MnnLlmEngine
import com.mnnkit.core.chat.ChatMessage
import com.mnnkit.core.chat.GenerationConfig
import com.mnnkit.core.model.ModelItem
import com.mnnkit.core.model.ModelKind
import com.mnnkit.core.model.ModelSource
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

/**
 * ADB-only 硬件探针，跑在独立的 `:hardware_probe` 进程里。
 *
 * 用途：**隔离地**验证 CPU / GPU(OpenCL) / GPU(Vulkan) 三条路径到底有没有真正执行，
 * 以及各自的吞吐。它只读本地已下载的模型，只写
 * `cache/hardware-probe-<backend>.json`，**绝不**碰对话、偏好设置或模型文件。
 *
 * 有效性边界（别把选项当证据）：
 *  - 「请求了 opencl」不等于「算子在 GPU 上跑」；
 *  - 「executor 运行时里有 3」也不等于「算子真在 GPU 执行」；
 *  - 只有 `mnnkit_capture_execution_evidence` 打开后拿到的**逐算子计数**
 *    （successful/completed/...）才是执行证据，且依旧只覆盖 MNN Pipeline 的调度，
 *    不代表物理 kernel 占用率。打开采集会给每次 OpenCL Pipeline 末尾加一次阻塞
 *    `finish()`，所以**采集期间的吞吐不能当性能数据**。
 *
 * 触发（需要 `android.permission.DUMP`）：
 * ```
 * adb shell am start -n com.mnnkit.app/.HardwareProbeActivity --es backend opencl
 * adb shell cat /sdcard/Android/data/com.mnnkit.app/files/cache/hardware-probe-opencl.json
 * ```
 * `--ez large true` 用 9B 模型；`--es prompt "..."` 换提示词（默认数到十）。
 */
class HardwareProbeActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { textSize = 18f; setPadding(32, 64, 32, 32) }
        setContentView(text)
        val backend = intent.getStringExtra("backend") ?: "cpu"
        require(backend in listOf("cpu", "opencl", "vulkan")) {
            "backend 只支持 cpu / opencl / vulkan（收到 $backend）"
        }
        val modelName = if (intent.getBooleanExtra("large", false)) {
            "MNN_Qwen3.5-9B-MNN"
        } else {
            "MNN_Qwen3.5-0.8B-MNN"
        }
        val prompt = intent.getStringExtra("prompt") ?: "Count from one to ten in English."
        val modelDir = File(getExternalFilesDir(null), "models/llm/$modelName")
        val resultFile = File(getExternalFilesDir(null), "cache/hardware-probe-$backend.json")
        val result = JSONObject()
            .put("backend", backend)
            .put("model", modelName)
            .put("prompt_characters", prompt.length)
            .put("status", "running")
        resultFile.parentFile?.mkdirs()
        resultFile.writeText(result.toString())
        text.text = "Independent hardware probe: $backend\n$modelName\nChats remain unchanged."

        scope.launch {
            val engine = MnnLlmEngine(
                backendType = backend,
                enableThinking = false,
                // 只有隔离探针打开逐算子证据采集；正常 App 恒为 false。
                captureExecutionEvidence = true,
            )
            try {
                val config = GenerationConfig(maxNewTokens = 24, temperature = 0.0, topP = 1.0)
                engine.load(
                    ModelItem(
                        id = modelName,
                        displayName = modelName,
                        kind = ModelKind.LLM,
                        source = ModelSource.LOCAL,
                        repoId = modelName,
                        localPath = modelDir.absolutePath,
                    ),
                    config,
                )
                var characters = 0
                engine.stream(listOf(ChatMessage(ChatMessage.Role.USER, prompt)), config)
                    .collect { characters += it.length }
                result.put("status", "complete").put("output_characters", characters)
                    .put("evidence", JSONObject(engine.backendReport))
                engine.lastGenerationMetrics?.let { result.put("tokens_per_second", it.tokensPerSecond) }
            } catch (e: CancellationException) {
                result.put("status", "cancelled")
                throw e
            } catch (e: Exception) {
                result.put("status", "failed").put("error", e.message ?: e.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) { runCatching { engine.unload() } }
                resultFile.writeText(result.toString())
                Log.i("HardwareProbe", result.toString())
                text.text = result.toString(2)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
