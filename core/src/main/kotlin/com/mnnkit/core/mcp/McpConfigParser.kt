package com.mnnkit.core.mcp

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify

/**
 * 传输方式。
 *
 * Android 上**只有 HTTP 系列可用**：stdio 需要 `execve` 一个子进程，
 * 而 Android 10+ 的 SELinux W^X 策略不允许执行应用数据目录里的二进制。
 * 所以 [STDIO] 只做**识别与展示**，不去尝试运行。
 */
enum class McpTransport(val label: String) {
    /** Streamable HTTP —— 现代 MCP 的标准传输，本工程实际支持。 */
    HTTP("HTTP（可连接）"),

    /** 老式 HTTP+SSE 双端点传输（2024-11-05 协议）。 */
    SSE("SSE（可连接）"),

    /** 本地子进程。Android 不支持，仅识别。 */
    STDIO("stdio（Android 不支持）"),
}

/**
 * 一个 MCP 服务端配置。
 *
 * 字段对齐官方 `mcp.json` 的 `mcpServers` 条目，便于与桌面端配置互导。
 *
 * @param url HTTP / SSE 型的地址。
 * @param command / [args] / [env] stdio 型；本工程只读取用于展示，不执行。
 * @param enabled 用户是否启用（本工程自己加的字段，不在官方格式里）。
 */
data class McpServerConfig(
    val name: String,
    val transport: McpTransport,
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
) {
    /** 能否真的连上。stdio 恒 false。 */
    val connectable: Boolean get() = transport != McpTransport.STDIO && url.isNotBlank()

    /** 配置问题说明；无问题返回 null。 */
    fun validationError(): String? = when {
        name.isBlank() -> "服务端缺少名称"
        transport == McpTransport.STDIO ->
            "stdio 传输需要启动子进程，Android 10+ 因 SELinux W^X 无法执行应用目录下的二进制"
        url.isBlank() -> "缺少 url"
        !url.startsWith("http://") && !url.startsWith("https://") ->
            "url 必须以 http:// 或 https:// 开头"
        else -> null
    }

    fun toJson(): Json = Json.Obj(
        buildMap {
            put("name", Json.Str(name))
            put("transport", Json.Str(transport.name))
            if (url.isNotBlank()) put("url", Json.Str(url))
            if (headers.isNotEmpty()) {
                put("headers", Json.Obj(headers.mapValues { Json.Str(it.value) }))
            }
            if (command.isNotBlank()) put("command", Json.Str(command))
            if (args.isNotEmpty()) put("args", Json.Arr(args.map { Json.Str(it) }))
            if (env.isNotEmpty()) put("env", Json.Obj(env.mapValues { Json.Str(it.value) }))
            put("enabled", Json.Bool(enabled))
        }
    )
}

/**
 * 解析 MCP 配置。
 *
 * 支持两种输入，都是官方生态里真实存在的形态：
 *
 * 1. **官方 `mcpServers` 包装**（Claude Desktop / Cursor 的 `mcp.json`）：
 *    ```json
 *    { "mcpServers": { "名字": { "command": "...", "args": [...] } } }
 *    ```
 * 2. **扁平单个服务端**：
 *    ```json
 *    { "name": "名字", "url": "https://..." }
 *    ```
 *
 * 单个条目坏掉**不影响其余** —— 每条独立解析，问题作为 [ParsedConfig.problems] 返回，
 * 而不是整份失败。这跟 [com.mnnkit.core.mcp.McpProtocol] 的容错取向一致。
 */
object McpConfigParser {

    /** 解析结果。 */
    data class ParsedConfig(
        val servers: List<McpServerConfig>,
        /** 每条坏配置的可读说明，形如 `名字：缺少 url`。 */
        val problems: List<String>,
    ) {
        val ok: Boolean get() = servers.isNotEmpty() || problems.isEmpty()
    }

    fun parse(text: String): Result<ParsedConfig> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Result.failure(McpException("配置内容为空"))

        val root = JsonParser.parseOrNull(trimmed)
            ?: return Result.failure(McpException("不是合法 JSON"))

        val obj = root.asObject
            ?: return Result.failure(McpException("配置必须是 JSON 对象"))

        // 形态 1：有 mcpServers 包装
        val wrapped = obj["mcpServers"]?.asObject
        if (wrapped != null) {
            if (wrapped.isEmpty()) return Result.failure(McpException("mcpServers 里没有任何服务端"))
            return Result.success(parseMap(wrapped))
        }

        // 形态 2：扁平单个
        if (obj.containsKey("name") || obj.containsKey("url") || obj.containsKey("command")) {
            val one = parseEntry(obj["name"]?.asString ?: "未命名", obj)
            return one.fold(
                onSuccess = { Result.success(ParsedConfig(listOf(it), emptyList())) },
                onFailure = { Result.failure(it) },
            )
        }

        // 形态 3：名字 -> 配置 的裸映射（没有 mcpServers 外壳）
        val looksLikeMap = obj.values.all { it.asObject != null }
        if (looksLikeMap && obj.isNotEmpty()) {
            return Result.success(parseMap(obj))
        }

        return Result.failure(
            McpException("无法识别的配置格式：既没有 mcpServers 字段，也不像单个服务端定义")
        )
    }

    private fun parseMap(map: Map<String, Json>): ParsedConfig {
        val servers = mutableListOf<McpServerConfig>()
        val problems = mutableListOf<String>()
        for ((name, value) in map) {
            val entry = value.asObject
            if (entry == null) {
                problems += "$name：配置不是对象"
                continue
            }
            parseEntry(name, entry).fold(
                onSuccess = { servers += it },
                onFailure = { problems += "$name：${it.message}" },
            )
        }
        return ParsedConfig(servers, problems)
    }

    private fun parseEntry(name: String, entry: Map<String, Json>): Result<McpServerConfig> {
        val url = entry["url"]?.asString?.trim().orEmpty()
        val command = entry["command"]?.asString?.trim().orEmpty()

        val transport = when {
            url.isNotEmpty() -> if (entry["type"]?.asString == "sse") McpTransport.SSE else McpTransport.HTTP
            command.isNotEmpty() -> McpTransport.STDIO
            else -> McpTransport.HTTP   // 缺字段：交给 validationError 报"缺少 url"
        }

        val headers: Map<String, String> = entry["headers"]?.asObject
            ?.mapNotNull { (k, v) -> v.asString?.let { k to it } }
            ?.toMap()
            ?: emptyMap()

        val args: List<String> = entry["args"]?.asArray?.mapNotNull { it.asString } ?: emptyList()

        val env: Map<String, String> = entry["env"]?.asObject
            ?.mapNotNull { (k, v) -> v.asString?.let { k to it } }
            ?.toMap()
            ?: emptyMap()

        val config = McpServerConfig(
            name = name,
            transport = transport,
            url = url,
            headers = headers,
            command = command,
            args = args,
            env = env,
            // 官方格式里 enabled 通常不存在，默认视为启用；
            // 但 stdio 型即使"启用"也连不上，UI 会标灰。
            enabled = entry["enabled"]?.asBool ?: true,
        )
        return Result.success(config)
    }

    /** 把服务端列表编码回官方 `mcpServers` 结构，便于导出与外部工具互导。 */
    fun encode(servers: List<McpServerConfig>): String {
        val map = servers.associate { s ->
            s.name to Json.Obj(
                buildMap {
                    if (s.url.isNotBlank()) put("url", Json.Str(s.url))
                    if (s.headers.isNotEmpty()) {
                        put("headers", Json.Obj(s.headers.mapValues { Json.Str(it.value) }))
                    }
                    if (s.command.isNotBlank()) put("command", Json.Str(s.command))
                    if (s.args.isNotEmpty()) put("args", Json.Arr(s.args.map { Json.Str(it) }))
                    if (s.env.isNotEmpty()) {
                        put("env", Json.Obj(s.env.mapValues { Json.Str(it.value) }))
                    }
                    if (!s.enabled) put("enabled", Json.Bool(false))
                }
            )
        }
        return Json.Obj(mapOf("mcpServers" to Json.Obj(map))).stringify(pretty = true)
    }
}
