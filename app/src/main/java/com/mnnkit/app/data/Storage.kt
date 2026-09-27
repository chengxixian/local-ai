package com.mnnkit.app.data

import android.content.Context
import java.io.File

/**
 * 应用的存储布局。
 *
 * 模型放在外部私有目录（`Android/data/<pkg>/files`），原因：
 *  - 单个 LLM 权重可达数 GB，内部存储常常不够；
 *  - 外部私有目录不需要任何权限即可读写，卸载即清理；
 *  - 用户可通过文件管理器侧载模型，便于调试。
 */
class Storage(context: Context) {

    private val ctx = context.applicationContext

    /** 根目录；外部不可用时回退到内部存储。 */
    val root: File by lazy {
        val external = ctx.getExternalFilesDir(null)
        (external ?: ctx.filesDir).also { it.mkdirs() }
    }

    /** 已安装模型：root/models/<kind>/<sanitized-id>/ */
    val modelsDir: File by lazy { File(root, "models").also { it.mkdirs() } }

    /** 下载中的临时文件：root/downloads/ */
    val downloadsDir: File by lazy { File(root, "downloads").also { it.mkdirs() } }

    /** 已安装的 Skill：root/skills/<name>/ */
    val skillsDir: File by lazy { File(root, "skills").also { it.mkdirs() } }

    /** Skill 的暂存/下载目录 */
    val skillsStagingDir: File by lazy { File(root, "skills/.staging").also { it.mkdirs() } }

    /** 数据仓库缓存（模型清单、MCP 注册表等） */
    val cacheDir: File by lazy { File(root, "cache").also { it.mkdirs() } }

    /** 生成的图片 */
    val imagesDir: File by lazy { File(root, "images").also { it.mkdirs() } }

    /** 录音缓存 */
    val audioDir: File by lazy { File(root, "audio").also { it.mkdirs() } }

    /** 日志 */
    val logsDir: File by lazy { File(root, "logs").also { it.mkdirs() } }

    /**
     * 对话历史的存放位置：`root/chat/conversation.json`
     *
     * 放外部私有目录（与模型同级）而不是 SharedPreferences：
     *  - 对话可能是几百 KB 的文本 + 图片路径，不适合塞进 SharedPreferences
     *    （那边是 XML，全量重写，越大越慢）；
     *  - 用户能用文件管理器直接看到/备份/删除；
     *  - 卸载即清理，语义与「应用数据」一致。
     */
    val conversationFile: File
        get() = File(File(root, "chat").also { it.mkdirs() }, "conversation.json")

    /** 某个模型应当安装到的目录。 */
    fun modelDir(kindId: String, modelId: String): File =
        File(File(modelsDir, kindId), sanitize(modelId)).also { it.mkdirs() }

    /** 把仓库 id 变成安全的目录名：`MNN/Qwen3-0.6B-MNN` → `MNN__Qwen3-0.6B-MNN` */
    fun sanitize(id: String): String =
        id.replace('\\', '_')
            .replace('/', '_')
            .replace(":", "_")
            .replace(" ", "_")
            .replace("..", "_")
            .take(120)

    /** 递归统计目录占用。 */
    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0L
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { sizeOf(it) } ?: 0L
    }

    /** 删除目录/文件。 */
    fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        return file.delete()
    }
}
