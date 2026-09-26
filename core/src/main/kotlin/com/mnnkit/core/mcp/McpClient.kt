package com.mnnkit.core.mcp

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.net.Http
import com.mnnkit.core.net.Sse
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP 客户端（Streamable HTTP 传输）。
 *
 * ## 握手流程
 *
 * ```
 * POST initialize            → 拿 serverInfo / capabilities，以及响应头 Mcp-Session-Id
 * POST notifications/initialized  （通知，服务端不回复）
 * POST tools/list            → 拿工具清单
 * POST tools/call            → 调用某个工具
 * ```
 *
 * ## 两个必须处理的现实情况
 *
 * 1. **响应可能是 JSON，也可能是 SSE。**
 *    规范允许服务端按请求选择：一次性响应用 `application/json`，
 *    流式响应用 `text/event-stream`。所以 [call] 先按行读，
 *    读到以 `data:` 开头的行就切到 SSE 解析，否则把整段当 JSON。
 * 2. **会话必须保持。** 服务端在 `initialize` 的响应头里给 `Mcp-Session-Id`，
 *    之后**每个请求都要回填**这个头，否则会被当成新会话（工具列表会丢）。
 *
 * ## 不做的事
 *
 * - 不执行 stdio 型服务端（Android 10+ 无法 `execve` 应用目录下的二进制）；
 * - 不处理服务端主动推的通知通道（`GET` 打开的那条 SSE）——
 *   本工程只需要「请求-响应」语义，主动通知对端侧模型没有用处。
 *
 * 本类**不做线程切换**，调用方负责放到 IO 线程（见 `McpManager`）。
 */
class McpClient(
    private val http: Http,
    private val config: McpServerConfig,
) {
    private val nextId = AtomicLong(1)

    /** `initialize` 拿到的服务端信息；未握手时为 null。 */
    var serverInfo: McpProtocol.ServerInfo? = null
        private set

    /** 会话 id（响应头 `Mcp-Session-Id`）；服务端不给就是 null。 */
    var sessionId: String? = null
        private set

    /** 已拉取的工具清单。 */
    var tools: List<McpProtocol.Tool> = emptyList()
        private set

    val isInitialized: Boolean get() = serverInfo != null

    /** 当前请求要带的头：会话 id + 配置里的自定义头。 */
    private fun headers(): Map<String, String> = buildMap {
        putAll(config.headers)
        sessionId?.let { put("Mcp-Session-Id", it) }
    }

    /**
     * 握手。
     *
     * @throws McpException 配置不可连、网络失败、或服务端返回协议错误。
     */
    fun initialize(): McpProtocol.ServerInfo {
        config.validationError()?.let { throw McpException("配置不可用：$it") }
        if (!config.connectable) throw McpException("该服务端不可连接")

        val body = McpProtocol.request(nextId.getAndIncrement(), "initialize", McpProtocol.initializeParams())
        val resp = call(body)
        resp.error?.let { throw McpException("initialize 失败：${it.message}（${it.code}）") }

        val info = McpProtocol.ServerInfo.from(resp.result)
        if (info.protocolVersion.isBlank()) {
            throw McpException("服务端没有返回 protocolVersion，可能不是 MCP 服务端")
        }

        serverInfo = info

        // 规范要求握手后立刻发一条 initialized 通知（无 id、不期待回复）。
        // 失败不算致命：有些服务端不实现它也能用。
        runCatching { call(McpProtocol.notification("notifications/initialized")) }

        return info
    }

    /**
     * 拉取工具清单。需要先 [initialize]。
     *
     * 服务端没声明 `tools` 能力时返回空列表（而不是报错）——
     * 「这个服务端不提供工具」是正常状态。
     */
    fun listTools(): List<McpProtocol.Tool> {
        val info = serverInfo ?: throw McpException("尚未握手，先调用 initialize()")
        if (!info.supportsTools) {
            tools = emptyList()
            return emptyList()
        }

        val body = McpProtocol.request(nextId.getAndIncrement(), "tools/list", Json.Obj(emptyMap()))
        val resp = call(body)
        resp.error?.let { throw McpException("tools/list 失败：${it.message}（${it.code}）") }

        val arr = resp.result?.asObject?.get("tools")?.asArray ?: emptyList()
        tools = arr.mapNotNull { McpProtocol.Tool.from(it) }
        return tools
    }

    /**
     * 调用一个工具。
     *
     * @param arguments 参数对象；调用方负责与 `inputSchema` 对齐。
     */
    fun callTool(name: String, arguments: Json = Json.Obj(emptyMap())): McpProtocol.ToolResult {
        if (serverInfo == null) throw McpException("尚未握手，先调用 initialize()")

        val params = Json.Obj(mapOf("name" to Json.Str(name), "arguments" to arguments))
        val body = McpProtocol.request(nextId.getAndIncrement(), "tools/call", params)
        val resp = call(body)
        resp.error?.let { throw McpException("调用 $name 失败：${it.message}（${it.code}）") }
        return McpProtocol.ToolResult.from(resp.result)
    }

    /**
     * 发一个 JSON-RPC 请求并解析响应。
     *
     * 这里同时应付两种响应形态（见类注释）：
     * 先按行收集，遇到 `data:` 前缀就说明服务端选了 SSE。
     * 单次请求的流应当很快结束，所以 SSE 情况下**读到第一个完整事件就停**。
     */
    private fun call(body: String): McpProtocol.RpcResponse {
        // 通知没有 id，服务端按规范应回 202 且无正文
        val isNotification = !body.contains("\"id\"")

        val collected = StringBuilder()
        var sseData: String? = null

        val result = Sse.postLines(
            url = config.url,
            body = body,
            headers = headers(),
            readTimeoutMs = READ_TIMEOUT_MS,
        ) { line ->
            when {
                // SSE：只认 data 行；event/id/retry 对请求-响应语义没用
                line.startsWith("data:") -> {
                    val payload = line.removePrefix("data:").removePrefix(" ")
                    val merged = if (sseData == null) payload else sseData + "\n" + payload
                    sseData = merged
                    // 一条 data 行就是完整 JSON 时可以直接收工；
                    // 多行 data 的情况等空行（由 parseEvents 处理）——
                    // 这里按行读没有空行概念，所以判断能否解析来决定是否停。
                    JsonParser.parseOrNull(merged.trim()) != null
                }
                line.isBlank() -> true
                else -> {
                    collected.append(line)
                    true
                }
            }
        }

        if (result.code !in 200..299) {
            // 202 是通知的正常回复
            if (result.code == 202 && isNotification) {
                return McpProtocol.RpcResponse(null, Json.Null, null)
            }
            throw McpException("HTTP ${result.code}：${collected.toString().take(300).ifBlank { "无响应体" }}")
        }

        // 会话 id 只在第一次响应里有，后面即使没有也不能把已有的覆盖掉
        result.sessionId?.let { sessionId = it }

        if (isNotification) return McpProtocol.RpcResponse(null, Json.Null, null)

        val payload = sseData?.trim().takeUnless { it.isNullOrEmpty() }
            ?: collected.toString().trim()
        if (payload.isEmpty()) throw McpException("服务端返回了空响应体")

        return McpProtocol.parseResponse(payload).getOrElse { throw it }
    }

    private companion object {
        /**
         * 单次请求的读超时。
         *
         * 比 [Http] 默认的 60s 短：MCP 的请求-响应都是轻量交互，
         * 卡住 60 秒对界面来说太久（用户以为死了）。工具**执行**本身可能很慢，
         * 那种情况应由服务端自己控时。
         */
        const val READ_TIMEOUT_MS = 30_000
    }
}
