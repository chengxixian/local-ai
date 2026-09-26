package com.mnnkit.app.llm

import android.util.Log
import java.io.File

/**
 * 模型配置的修正点。
 *
 * ## ⚠️ 目前这里**什么都不改**
 *
 * 曾经在这里写 `jinja.context.enable_thinking = <用户设置>`，想借此控制思考模式。
 * 结果是三重错误：
 *
 * 1. **无效**：`config.json` 的 `jinja` 会被 `llm_config.json` 的同名键整个覆盖
 *    （`LlmConfig::LlmConfig` 是浅合并），而后者**没有** `enable_thinking` 这个键
 *    —— 我们写进去的值根本到不了模板。真机日志可证：
 *    我们发 `enable_thinking:false`，最终生效的仍是 `true`。
 *
 * 2. **语义不干净**：这个 patch 是**粘性**的。用户改一次设置就把模型目录改了，
 *    之后即使恢复默认配置启动，模型文件也已经是另一个状态 ——
 *    用户没法通过「重装应用」回到干净状态。
 *
 * 3. **引入新变量**：排查「本地模型只出 1 个 token」时，
 *    这个 patch 让「模型到底跑的是什么配置」变得不可知，反而掩盖了真正的原因。
 *
 * 所以现在**不动任何配置文件**，让模型跑它自带的原始设置。
 *
 * ## 那思考模式怎么控制？
 *
 * 走 **prompt 层**（对模型文件无损、随时可撤销），而不是改模型配置。
 * 相关取值见 [com.mnnkit.app.data.api.OpenAiCompatibleClient.ThinkingEffort]。
 *
 * ## 被改过的模型怎么恢复
 *
 * 早期版本会在模型目录留下 `config.json.mnnkit-orig`。
 * 删掉被改的 `config.json` / `llm_config.json`、把 `.mnnkit-orig` 改回原名即可，
 * 或者直接在「模型商店」里重新下载。
 */
object ModelConfigPatcher {

    private const val TAG = "ModelConfigPatcher"

    /** patch 结果。 */
    data class Result(
        /** 被修改的文件名；现在是空的（不再改任何文件）。 */
        val patched: List<String>,
        /** 附加说明，供日志输出。 */
        val note: String?,
    )

    /**
     * 检查模型目录的配置，**并自动回滚早期版本留下的改动**。
     *
     * ## 为什么要自动回滚
     *
     * 早期版本会改模型目录里的 `config.json` / `llm_config.json`
     * （想借此控制思考模式），改之前留下 `<文件名>.mnnkit-orig` 备份。
     *
     * 那些改动是**粘性**的：应用升级、清数据、甚至重装 APK 都不会撤销它 ——
     * 文件在模型目录里，而模型目录是用户下载下来的资产。
     * 结果是「用户什么都没做，模型却一直跑在一份被改过的配置上」，
     * 排查时完全看不出。
     *
     * 所以这里主动把备份还原回去，让模型回到出厂状态。
     * 还原后删掉备份，避免反复执行。
     */
    fun patch(modelDir: File): Result {
        val restored = mutableListOf<String>()

        listOf("config.json", "llm_config.json").forEach { name ->
            val backup = File(modelDir, "$name.mnnkit-orig")
            if (!backup.isFile) return@forEach

            val current = File(modelDir, name)
            runCatching {
                // 还原原始内容，然后删掉备份
                current.writeText(backup.readText())
                backup.delete()
                restored += name
                Log.i(TAG, "已从备份还原 $name（撤销早期版本的配置改动）")
            }.onFailure {
                Log.w(TAG, "还原 $name 失败", it)
            }
        }

        val present = listOf("config.json", "llm_config.json")
            .filter { File(modelDir, it).isFile }

        return Result(
            patched = restored,
            note = if (restored.isEmpty()) {
                "模型配置未改动（存在：$present）"
            } else {
                "已还原被早期版本改过的配置：${restored.joinToString(", ")}"
            },
        )
    }
}
