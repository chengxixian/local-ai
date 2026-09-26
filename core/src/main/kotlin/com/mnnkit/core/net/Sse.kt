package com.mnnkit.core.net

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server-Sent Events 与流式 HTTP 读取。
 *
 * ## 为什么必须单独有它
 *
 * [Http] 的读取路径是 `stream.readBytes()` —— **一次性把整个响应体读进内存**，
 * 只有等连接关闭才拿得到内容。这对普通请求没问题，但对下面两类**完全不可用**：
 *
 * - **SSE（`text/event-stream`）**：协议本身就是「服务端保持连接、持续推事件」，
 *   等连接关闭等于永远等不到；
 * - **MCP 的 Streamable HTTP**：响应可能是 `application/json`（一次性），
 *   也可能是 `text/event-stream`（流式），客户端必须**两种都处理**。
 *
 * 所以这里直接操作 `HttpURLConnection` 的 `inputStream`，按行读、逐块回调。
 *
 * 仍然只用 JDK 自带能力（`HttpURLConnection` + `InputStream`），
 * **不引任何第三方依赖** —— 与 [Http] 同样的理由：core 保持纯 JVM 可测。
 */
object Sse {

    /**
     * 一条 SSE 事件。
     *
     * 字段含义见 [SSE 规范](https://html.spec.whatwg.org/multipage/server-sent-events.html#event-stream-interpretation)：
     * 同一事件里可以出现**多行 `data:`**，按换行拼成一个 `data`。
     *
     * @param event `event:` 字段；未指定时为 `"message"`（规范默认值）。
     * @param data  `data:` 字段拼接结果（已去掉**最后一个**换行）。
     * @param id    `id:` 字段，可用于断线重连的 `Last-Event-ID`。
     * @param retry `retry:` 字段（毫秒），服务端建议的重连间隔。
     */
    data class Event(
        val event: String = "message",
        val data: String = "",
        val id: String? = null,
        val retry: Long? = null,
    )

    /** 一次流式调用的结果概要（内容通过回调逐块交给调用方，不在这里缓冲）。 */
    data class StreamResult(
        val code: Int,
        /** 响应头里的 `Mcp-Session-Id`（MCP 用它会话保持），没有则为 null。 */
        val sessionId: String? = null,
        val contentType: String? = null,
        /** 是否因为在回调里返回 false 而提前中断。 */
        val aborted: Boolean = false,
    )

    /**
     * POST 一个 JSON body，并按 SSE 解析响应。
     *
     * @param onEvent 每解析出一条完整事件调用一次。**返回 false 表示停止读取**并关闭连接。
     *   这是取消长连接的唯一手段 —— SSE 正常情况下不会自己结束。
     * @param readTimeoutMs 单次读的超时。SSE 是长连接，**别用 [Http] 默认的 60s**：
     *   服务端可能长时间不发心跳，超时会误判为断开。一般给几分钟到 0（无限）。
     */
    fun postEventStream(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 0,
        onEvent: (Event) -> Boolean = { true },
    ): StreamResult = requestStream(
        method = "POST",
        url = url,
        body = body,
        headers = headers + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "text/event-stream",
        ),
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        parseEvents = true,
        onEvent = onEvent,
        onLine = null,
    )

    /**
     * GET 一个 SSE 端点（部分 MCP 服务器的通知通道用 GET 打开）。
     */
    fun getEventStream(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 0,
        onEvent: (Event) -> Boolean = { true },
    ): StreamResult = requestStream(
        method = "GET",
        url = url,
        body = null,
        headers = headers + mapOf("Accept" to "text/event-stream"),
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        parseEvents = true,
        onEvent = onEvent,
        onLine = null,
    )

    /**
     * POST 一个 JSON body，**按行**回调。
     *
     * 这是最底层的原语：MCP 的响应有时是整体 JSON（没有换行分帧的概念），
     * 这时用 [postEventStream] 会解析不出东西，而按行读能把整个 body 收回来。
     *
     * @param onLine 每读到一行调用一次（**不含换行符**）。返回 false 表示停止。
     */
    fun postLines(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 60_000,
        onLine: (String) -> Boolean,
    ): StreamResult = requestStream(
        method = "POST",
        url = url,
        body = body,
        headers = headers + mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/event-stream",
        ),
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
        parseEvents = false,
        onEvent = null,
        onLine = onLine,
    )

    /** 把输入流按 SSE 规则切分事件。抽出来是为了**可纯 JVM 单测**（不碰网络）。 */
    fun parseEvents(reader: BufferedReader, onEvent: (Event) -> Boolean): Boolean {
        var eventType = "message"
        val data = StringBuilder()
        var id: String? = null
        var retry: Long? = null
        var aborted = false

        fun flush() {
            // 规范：没有任何 data 行时**不派发**事件（心跳/注释行就是靠这个被忽略的）
            if (data.isNotEmpty()) {
                onEvent(Event(eventType, data.toString(), id, retry))
            }
            eventType = "message"
            data.setLength(0)
            retry = null
            // 注意：id 在规范里是「粘性」的，跨事件保持
        }

        while (true) {
            val line = reader.readLine() ?: break
            when {
                // 空行 = 事件边界
                line.isEmpty() -> flush()
                // 注释行（常见于心跳，如 ": ping"）
                line.startsWith(":") -> Unit
                else -> {
                    val colon = line.indexOf(':')
                    val field = if (colon < 0) line else line.substring(0, colon)
                    // 规范：冒号后若有**一个**空格要去掉，多余的空格保留
                    var value = if (colon < 0) "" else line.substring(colon + 1)
                    if (value.startsWith(" ")) value = value.substring(1)
                    when (field) {
                        "event" -> eventType = value
                        "data" -> data.append(value).append('\n')
                        "id" -> if (!value.contains('\u0000')) id = value
                        "retry" -> value.toLongOrNull()?.let { retry = it }
                        else -> Unit   // 未知字段忽略
                    }
                }
            }
        }
        // 流结束时若还有未派发的事件（服务端没补最后的空行），也要发出去
        flush()
        return !aborted
    }

    private fun requestStream(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        parseEvents: Boolean,
        onEvent: ((Event) -> Boolean)?,
        onLine: ((String) -> Boolean)?,
    ): StreamResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            // 不要求压缩：SSE 需要立即拿到字节，gzip 缓冲会破坏实时性
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", Http.DEFAULT_UA)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                // 不用 fixedLength：长度已知但分块发送对长连接更友好
                setChunkedStreamingMode(0)
            }
        }

        var aborted = false
        try {
            if (body != null) {
                conn.outputStream.use { out: OutputStream ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }

            val code = conn.responseCode
            val sessionId = conn.getHeaderField("Mcp-Session-Id")
            val contentType = conn.contentType

            val stream: InputStream? = if (code in 200..299) {
                runCatching { conn.inputStream }.getOrNull()
            } else {
                runCatching { conn.errorStream }.getOrNull()
            }

            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    if (parseEvents && onEvent != null) {
                        // parseEvents 内部遇到 onEvent 返回 false 时仍会把当前事件发完，
                        // 这里用一个旗标把「已请求中断」传递出来
                        var stop = false
                        parseEvents(reader) { e ->
                            if (stop) false
                            else {
                                val keep = onEvent(e)
                                if (!keep) stop = true
                                keep
                            }
                        }
                        aborted = stop
                    } else if (onLine != null) {
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (!onLine(line)) { aborted = true; break }
                        }
                    } else {
                        // 没给回调：把内容读掉，避免连接泄漏（调用方只要状态码/头）
                        while (reader.readLine() != null) Unit
                    }
                }
            }

            // 非 2xx：把错误体读出来给调用方看（很多 MCP 服务器用它解释原因）
            if (code !in 200..299) {
                conn.errorStream?.use { it.readBytes() }
            }

            return StreamResult(code, sessionId, contentType, aborted)
        } finally {
            conn.disconnect()
        }
    }
}
