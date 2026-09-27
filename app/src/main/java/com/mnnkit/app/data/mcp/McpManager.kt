package com.mnnkit.app.data.mcp

import com.mnnkit.app.data.Storage
import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import com.mnnkit.core.mcp.McpClient
import com.mnnkit.core.mcp.McpConfigParser
import com.mnnkit.core.mcp.McpProtocol
import com.mnnkit.core.mcp.McpServerConfig
import com.mnnkit.core.mcp.McpTransport
import com.mnnkit.core.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 服务端的配置与运行时管理。
 *
 * 照 [com.mnnkit.app.data.ModelManager] 的模式写：
 * - `MutableStateFlow` + `asStateFlow()` 暴露状态；
 * - 状态类嵌在内部，每个字段都有默认值，便于 `.copy()` 局部更新；
 * - `suspend` 方法一律 `withContext(Dispatchers.IO)` + `try/catch`，
 *   异常收成 `state.error` / `state.message` 字符串，**从不向 UI 抛异常**；
 * - 长任务放 `ConcurrentHashMap<String, Job>` 以便取消。
 *
 * ## 配置落盘格式
 *
 * 直接存官方 `mcpServers` 结构（见 [McpConfigParser.encode]），
 * 这样导出的文件能直接喂给 Claude Desktop / Cursor，反之亦然。
 */
class McpManager(
    private val storage: Storage,
    private val http: Http,
    private val scope: CoroutineScope,
) {

    /** 一个服务端的运行时状态。 */
    data class ServerState(
        val config: McpServerConfig,
        val connecting: Boolean = false,
        val connected: Boolean = false,
        val tools: List<McpProtocol.Tool> = emptyList(),
        val serverName: String = "",
        val protocolVersion: String = "",
        val lastError: String? = null,
    )

    data class McpState(
        val servers: List<ServerState> = emptyList(),
        val loading: Boolean = false,
        /** 正在忙的服务端名（连接/断开的按钮禁用用）。 */
        val busyName: String? = null,
        val message: String? = null,
        val error: String? = null,
        /** 调用工具的输出（界面用一张卡展示）。 */
        val toolOutput: String? = null,
        val toolOutputIsError: Boolean = false,
    ) {
        val connectedCount: Int get() = servers.count { it.connected }
        val toolCount: Int get() = servers.sumOf { it.tools.size }
    }

    private val _state = MutableStateFlow(McpState())
    val state: StateFlow<McpState> = _state.asStateFlow()

    /** 活着的客户端。key = 服务端名。 */
    private val clients = ConcurrentHashMap<String, McpClient>()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val configFile: File get() = File(storage.root, "mcp_servers.json")

    // ── 配置持久化 ──

    /** 从磁盘读配置。启动时调用。 */
    fun load() {
        val servers = readConfigs().map { ServerState(config = it) }
        _state.value = _state.value.copy(servers = servers)
    }

    private fun readConfigs(): List<McpServerConfig> {
        if (!configFile.exists()) return emptyList()
        return runCatching {
            val text = configFile.readText()
            McpConfigParser.parse(text).getOrNull()?.servers ?: emptyList()
        }.getOrElse { emptyList() }
    }

    private fun writeConfigs(servers: List<ServerState>) {
        runCatching {
            configFile.writeText(McpConfigParser.encode(servers.map { it.config }))
        }.onFailure {
            _state.value = _state.value.copy(error = "配置保存失败：${it.message}")
        }
    }

    private fun update(block: (McpState) -> McpState) {
        _state.value = block(_state.value)
    }

    private fun updateServer(name: String, block: (ServerState) -> ServerState) {
        update { s ->
            s.copy(servers = s.servers.map { if (it.config.name == name) block(it) else it })
        }
    }

    // ── 导入 ──

    /**
     * 从粘贴的 JSON 导入服务端。已存在的同名配置会被覆盖（并按需要断开旧连接）。
     */
    fun importFromJson(text: String) {
        val parsed = McpConfigParser.parse(text).getOrElse { e ->
            update { it.copy(error = e.message ?: "解析失败", message = null) }
            return
        }

        val existing = _state.value.servers.associateBy { it.config.name }
        val incoming = parsed.servers.map { cfg ->
            // 保留已连接服务端的运行时状态
            val old = existing[cfg.name]
            if (old != null && old.config == cfg) old
            else {
                if (old?.connected == true) disconnect(cfg.name, silent = true)
                ServerState(config = cfg)
            }
        }

        // 保留没被导入覆盖的旧条目
        val incomingNames = incoming.map { it.config.name }.toSet()
        val kept = _state.value.servers.filter { it.config.name !in incomingNames }
        val merged = kept + incoming

        update {
            it.copy(
                servers = merged,
                message = buildString {
                    append("已导入 ${parsed.servers.size} 个服务端")
                    if (parsed.problems.isNotEmpty()) {
                        append("；${parsed.problems.size} 条有问题：")
                        append(parsed.problems.take(3).joinToString("；"))
                    }
                },
                error = null,
            )
        }
        writeConfigs(merged)
    }

    /** 删除一个服务端（连带断开）。 */
    fun remove(name: String) {
        disconnect(name, silent = true)
        val next = _state.value.servers.filterNot { it.config.name == name }
        update { it.copy(servers = next, message = "已删除 $name", error = null) }
        writeConfigs(next)
    }

    /** 启用 / 停用。停用会断开。 */
    fun setEnabled(name: String, enabled: Boolean) {
        if (!enabled) disconnect(name, silent = true)
        updateServer(name) { it.copy(config = it.config.copy(enabled = enabled)) }
        writeConfigs(_state.value.servers)
    }

    // ── 连接 ──

    /**
     * 连接并握手、拉取工具清单。
     *
     * 整个过程在网络线程，失败只写 `lastError`，不抛。
     */
    fun connect(name: String) {
        val target = _state.value.servers.firstOrNull { it.config.name == name } ?: return
        val cfg = target.config

        cfg.validationError()?.let { reason ->
            updateServer(name) { it.copy(lastError = reason) }
            update { it.copy(error = "$name：$reason") }
            return
        }

        if (jobs[name]?.isActive == true) return

        jobs[name] = scope.launch {
            updateServer(name) { it.copy(connecting = true, lastError = null) }
            update { it.copy(busyName = name, message = null, error = null) }
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = McpClient(http, cfg)
                    val info = client.initialize()
                    val tools = client.listTools()
                    Triple(client, info, tools)
                }
                val (client, info, tools) = result
                clients[name] = client
                updateServer(name) {
                    it.copy(
                        connecting = false,
                        connected = true,
                        tools = tools,
                        serverName = info.serverName,
                        protocolVersion = info.protocolVersion,
                        lastError = null,
                    )
                }
                update {
                    it.copy(
                        busyName = null,
                        message = "已连接 $name（${tools.size} 个工具）",
                        error = null,
                    )
                }
            } catch (e: Exception) {
                clients.remove(name)
                val reason = e.message ?: "连接失败"
                updateServer(name) { it.copy(connecting = false, connected = false, tools = emptyList(), lastError = reason) }
                update { it.copy(busyName = null, error = "$name：$reason") }
            } finally {
                jobs.remove(name)
            }
        }
    }

    /** 断开。`silent` 用于内部清理，不写 message。 */
    fun disconnect(name: String, silent: Boolean = false) {
        jobs.remove(name)?.cancel()
        clients.remove(name)
        updateServer(name) { it.copy(connected = false, connecting = false, tools = emptyList()) }
        if (!silent) update { it.copy(message = "已断开 $name", error = null) }
    }

    /** 全部断开（退出或关掉总开关时）。 */
    fun disconnectAll() {
        _state.value.servers.forEach { disconnect(it.config.name, silent = true) }
    }

    // ── 调用工具 ──

    /**
     * 调用某个已连接服务端的工具。
     *
     * @param argumentsJson 参数 JSON 文本，界面直接给用户输入。
     *   非法 JSON 会被明确报错，而不是静默当空对象。
     */
    fun callTool(server: String, tool: String, argumentsJson: String) {
        val client = clients[server]
        if (client == null || !client.isInitialized) {
            update { it.copy(error = "$server 未连接", toolOutput = null) }
            return
        }

        val args = if (argumentsJson.isBlank()) {
            Json.Obj(emptyMap())
        } else {
            JsonParser.parseOrNull(argumentsJson) ?: run {
                update { it.copy(error = "参数不是合法 JSON", toolOutput = null) }
                return
            }
        }

        scope.launch {
            update { it.copy(busyName = server, message = null, error = null) }
            try {
                val result = withContext(Dispatchers.IO) { client.callTool(tool, args) }
                update {
                    it.copy(
                        busyName = null,
                        toolOutput = "【$server / $tool】\n${result.text}",
                        toolOutputIsError = result.isError,
                        error = null,
                    )
                }
            } catch (e: Exception) {
                update {
                    it.copy(
                        busyName = null,
                        toolOutput = null,
                        error = "$server / $tool 调用失败：${e.message}",
                    )
                }
            }
        }
    }

    fun clearToolOutput() {
        update { it.copy(toolOutput = null, toolOutputIsError = false) }
    }

    // ── 提示词注入 ──

    /**
     * 把所有**已连接**服务端的工具清单拼成一段提示词。
     *
     * 没连接的服务端不参与 —— 告诉模型一个调不到的工具只会让它胡说。
     */
    fun buildToolsPrompt(maxChars: Int = 4000): String? {
        val parts = _state.value.servers
            .filter { it.connected && it.tools.isNotEmpty() }
            .mapNotNull { McpProtocol.buildToolsPrompt(it.config.name, it.tools, maxChars) }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n")
    }

    /** 便捷查询：某个工具属于哪个已连接的服务端。 */
    fun findToolOwner(toolName: String): String? =
        _state.value.servers.firstOrNull { s -> s.connected && s.tools.any { it.name == toolName } }
            ?.config?.name

    /** 内置的一条示例配置，方便用户一键填入看看格式。 */
    fun sampleConfig(): String = """
        {
          "mcpServers": {
            "example-http": {
              "url": "https://example.com/mcp",
              "headers": { "Authorization": "Bearer <token>" }
            },
            "example-local": {
              "url": "http://192.168.1.10:3000/mcp"
            },
            "example-stdio": {
              "command": "npx",
              "args": ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
            }
          }
        }
    """.trimIndent()
}
