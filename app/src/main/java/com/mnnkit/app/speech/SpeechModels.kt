package com.mnnkit.app.speech

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import java.io.File

/**
 * STT（流式 Zipformer 中英双语）模型目录里被识别出来的文件。
 *
 * 仓库名前缀会随版本变化（`encoder-epoch-99-avg-1.int8.mnn`、
 * `encoder.int8.mnn`、`encoder-epoch-12-avg-4-chunk-16-left-128.mnn` …），
 * 所以这里只保留"语义角色 ↔ 实际文件"的映射，加载时用真实路径。
 */
data class SttModelFiles(
    val encoder: File,
    val decoder: File?,
    val joiner: File?,
    val tokens: File,
    /** true 表示这是一套流式模型（必须用 OnlineRecognizer）。 */
    val looksStreaming: Boolean,
) {
    /** transducer（encoder+decoder+joiner）还是单文件 CTC。 */
    val isTransducer: Boolean get() = decoder != null && joiner != null
}

/** TTS（bert-vits2）模型目录里被识别出来的文件。 */
data class TtsModelFiles(
    val generator: File,
    val tokens: File?,
    val configJson: File?,
    /** 从 config.json 猜到的说话人数；猜不到为 0。 */
    val speakerCountHint: Int,
    /** 可选：VITS/piper 风格模型的词典文件。 */
    val lexicon: File? = null,
    /** 可选：`espeak-ng-data` 之类的前端资源目录。 */
    val dataDir: File? = null,
    /** 可选：jieba 分词词典目录。 */
    val dictDir: File? = null,
)

/**
 * 模型文件的**模糊发现**逻辑（纯 JVM，可单元测试）。
 *
 * 约定（与 `ModelManager` 一致）：`ModelItem.localPath` 指向模型目录，
 * 目录内可能直接是文件，也可能有一层（或多层）子目录，所以递归查找。
 */
object SpeechModels {

    /** 递归深度上限，避免误入巨大的无关目录。 */
    const val MAX_DEPTH = 4

    // ------------------------------------------------------------------
    // 通用：列出文件 + 按关键字挑选
    // ------------------------------------------------------------------

    /** 递归列出普通文件（忽略隐藏文件与 `.part` 未完成下载）。 */
    fun listFiles(dir: File, maxDepth: Int = MAX_DEPTH): List<File> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        fun walk(current: File, depth: Int) {
            if (depth > maxDepth) return
            val children = current.listFiles() ?: return
            for (child in children) {
                if (child.name.startsWith(".")) continue
                if (child.name.endsWith(".part")) continue
                if (child.isDirectory) {
                    walk(child, depth + 1)
                } else if (child.isFile) {
                    out.add(child)
                }
            }
        }
        walk(dir, 0)
        return out
    }

    /**
     * 在 [files] 里挑出"最合适"的一个文件：
     *  - 必须匹配 [extension]（传空串表示不限扩展名）；
     *  - 文件名（basename，忽略大小写）必须包含 [keyword]；
     *  - 打分：int8 权重优先（手机端内存/速度），其次关键字出现在文件名开头；
     *  - 平手时：目录层级浅 > 名字短 > 字典序（保证结果稳定可测）。
     */
    fun pick(
        files: List<File>,
        keyword: String,
        extension: String = ".mnn",
        excludeKeywords: List<String> = emptyList(),
    ): File? {
        val key = keyword.lowercase()
        return files.asSequence()
            .filter { extension.isEmpty() || it.name.endsWith(extension, ignoreCase = true) }
            .filter { it.name.lowercase().contains(key) }
            .filter { f -> excludeKeywords.none { f.name.lowercase().contains(it.lowercase()) } }
            .map { it to score(it, key) }
            .sortedWith(
                compareByDescending<Pair<File, Int>> { it.second }
                    .thenBy { depthOf(it.first) }
                    .thenBy { it.first.name.length }
                    .thenBy { it.first.name },
            )
            .firstOrNull()
            ?.first
    }

    /** 挑出体积最大的 `.mnn`（用于"生成器文件没有 generator 字样"的兜底）。 */
    fun pickLargestMnn(files: List<File>, excludeKeywords: List<String> = emptyList()): File? =
        files.asSequence()
            .filter { it.name.endsWith(".mnn", ignoreCase = true) }
            .filter { f -> excludeKeywords.none { f.name.lowercase().contains(it.lowercase()) } }
            .sortedWith(
                compareByDescending<File> { it.length() }
                    .thenBy { depthOf(it) }
                    .thenBy { it.name.length }
                    .thenBy { it.name },
            )
            .firstOrNull()

    /** 递归找名字包含 [keyword] 的**目录**（最浅优先），用于 espeak-ng-data / 词典目录。 */
    fun findDirectory(dir: File, keyword: String, maxDepth: Int = MAX_DEPTH): File? {
        if (!dir.isDirectory) return null
        val key = keyword.lowercase()
        val out = ArrayList<File>()
        fun walk(current: File, depth: Int) {
            if (depth > maxDepth) return
            current.listFiles()?.forEach { child ->
                if (!child.isDirectory || child.name.startsWith(".")) return@forEach
                if (child.name.lowercase().contains(key)) out.add(child)
                walk(child, depth + 1)
            }
        }
        walk(dir, 0)
        return out.sortedWith(compareBy({ depthOf(it) }, { it.name.length }, { it.name })).firstOrNull()
    }

    private fun score(file: File, lowerKeyword: String): Int {
        val name = file.name.lowercase()
        var s = 0
        // 手机端优先 int8 权重
        if (name.contains("int8")) s += 4
        // 关键字出现在文件名开头（例如 encoder-epoch-99…），比 "streaming-encoder" 之类更可信
        if (name.startsWith(lowerKeyword)) s += 2
        return s
    }

    private fun depthOf(file: File): Int = file.absolutePath.count { it == File.separatorChar }

    // ------------------------------------------------------------------
    // STT
    // ------------------------------------------------------------------

    /**
     * 从模型目录发现 STT 文件。
     *
     * 失败时返回带可读中文原因的 [Result]（缺少哪些文件、目录在哪），
     * 便于 UI 直接展示。
     */
    fun discoverStt(dir: File, repoIdHint: String? = null): Result<SttModelFiles> {
        if (!dir.isDirectory) {
            return Result.failure(SpeechException("语音识别模型目录不存在：${dir.absolutePath}"))
        }
        val files = listFiles(dir)
        if (files.isEmpty()) {
            return Result.failure(SpeechException("语音识别模型目录为空：${dir.absolutePath}"))
        }

        val encoder = pick(files, "encoder")
        val decoder = pick(files, "decoder")
        val joiner = pick(files, "joiner")
        // 官方仓库里词表叫 tokens.txt；部分转换产物叫 tokenizer.txt
        val tokens = pick(files, "tokens", extension = ".txt")
            ?: pick(files, "tokenizer", extension = ".txt")

        val missing = buildList {
            if (encoder == null) add("encoder*.mnn")
            if (tokens == null) add("tokens.txt")
        }
        if (missing.isNotEmpty() || encoder == null || tokens == null) {
            return Result.failure(
                SpeechException(
                    "语音识别模型文件不完整，缺少 ${missing.joinToString("、")}（目录 ${dir.absolutePath}，" +
                        "实际文件：${files.joinToString(", ") { it.name }}）",
                ),
            )
        }

        return Result.success(
            SttModelFiles(
                encoder = encoder,
                decoder = decoder,
                joiner = joiner,
                tokens = tokens,
                looksStreaming = looksStreaming(dir, repoIdHint, files.map { it.name }),
            ),
        )
    }

    /**
     * 判断一套权重是不是**流式**模型。
     *
     * 这是本模块最容易踩的坑：官方模型市场里 STT 条目是
     * `sherpa-mnn-streaming-zipformer-bilingual-zh-en-2023-02-20`，
     * 流式 encoder 带 `conv_cache/attn_cache/processed_lens` 等状态输入，
     * 只能喂给 `OnlineRecognizer`（离线识别器不提供这些状态，结果为空或直接失败）。
     */
    fun looksStreaming(dir: File, repoIdHint: String?, fileNames: List<String>): Boolean {
        val haystack = buildString {
            append(dir.name).append(' ')
            append(dir.absolutePath).append(' ')
            if (repoIdHint != null) append(repoIdHint).append(' ')
            fileNames.forEach { append(it).append(' ') }
        }.lowercase()
        return haystack.contains("streaming") || haystack.contains("chunk-")
    }

    // ------------------------------------------------------------------
    // TTS
    // ------------------------------------------------------------------

    /** 从模型目录发现 TTS 文件。语义同上。 */
    fun discoverTts(dir: File): Result<TtsModelFiles> {
        if (!dir.isDirectory) {
            return Result.failure(SpeechException("语音合成模型目录不存在：${dir.absolutePath}"))
        }
        val files = listFiles(dir)
        if (files.isEmpty()) {
            return Result.failure(SpeechException("语音合成模型目录为空：${dir.absolutePath}"))
        }

        // bert-vits2 的生成器叫 tts_generator_w_bert_chenxi_0310_int8.mnn；
        // 其它 vits 转换产物可能只叫 model.mnn，所以做两级兜底。
        val generator = pick(files, "generator")
            ?: pick(files, "vits")
            ?: pickLargestMnn(files)
        if (generator == null) {
            return Result.failure(
                SpeechException(
                    "语音合成模型目录里没有 .mnn 权重（目录 ${dir.absolutePath}，" +
                        "实际文件：${files.joinToString(", ") { it.name }}）",
                ),
            )
        }

        val tokens = pick(files, "tokenizer", extension = ".txt")
            ?: pick(files, "tokens", extension = ".txt")
        val configJson = pick(files, "config", extension = ".json")

        return Result.success(
            TtsModelFiles(
                generator = generator,
                tokens = tokens,
                configJson = configJson,
                speakerCountHint = readSpeakerCount(configJson),
                // 下面三项是可选的：VITS/piper 风格模型需要 lexicon/dataDir，
                // 中文 jieba 前端需要 dictDir；bert-vits2 目录里通常没有这些文件。
                lexicon = pick(files, "lexicon", extension = ".txt"),
                dataDir = findDirectory(dir, "espeak"),
                dictDir = findDirectory(dir, "dict"),
            ),
        )
    }

    /** 读 config.json 里的说话人数；读不到返回 0（不抛异常）。 */
    fun readSpeakerCount(configJson: File?): Int {
        if (configJson == null || !configJson.isFile) return 0
        val json = runCatching { JsonParser.parseOrNull(configJson.readText()) }.getOrNull()
        return parseSpeakerCount(json)
    }

    /**
     * 从 config.json 猜说话人数。
     *
     * bert-vits2 的 config 里字段名各版本不同（`num_speakers` / `n_speakers` /
     * `speakers` / `data.spk2id`），这里逐个尝试；都找不到返回 0，
     * 之后会用 native 的 `OfflineTts.numSpeakers()` 覆盖。
     */
    fun parseSpeakerCount(json: Json?): Int {
        if (json == null) return 0

        val numericKeys = listOf(
            "num_speakers", "numSpeakers", "n_speakers", "nSpeakers",
            "speaker_count", "speakerCount", "num_speaker",
        )
        for (key in numericKeys) {
            json.int(key)?.takeIf { it > 0 }?.let { return it }
            json.path("data")?.int(key)?.takeIf { it > 0 }?.let { return it }
            json.path("model")?.int(key)?.takeIf { it > 0 }?.let { return it }
        }

        val containerKeys = listOf("speakers", "spk2id", "speaker2id", "speaker_id_map", "id2spk")
        for (key in containerKeys) {
            for (node in listOfNotNull(json[key], json.path("data")?.get(key), json.path("model")?.get(key))) {
                node.asArray?.size?.takeIf { it > 0 }?.let { return it }
                node.asObject?.size?.takeIf { it > 0 }?.let { return it }
            }
        }
        return 0
    }
}
