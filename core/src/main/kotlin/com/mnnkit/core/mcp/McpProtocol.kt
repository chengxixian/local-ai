package com.mnnkit.core.mcp

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify

/**
 * MCP（Model Context Protocol）的协议层：纯数据 + 编解码，**不做任何 IO**。
 *
 * 放在 core 而不是 app 是为了**可纯 JVM 单测** —— 这里全是纯函数，
 * 不需要 Android 环境就能验证。
 *
 * 编解码一律走本工程自带的 [Json] / [JsonParser]，**不引 kotlinx-serialization** ——
 * 与 core 其余部分同样的零依赖原则。
 *
 * 协议版本见 [PROTOCOL_VERSION]。
 */
object McpProtocol {

    /**
     * 本客户端声明的协议版本。
     *
     * MCP 的 `initialize` 要带上它；服务端若不支持会回自己的版本，
     * 客户端应据此决定是否继续（见 [McpClient]）。
     */
    const val PROTOCOL_VERSION = "2025-06-18"

    /** 客户端实现名与版本，会在 `initialize` 里报给服务端。 */
    const val CLIENT_NAME = "local-ai"
    const val CLIENT_VERSION = "1.0.0"

    // ── JSON-RPC 2.0 错误码 ──

    const val CODE_PARSE_ERROR = -32700
    const val CODE_INVALID_REQUEST = -32600
    const val CODE_METHOD_NOT_FOUND = -32601
    const val CODE_INVALID_PARAMS = -32602
    const val CODE_INTERNAL_ERROR = -32603

    /** JSON-RPC 错误对象。 */
    data class RpcError(val code: Int, val message: String, val data: Json? = null) {
        fun toJson(): Json = Json.Obj(
            buildMap {
                put("code", Json.Num(code.toDouble()))
                put("message", Json.Str(message))
                data?.let { put("data", it) }
            }
        )

        companion object {
            fun from(json: Json?): RpcError? {
                if (json == null) return null
                val obj = json.asObject ?: return null
                return RpcError(
                    code = obj["code"]?.asInt ?: CODE_INTERNAL_ERROR,
                    message = obj["message"]?.asString ?: "未知错误",
                    data = obj["data"],
                )
            }
        }
    }

    /**
     * 一次 JSON-RPC 响应。
     *
     * 注意 `id` 用 **String** 存：JSON-RPC 允许数字或字符串，
     * 而本工程的 [Json.Num] 底层是 Double，大整数会精度丢失。
     * 统一转成字符串比较，服务端回数字还是字符串都能对上。
     */
    data class RpcResponse(
        val id: String?,
        val result: Json?,
        val error: RpcError?,
    ) {
        val isOk: Boolean get() = error == null

        companion object {
            fun from(json: Json): RpcResponse {
                val obj = json.asObject
                    ?: return RpcResponse(null, null, RpcError(CODE_INVALID_REQUEST, "响应不是 JSON 对象"))
                val idJson = obj["id"]
                val id = when (idJson) {
                    is Json.Str -> idJson.value
                    is Json.Num -> {
                        val d = idJson.value
                        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
                    }
                    else -> null
                }
                return RpcResponse(
                    id = id,
                    result = obj["result"],
                    error = RpcError.from(obj["error"]),
                )
            }
        }
    }

    /** 从一段可能是「单个 JSON」也可能是「SSE 事件里的 JSON」的文本里取出响应。 */
    fun parseResponse(text: String): Result<RpcResponse> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(McpException("响应为空"))
        val json = JsonParser.parseOrNull(trimmed)
            ?: return Result.failure(McpException("响应不是合法 JSON：${trimmed.take(200)}"))
        // 可能是批量响应（数组），取第一个
        val first = if (json is Json.Arr) json.items.firstOrNull() else json
        if (first == null) return Result.failure(McpException("响应数组为空"))
        return Result.success(RpcResponse.from(first))
    }

    /** 构造一个 JSON-RPC 请求体。 */
    fun request(id: Long, method: String, params: Json? = null): String {
        val fields = mutableMapOf<String, Json>(
            "jsonrpc" to Json.Str("2.0"),
            "id" to Json.Num(id.toDouble()),
            "method" to Json.Str(method),
        )
        params?.let { fields["params"] = it }
        return Json.Obj(fields).stringify()
    }

    /** 构造一个 JSON-RPC 通知（没有 id，服务端不回复）。 */
    fun notification(method: String, params: Json? = null): String {
        val fields = mutableMapOf<String, Json>(
            "jsonrpc" to Json.Str("2.0"),
            "method" to Json.Str(method),
        )
        params?.let { fields["params"] = it }
        return Json.Obj(fields).stringify()
    }

    /** `initialize` 的 params。 */
    fun initializeParams(): Json = Json.Obj(
        mapOf(
            "protocolVersion" to Json.Str(PROTOCOL_VERSION),
            "capabilities" to Json.Obj(emptyMap()),
            "clientInfo" to Json.Obj(
                mapOf(
                    "name" to Json.Str(CLIENT_NAME),
                    "version" to Json.Str(CLIENT_VERSION),
                )
            ),
        )
    )

    // ── 服务端能力的解析结果 ──

    /**
     * `initialize` 的返回。
     *
     * @param serverName / [serverVersion] 来自 `serverInfo`，仅用于界面展示。
     * @param supportsTools `capabilities.tools` 是否存在 —— 不存在就别去 `tools/list` 了。
     * @param instructions 服务端给的可选提示（会拼进 system prompt）。
     */
    data class ServerInfo(
        val protocolVersion: String,
        val serverName: String,
        val serverVersion: String,
        val supportsTools: Boolean,
        val instructions: String?,
    ) {
        companion object {
            fun from(result: Json?): ServerInfo {
                val caps = result?.asObject?.get("capabilities")?.asObject
                val info = result?.asObject?.get("serverInfo")?.asObject
                return ServerInfo(
                    protocolVersion = result?.asObject?.get("protocolVersion")?.asString ?: "",
                    serverName = info?.get("name")?.asString ?: "未命名服务端",
                    serverVersion = info?.get("version")?.asString ?: "",
                    supportsTools = caps?.containsKey("tools") == true,
                    instructions = result?.asObject?.get("instructions")?.asString,
                )
            }
        }
    }

    /**
     * 一个可调用工具。
     *
     * @param inputSchema JSON Schema；渲染进提示词时**原样**给模型看，
     *   这样模型能知道每个参数的类型与是否必填。
     */
    data class Tool(
        val name: String,
        val description: String,
        val inputSchema: Json?,
    ) {
        companion object {
            fun from(json: Json): Tool? {
                val obj = json.asObject ?: return null
                val name = obj["name"]?.asString ?: return null
                return Tool(
                    name = name,
                    description = obj["description"]?.asString ?: "",
                    inputSchema = obj["inputSchema"],
                )
            }
        }
    }

    /**
     * `tools/call` 的返回。
     *
     * MCP 的内容块有多种类型（text / image / resource）；
     * 本工程只渲染 text，其余类型给出一个可读的占位说明。
     */
    data class ToolResult(val text: String, val isError: Boolean) {
        companion object {
            fun from(result: Json?): ToolResult {
                val obj = result?.asObject
                val blocks = obj?.get("content")?.asArray ?: emptyList()
                val sb = StringBuilder()
                for (block in blocks) {
                    val b = block.asObject ?: continue
                    when (b["type"]?.asString) {
                        "text" -> {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(b["text"]?.asString.orEmpty())
                        }
                        "image" -> {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append("[图片内容，本工程暂不渲染]")
                        }
                        "resource" -> {
                            if (sb.isNotEmpty()) sb.append('\n')
                            val uri = b["resource"]?.asObject?.get("uri")?.asString
                            sb.append("[资源 ${uri ?: "未命名"}，本工程暂不渲染]")
                        }
                        else -> Unit
                    }
                }
                return ToolResult(
                    text = sb.toString().ifEmpty { "(工具返回了空内容)" },
                    isError = obj?.get("isError")?.asBool == true,
                )
            }
        }
    }

    /**
     * 把工具清单渲染进提示词。
     *
     * 与 Skill 的「渐进式披露」同一个思路：只给**名称 + 描述 + 参数 schema**，
     * 并明确告诉模型用什么格式调用 —— 端侧小模型光看工具名是猜不出调用格式的。
     */
    fun buildToolsPrompt(serverName: String, tools: List<Tool>, maxChars: Int = 4000): String? {
        if (tools.isEmpty()) return null
        val sb = StringBuilder()
        sb.append("你可以调用以下工具（来自 MCP 服务端「").append(serverName).append("」）。\n")
        sb.append("需要时，只输出一行 JSON，不要有别的文字：\n")
        sb.append("{\"tool\":\"工具名\",\"arguments\":{...}}\n\n")
        sb.append("可用工具：\n")
        for (t in tools) {
            val line = buildString {
                append("- ").append(t.name)
                if (t.description.isNotBlank()) append("：").append(t.description.replace('\n', ' '))
                val props = t.inputSchema?.asObject?.get("properties")?.asObject
                if (props != null && props.isNotEmpty()) {
                    append("\n  参数：")
                    append(
                        props.entries.joinToString("、") { (k, v) ->
                            val type = (v.asObject?.get("type")?.asString) ?: "any"
                            "$k($type)"
                        }
                    )
                }
                val required = t.inputSchema?.asObject?.get("required")?.asArray
                if (required != null && required.isNotEmpty()) {
                    append("；必填：")
                    append(required.mapNotNull { it.asString }.joinToString("、"))
                }
            }
            if (sb.length + line.length + 1 > maxChars) {
                sb.append("\n(工具过多，其余已省略)")
                break
            }
            sb.append(line).append('\n')
        }
        return sb.toString()
    }
}

/** MCP 相关失败。带可读原因，界面直接展示 `message`。 */
class McpException(message: String, cause: Throwable? = null) : Exception(message, cause)
