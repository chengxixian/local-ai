package com.mnnkit.core.net

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

class HttpException(val code: Int, val url: String, message: String) : IOException("HTTP $code $message ($url)")

data class HttpResult(val code: Int, val body: String, val contentType: String?)

/**
 * 基于 java.net.HttpURLConnection 的极简 HTTP 客户端。
 *
 * 选择 HttpURLConnection 而非 OkHttp：Android 与 JVM 都内置，
 * 使 core 模块保持零第三方依赖，同时便于在 JVM 单元测试里直接跑。
 */
class Http(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
    private val userAgent: String = DEFAULT_UA,
) : Closeable {

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int? = null,
    ): HttpResult {
        val conn = open(url, headers, timeoutMs)
        return try {
            val code = conn.responseCode
            val body = readAll(code, conn)
            HttpResult(code, body, conn.contentType)
        } finally {
            conn.disconnect()
        }
    }

    /** GET 并解析 JSON；非 2xx 会抛 [HttpException]。 */
    fun getText(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int? = null,
    ): String {
        val r = get(url, headers, timeoutMs)
        if (r.code !in 200..299) throw HttpException(r.code, url, "请求失败")
        return r.body
    }

    /**
     * 发送 JSON 请求体的 PUT。
     *
     * 必需：ModelScope 的模型**搜索**接口只接受 PUT（GET 返回 404）。
     */
    fun putJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int? = null,
    ): String = sendJson("PUT", url, body, headers, timeoutMs)

    /** 发送 JSON 请求体的 POST（用于本地模型转换服务）。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int? = null,
    ): String = sendJson("POST", url, body, headers, timeoutMs)

    private fun sendJson(
        method: String,
        url: String,
        body: String,
        headers: Map<String, String>,
        timeoutMs: Int?,
    ): String {
        val conn = open(url, headers, timeoutMs)
        return try {
            conn.requestMethod = method
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            val payload = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(payload.size)
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val text = readAll(code, conn)
            if (code !in 200..299) throw HttpException(code, url, "$method 请求失败")
            text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载到文件，支持断点续传。
     *
     * 当目标文件已存在且服务器支持 Range 时会从断点继续；
     * [onProgress] 返回 false 表示调用方要求取消，会尽早中断。
     */
    fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int? = null,
        onProgress: ((downloaded: Long, total: Long) -> Boolean)? = null,
    ): Long {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        var existing = if (part.exists()) part.length() else 0L

        var attempt = 0
        var lastError: IOException? = null
        while (attempt < 3) {
            attempt++
            try {
                val reqHeaders = LinkedHashMap<String, String>(headers)
                if (existing > 0) reqHeaders["Range"] = "bytes=$existing-"

                val conn = open(url, reqHeaders, timeoutMs)
                try {
                    val code = conn.responseCode
                    if (code == 416) {
                        // Range 越界：远端文件比本地断点还短，重下
                        part.delete()
                        existing = 0
                        continue
                    }
                    if (code !in 200..299) throw HttpException(code, url, "下载失败")

                    val appending = code == 206 && existing > 0
                    if (!appending) {
                        existing = 0
                        part.delete()
                    }

                    val declared = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                    val total = if (appending) existing + declared else declared

                    val sink = if (appending) FileOutputStream(part, true) else FileOutputStream(part, false)
                    var written = existing
                    sink.use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(256 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                written += n
                                if (onProgress != null && !onProgress(written, total)) {
                                    throw CancelledException(url)
                                }
                            }
                            out.flush()
                        }
                    }

                    if (target.exists()) target.delete()
                    if (!part.renameTo(target)) {
                        part.copyTo(target, overwrite = true)
                        part.delete()
                    }
                    return target.length()
                } finally {
                    conn.disconnect()
                }
            } catch (c: CancelledException) {
                throw c
            } catch (e: IOException) {
                lastError = e
                existing = if (part.exists()) part.length() else 0L
                if (attempt >= 3) break
                Thread.sleep(500L * attempt)
            }
        }
        throw lastError ?: IOException("下载失败: $url")
    }

    private fun open(url: String, headers: Map<String, String>, timeoutMs: Int?): HttpURLConnection {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IOException("非法 URL: $url", e)
        }
        require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 http/https: $url" }
        val conn = uri.toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = timeoutMs ?: connectTimeoutMs
        conn.readTimeout = timeoutMs ?: readTimeoutMs
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", userAgent)
        conn.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    private fun readAll(code: Int, conn: HttpURLConnection): String {
        val stream: InputStream = if (code in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: return ""
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun close() {
        // HttpURLConnection 无连接池需要显式关闭
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36 MNNKit/1.0"

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun humanSpeed(bytes: Long, elapsedMs: Long): String {
            if (elapsedMs <= 0L) return "--"
            val bps = bytes * 1000.0 / elapsedMs
            return when {
                bps >= 1024 * 1024 -> String.format("%.1f MB/s", bps / (1024 * 1024))
                bps >= 1024 -> String.format("%.0f KB/s", bps / 1024)
                else -> String.format("%.0f B/s", bps)
            }
        }
    }
}

/** 调用方主动取消下载时抛出。 */
class CancelledException(val url: String) : IOException("已取消: $url")

/** 复制流到输出流，供本地导入等场景复用。 */
fun InputStream.copyToOut(out: OutputStream, bufferSize: Int = 256 * 1024): Long {
    val buf = ByteArray(bufferSize)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n <= 0) break
        out.write(buf, 0, n)
        total += n
    }
    return total
}
