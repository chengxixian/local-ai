package com.mnnkit.app.data.api

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.net.Http
import com.mnnkit.core.net.Sse
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * OpenAI 兼容 API 客户端。
 *
 * 覆盖四类端点（凡是提供 OpenAI 兼容接口的服务都能用，包括本地 Ollama / vLLM）：
 *
 * | 能力 | 端点 | 方法 |
 * |---|---|---|
 * | 对话 | `POST /chat/completions` | [chatStream] / [chat] |
 * | 语音识别 | `POST /audio/transcriptions` | [transcribe] |
 * | 语音合成 | `POST /audio/speech` | [speak] |
 * | 生图 | `POST /images/generations` | [generateImage] |
 *
 * ## 为什么流式对话要自己写而不是用 [Http.postJson]
 *
 * 对话要**逐字上屏**，而 `Http` 的读取路径是 `readBytes()` ——
 * 一次性把整个响应读进内存、等连接关闭才返回，对 SSE 完全不适用。
 * 这里直接操作 `HttpURLConnection.inputStream` 按行读 `data:` 事件。
 * （core 的 [Sse] 里有同样的分帧逻辑，但它面向 MCP 的请求-响应语义；
 * 这里要处理 OpenAI 特有的 `[DONE]` 结束标记与 `delta.content`，索性单独写。）
 *
 * ## 已知未做
 *
 * - **Anthropic Messages API / Gemini generateContent 不兼容**，需要各自的请求体；
 *   本工程只做 OpenAI 兼容风格。
 * - **图片理解（多模态上传）未接入** —— 请求体结构已按 OpenAI 规范预留，
 *   但界面还没做选择图片的入口。
 */
class OpenAiCompatibleClient(private val http: Http) {

    private companion object {
        const val TAG = "LocalAI-Api"
    }

    /**
     * 思考（推理）强度。
     *
     * ## 取值依据（官方文档，不是猜的）
     *
     * DeepSeek 的 `POST /chat/completions` 有一个 `thinking` 对象：
     * ```json
     * "thinking": { "type": "enabled" | "disabled", "reasoning_effort": "none"|"low"|"high"|"max" }
     * ```
     * - `thinking.type` **默认 `enabled`**
     * - `thinking.reasoning_effort` 默认 `high`；`none` 等价于关闭思考
     * - 兼容别名：`minimal` → `low`，`medium` / `xhigh` → `high`
     *
     * 文档：https://api-docs.deepseek.com/api/create-chat-completion
     *
     * ## 为什么必须显式传
     *
     * 默认 `enabled` 时，推理过程会**吃满 max_tokens**：
     * 实测 `max_tokens=10` 全部消耗在 `reasoning_tokens` 上，
     * `content` 是**空字符串**、`finish_reason="length"` ——
     * 界面上一个字都不显示，完全像「API 调用失败」。
     *
     * ## 兼容性
     *
     * 不是所有 OpenAI 兼容服务都认 `thinking` 字段。多数服务会**忽略**未知字段，
     * 但如果某个服务因此报 400，把强度调成 [AUTO] 即可（不发该字段）。
     */
    enum class ThinkingEffort(val id: String?, val label: String, val note: String) {
        AUTO(
            id = null,
            label = "不指定",
            note = "不发送 thinking 字段，由服务端决定。兼容性最好。",
        ),
        OFF(
            id = "none",
            label = "关闭思考",
            note = "最快、最省 token。适合日常问答 —— 推荐默认用这个。",
        ),
        LOW(
            id = "low",
            label = "低",
            note = "少量思考。简单推理够用。",
        ),
        HIGH(
            id = "high",
            label = "高",
            note = "服务端默认档。复杂推理更准，但慢且费 token。",
        ),
        MAX(
            id = "max",
            label = "最高",
            note = "最充分的思考。最慢、最贵，只在难题上用。",
        ),
        ;

        companion object {
            val DEFAULT = OFF

            fun fromId(id: String?): ThinkingEffort =
                entries.firstOrNull { it.id == id } ?: DEFAULT
        }
    }

    /**
     * 流式对话。
     *
     * 每收到一段增量就 emit 一次；`[DONE]` 或连接结束即完成。
     * 调用方负责取消 —— 取消会关闭底层连接。
     */
    fun chatStream(
        provider: ApiProvider,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.7,
        topP: Double = 0.9,
        maxTokens: Int = 512,
        systemPrompt: String? = null,
        thinking: ThinkingEffort = ThinkingEffort.DEFAULT,
    ): Flow<String> = callbackFlow {
        val url = provider.endpoint("chat/completions")
        val body = buildChatBody(
            provider = provider,
            messages = messages,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            stream = true,
            thinking = thinking,
        )

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            // 流式响应不能短超时：模型可能在思考期间几十秒不吐字
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            // 必须 identity：gzip 会缓冲，破坏逐字上屏
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
            setChunkedStreamingMode(0)
        }

        try {
            conn.outputStream.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(body) }
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                android.util.Log.e(TAG, "HTTP $code 请求体=$body")
                close(ApiException("HTTP $code：${extractErrorMessage(err)}"))
                return@callbackFlow
            }

            // ── 响应诊断 ──
            // 记住 HTTP 码与内容类型。零 token 且零异常时，这是唯一能区分
            // 「服务端没发数据」「我们读错了」「流被提前关掉」的办法。
            android.util.Log.i(
                TAG,
                "chatStream 已连接：HTTP $code" +
                    " contentType=${conn.contentType}" +
                    " contentLength=${conn.contentLength}" +
                    " 请求体=$body",
            )

            val reader = conn.inputStream.bufferedReader(Charsets.UTF_8)
            var lineCount = 0
            var dataCount = 0
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                // 前 5 行原始数据打出来，用于核对 SSE 分帧是否符合预期
                if (lineCount <= 5) {
                    android.util.Log.i(TAG, "chatStream 原始行#$lineCount: $line")
                }
                if (!line.startsWith("data:")) continue
                dataCount++
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (payload.isEmpty()) continue

                val choice = runCatching {
                    JsonParser.parseOrNull(payload)?.arr("choices")?.firstOrNull()
                }.getOrNull() ?: continue

                // ⚠️ 必须同时看 `reasoning_content`，不能只看 `content`。
                //
                // 推理模型（DeepSeek 的 deepseek-flash / deepseek-reasoner、
                // QwQ、以及各家带思考的模型）会**先**在 `delta.reasoning_content`
                // 里输出一段思考，之后才开始输出 `delta.content`。
                //
                // 之前只读 `content`，于是整个思考阶段被静默丢弃 ——
                // 如果 max_tokens 偏小、思考没结束就把预算耗尽，
                // 界面上会**一个字都没有**，看起来完全像「API 调用失败」。
                val content = choice.str("delta", "content")
                val reasoning = choice.str("delta", "reasoning_content")

                if (!content.isNullOrEmpty()) {
                    trySend(content)
                } else if (!reasoning.isNullOrEmpty()) {
                    trySend(reasoning)
                }
            }
            android.util.Log.i(
                TAG,
                "chatStream 结束：总行数=$lineCount data 行数=$dataCount",
            )
            close()
        } catch (e: Exception) {
            close(ApiException("请求失败：${e.message}", e))
        } finally {
            runCatching { conn.disconnect() }
        }

        awaitClose { runCatching { conn.disconnect() } }
    }

    /** 非流式对话（一次性拿完整回复）。用于「测试连接」。 */
    suspend fun chat(
        provider: ApiProvider,
        messages: List<Pair<String, String>>,
        temperature: Double = 0.7,
        topP: Double = 0.9,
        maxTokens: Int = 256,
        systemPrompt: String? = null,
    ): String {
        val body = buildChatBody(
            provider = provider,
            messages = messages,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            systemPrompt = systemPrompt,
            stream = false,
        )
        val text = http.postJson(
            url = provider.endpoint("chat/completions"),
            body = body,
            headers = authHeaders(provider),
            timeoutMs = 60_000,
        )
        val json = JsonParser.parseOrNull(text) ?: throw ApiException("响应不是 JSON")
        if (json.str("error", "message") != null) {
            throw ApiException(extractErrorMessage(text))
        }
        return json.arr("choices")?.firstOrNull()?.str("message", "content")
            ?: throw ApiException("响应里没有 choices[0].message.content")
    }

    /**
     * 语音识别（ASR）。
     *
     * 用 **multipart/form-data** 上传音频文件 —— 这是 OpenAI 的 `/audio/transcriptions`
     * 要求的格式，不能用 JSON body。
     *
     * @param language 可选的语言提示（如 `zh`）；留空让服务端自动判定。
     */
    suspend fun transcribe(
        provider: ApiProvider,
        audio: File,
        language: String? = null,
    ): String {
        require(audio.exists()) { "音频文件不存在：${audio.absolutePath}" }
        val boundary = "----LocalAI${UUID.randomUUID().toString().replace("-", "")}"
        val url = provider.endpoint("audio/transcriptions")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000        // 长音频转写可能很慢
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }

        try {
            conn.outputStream.use { out ->
                fun writeText(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

                // model 字段
                writeText("--$boundary\r\n")
                writeText("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
                writeText(provider.asrModel + "\r\n")

                // language 字段（可选）
                if (!language.isNullOrBlank()) {
                    writeText("--$boundary\r\n")
                    writeText("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    writeText(language + "\r\n")
                }

                // file 字段
                writeText("--$boundary\r\n")
                writeText(
                    "Content-Disposition: form-data; name=\"file\"; " +
                        "filename=\"${audio.name}\"\r\n",
                )
                writeText("Content-Type: application/octet-stream\r\n\r\n")
                audio.inputStream().use { it.copyTo(out) }
                writeText("\r\n--$boundary--\r\n")
            }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw ApiException("ASR HTTP $code：${extractErrorMessage(text)}")
            }
            val json = JsonParser.parseOrNull(text)
            // OpenAI 返回 {"text": "..."}
            return json?.str("text")
                ?: throw ApiException("ASR 响应里没有 text 字段：${text.take(200)}")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 语音合成（TTS）。
     *
     * 返回的音频字节流直接写入 [target]。
     *
     * ## 关于格式
     *
     * 请求 `response_format` 默认用 **mp3**（OpenAI 支持 mp3/opus/aac/flac/wav/pcm）。
     * 选 mp3 是因为用户要的「音频转 mp3 供下载」在这里天然满足 ——
     * **服务端直接产出 mp3，不需要再转码**。
     */
    suspend fun speak(
        provider: ApiProvider,
        text: String,
        target: File,
        format: String = "mp3",
        speed: Double = 1.0,
    ): File {
        val body = Json.Obj(
            mapOf(
                "model" to Json.Str(provider.ttsModel),
                "input" to Json.Str(text),
                "voice" to Json.Str(provider.ttsVoice.ifBlank { "alloy" }),
                "response_format" to Json.Str(format),
                "speed" to Json.Num(speed),
            )
        ).stringify()

        val conn = (URL(provider.endpoint("audio/speech")).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
        }

        try {
            conn.outputStream.use { out ->
                OutputStreamWriter(out, Charsets.UTF_8).use { it.write(body) }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                throw ApiException("TTS HTTP $code：${extractErrorMessage(err)}")
            }
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { out -> input.copyTo(out) }
            }
            return target
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /**
     * 文生图。
     *
     * @param size 形如 `1024x1024`；留空用服务端默认。
     * @param saveTo 下载目标；返回的 `url` 或 `b64_json` 都会被落盘到这里。
     */
    suspend fun generateImage(
        provider: ApiProvider,
        prompt: String,
        saveTo: File,
        size: String? = null,
        n: Int = 1,
    ): File {
        val fields = mutableMapOf<String, Json>(
            "model" to Json.Str(provider.imageModel),
            "prompt" to Json.Str(prompt),
            "n" to Json.Num(n.toDouble()),
            // 要 b64 而不是 url：url 可能过期，且多一次下载；
            // 但**不是所有服务都支持 b64_json**，所以下面两种都处理。
            "response_format" to Json.Str("b64_json"),
        )
        size?.takeIf { it.isNotBlank() }?.let { fields["size"] = Json.Str(it) }

        val text = http.postJson(
            url = provider.endpoint("images/generations"),
            body = Json.Obj(fields).stringify(),
            headers = authHeaders(provider),
            timeoutMs = 300_000,      // 生图很慢
        )

        val json = JsonParser.parseOrNull(text) ?: throw ApiException("生图响应不是 JSON")
        if (json.str("error", "message") != null) {
            throw ApiException(extractErrorMessage(text))
        }
        val first = json.arr("data")?.firstOrNull()
            ?: throw ApiException("响应里没有 data[0]")

        saveTo.parentFile?.mkdirs()

        // 优先 b64
        val b64 = first.str("b64_json")
        if (!b64.isNullOrBlank()) {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            saveTo.writeBytes(bytes)
            return saveTo
        }

        // 退而求其次：下载 url
        val remote = first.str("url")
        if (!remote.isNullOrBlank()) {
            http.download(remote, saveTo, timeoutMs = 180_000)
            return saveTo
        }

        throw ApiException("响应里既没有 b64_json 也没有 url")
    }

    // ── 内部 ──

    private fun authHeaders(provider: ApiProvider): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        if (provider.apiKey.isNotBlank()) {
            put("Authorization", "Bearer ${provider.apiKey}")
        }
    }

    private fun buildChatBody(
        provider: ApiProvider,
        messages: List<Pair<String, String>>,
        temperature: Double,
        topP: Double,
        maxTokens: Int,
        systemPrompt: String?,
        stream: Boolean,
        thinking: ThinkingEffort = ThinkingEffort.DEFAULT,
    ): String {
        val msgs = mutableListOf<Json>()
        if (!systemPrompt.isNullOrBlank()) {
            msgs += Json.Obj(
                mapOf("role" to Json.Str("system"), "content" to Json.Str(systemPrompt))
            )
        }
        messages.forEach { (role, content) ->
            msgs += Json.Obj(
                mapOf("role" to Json.Str(role), "content" to Json.Str(content))
            )
        }

        val fields = mutableMapOf<String, Json>(
            "model" to Json.Str(provider.llmModel),
            "messages" to Json.Arr(msgs),
            "temperature" to Json.Num(temperature),
            "top_p" to Json.Num(topP),
            "max_tokens" to Json.Num(maxTokens.toDouble()),
            "stream" to Json.Bool(stream),
        )

        // 思考开关。按官方文档传 `thinking` 对象；AUTO 时不发这个字段。
        thinking.id?.let { effort ->
            fields["thinking"] = Json.Obj(
                mapOf(
                    "type" to Json.Str(if (effort == "none") "disabled" else "enabled"),
                    "reasoning_effort" to Json.Str(effort),
                )
            )
        }

        return Json.Obj(fields).stringify()
    }

    /** 从各家五花八门的错误响应里尽量挖出一句人话。 */
    private fun extractErrorMessage(raw: String): String {
        if (raw.isBlank()) return "（无响应体）"
        val json = JsonParser.parseOrNull(raw)
        json?.str("error", "message")?.let { return it }
        json?.str("message")?.let { return it }
        json?.str("error")?.let { return it }
        return raw.take(300)
    }
}

/** API 调用失败。`message` 直接展示给用户，所以要可读。 */
class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
