package com.mnnkit.app

import android.app.Application
import kotlinx.coroutines.launch

class MnnKitApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // ── 原生库自检 ──
        // MnnLlmEngine 是 by lazy 的，不主动碰它就不会触发 System.loadLibrary，
        // 于是「库到底能不能加载」在启动日志里完全看不到 —— 排查 JNI 问题时这是最大盲区。
        // 这里主动探一次。只探 isAvailable（内部即 tryLoadLibrary），不加载模型。
        runCatching {
            val ok = container.llmEngine.isAvailable
            android.util.Log.i(
                "LocalAI-JNI",
                "llmEngine.isAvailable=$ok" +
                    if (ok) "" else " —— libmnnkitbridge.so 加载失败，原因见 MnnLlmSession 的警告日志",
            )
            // 再走一步：真正调用一次 native 方法。
            // 「库加载成功」不等于「符号能解析」—— 两者失败的信息完全不同：
            //   库没加载   → UnsatisfiedLinkError，且 message 里通常带 dlopen 原因
            //   符号没对上 → UnsatisfiedLinkError，message 形如 "No implementation found for ..."
            // 用一个必然不存在的 config 路径去调，业务上会失败，但能证明符号是否可达。
            if (ok) {
                runCatching {
                    com.mnnkit.app.llm.MnnLlmSession(
                        com.mnnkit.app.llm.MnnLlmSession.lazyLibraryLoader()
                    ).load("/definitely/not/here/config.json", "{}")
                }.onFailure { e ->
                    val kind = when {
                        e is UnsatisfiedLinkError && e.message?.contains("No implementation found") == true ->
                            "符号未解析（JNI 绑定问题）"
                        e is UnsatisfiedLinkError -> "库加载问题"
                        else -> "业务错误（说明符号可达，这是正常的）"
                    }
                    android.util.Log.i("LocalAI-JNI", "initNative 探针 → $kind :: ${e.message?.take(300)}")
                }.onSuccess {
                    android.util.Log.i("LocalAI-JNI", "initNative 探针意外成功")
                }
            }
        }.onFailure {
            android.util.Log.e("LocalAI-JNI", "探测 isAvailable 抛异常", it)
        }

        // MCP 的配置存在磁盘上（mcp_servers.json），需要显式读一次才会进状态。
        // 放在这里而不是 McpManager 的 init：构造期做 IO 会拖慢 Application 启动，
        // 而这一步只是读一个小 JSON。
        runCatching { container.mcpManager.load() }

        // API 提供商同样存在磁盘上（api_providers.json）。
        // 必须在这里读：不读的话「模型」面板里永远是空的，
        // 用户会以为配置没保存成功。
        runCatching { container.apiProviders.load() }

        // Skill 的商店索引读 assets + 扫描已安装目录，同样是轻量 IO。
        container.appScope.launch {
            runCatching { container.skillManager.refresh() }
        }
    }
}
