package com.mnnkit.app.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 把应用私有目录里的图片 / 音频导出到系统「下载」或相册。
 *
 * ## 为什么用 MediaStore 而不是直接写文件
 *
 * Android 10+ 起应用**不能**直接往共享存储写文件（分区存储）。
 * 正确做法是通过 [MediaStore] 插入一条记录、拿到系统给的 Uri，再往它的
 * `openOutputStream()` 里写 —— 这样文件会出现在相册 / 下载里，
 * 且**不需要任何存储权限**。
 *
 * ## 为什么不用 `MediaStore.Images.Media.IS_PENDING`
 *
 * 那是 Android 10+ 的 API。本工程 minSdk 33，必然存在，但为了让代码在
 * 低版本上也不崩（例如以后放宽 minSdk），这里用 `Build.VERSION` 判断后再加。
 * 不设 IS_PENDING 的后果是别的应用可能在写入完成前就看到半张图。
 */
object MediaExport {

    /** 导出结果。 */
    data class Result(val ok: Boolean, val message: String)

    /**
     * 把图片保存到相册。
     *
     * @param displayName 显示名，会自动补扩展名。
     */
    fun saveImage(context: Context, source: File, displayName: String): Result {
        if (!source.exists()) return Result(false, "源文件不存在：${source.name}")
        val name = ensureExt(displayName, "png")
        val mime = mimeOf(name)

        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalAI")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result(false, "系统拒绝了写入请求")

            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return Result(false, "无法打开输出流")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Result(true, "已保存到相册（Pictures/LocalAI/$name）")
        }.getOrElse { Result(false, "保存失败：${it.message}") }
    }

    /**
     * 把音频保存到「下载」。
     *
     * @param asMp3 true 时把文件按 mp3 扩展名与 MIME 导出。
     *   **注意**：这只是命名与 MIME，不改变编码。真正转 mp3 需要编码器
     *   （见 [com.mnnkit.app.speech.AudioExport]），系统 MediaStore 不做转码。
     */
    fun saveAudio(
        context: Context,
        source: File,
        displayName: String,
        asMp3: Boolean = false,
    ): Result {
        if (!source.exists()) return Result(false, "源文件不存在：${source.name}")
        val name = ensureExt(displayName, if (asMp3) "mp3" else "wav")
        val mime = if (asMp3) "audio/mpeg" else "audio/wav"

        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LocalAI")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return Result(false, "系统拒绝了写入请求")

            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return Result(false, "无法打开输出流")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            Result(true, "已保存到 Music/LocalAI/$name")
        }.getOrElse { Result(false, "保存失败：${it.message}") }
    }

    private fun ensureExt(name: String, ext: String): String =
        if (name.substringAfterLast('.', "").equals(ext, ignoreCase = true)) {
            name
        } else {
            name.substringBeforeLast('.', name) + "." + ext
        }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }
}
