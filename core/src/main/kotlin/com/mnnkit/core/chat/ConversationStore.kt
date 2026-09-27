package com.mnnkit.core.chat

import com.mnnkit.core.json.Json
import com.mnnkit.core.json.JsonParser
import com.mnnkit.core.json.stringify
import java.io.File

/**
 * 对话历史的**持久化**。
 *
 * ## 为什么需要它
 *
 * 对话消息原先只活在 Compose 的 `remember { mutableStateOf(...) }` 里 ——
 * 进程一退（用户划掉、系统回收、崩溃重启）整段上下文就没了，
 * 用户看到的效果是「一退出上下文就清空，没有对话历史」。
 *
 * 注意这跟**记忆库**（`data/memory/`，SQLite FTS4）是两件事：
 * 记忆库存的是从对话里**提炼出来的长期记忆**，用于检索注入；
 * 这里是原样的对话消息，用于重启后**恢复界面与上下文**。
 *
 * ## 为什么放在 core 而不是 app
 *
 * 1. 它是纯逻辑 + `java.io.File`，没有任何 Android 依赖；
 * 2. `:core` 的单测环境是现成的（`kotlin("test")` 在纯 kotlin-jvm 模块里
 *    能直接解析出 JUnit5 变体），而 `:app` 里要额外配测试框架 ——
 *    把这段逻辑放这里，序列化正确性就能被单测覆盖。
 *    见 `ConversationStoreCodecTest`。
 *
 * ## 存储格式
 *
 * ```json
 * {
 *   "version": 1,
 *   "updatedAt": 1758900000000,
 *   "messages": [
 *     { "role": "user", "text": "你好" },
 *     { "role": "assistant", "text": "你好", "reasoning": "……" }
 *   ]
 * }
 * ```
 *
 * ## 设计取舍
 *
 * 1. **同步读写，不引协程**。调用方自己在 IO 协程里调。
 * 2. **不抛异常**。丢历史是可接受的降级，但绝不能因为写历史失败把聊天搞崩；
 *    失败信息通过返回值 / [Snapshot.error] 上报，由调用方决定是否记日志。
 * 3. **跳过空回复**。生成中途退出会留下空 `text` 的气泡，存下来下次启动
 *    会看到一个空气泡。这类残缺回复一律不落盘。
 * 4. **上限裁剪**。只保留最近 [MAX_MESSAGES] 条。
 */
class ConversationStore(private val file: File) {

    /** 一条存下来的消息。字段与 UI 的 `ChatMessageUi` 对齐，但不依赖 UI 层。 */
    data class StoredMessage(
        val role: String,
        val text: String,
        val reasoning: String? = null,
        val imagePath: String? = null,
        val audioPath: String? = null,
    ) {
        /**
         * 是否值得落盘。
         *
         * 空文本的气泡有三种情况都不该存：生成中途被杀、请求失败被清掉、
         * 以及首帧占位。带图片的（文生图）除外 —— 那种回复本来就没有文字；
         * 只有思考内容的（推理吃满 max_tokens）也要保留。
         */
        val isPersistable: Boolean
            get() = text.isNotBlank() || !reasoning.isNullOrBlank() || imagePath != null
    }

    /** 读到的结果。 */
    data class Snapshot(
        val messages: List<StoredMessage> = emptyList(),
        /** 文件里的 `updatedAt`，用于展示「上次对话时间」。 */
        val updatedAt: Long = 0L,
        /** 读取失败时的说明，供日志/界面提示；成功为 null。 */
        val error: String? = null,
    )

    /**
     * 读取历史。文件不存在或解析失败都返回空 [Snapshot]，**不抛异常**。
     *
     * 解析失败时刻意**不删除**文件 —— 万一是我们格式升级改错了，
     * 用户的历史还在磁盘上，可以人工救回来。
     */
    fun load(): Snapshot {
        if (!file.isFile) return Snapshot()
        val text = try {
            file.readText()
        } catch (error: Exception) {
            return Snapshot(error = "读取失败：${error.message ?: error::class.java.simpleName}")
        }
        return decode(text)
    }

    /**
     * 写入历史。返回是否成功。
     *
     * 裁剪策略：从**尾部**保留最近 [MAX_MESSAGES] 条。不按 token 数裁剪，
     * 因为这里的目的只是「重启后能把界面和上下文恢复回来」，
     * 真正的上下文长度由每轮生成时的历史组装决定。
     */
    fun save(messages: List<StoredMessage>): Boolean = try {
        file.parentFile?.mkdirs()
        // 先写临时文件再改名：避免写到一半被杀，留下半截 JSON 把历史毁掉
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(encode(messages))
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            // renameTo 在少数文件系统上会失败，退化成直接写
            file.writeText(encode(messages))
            tmp.delete()
        }
        true
    } catch (error: Exception) {
        false
    }

    /** 清空历史（「新话题」用）。 */
    fun clear(): Boolean = try {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        true
    } catch (error: Exception) {
        false
    }

    companion object {
        /** 存储格式版本。将来改结构时用它做迁移判断。 */
        const val VERSION = 1

        /**
         * 最多保留多少条消息。
         *
         * 200 条对手机屏幕来说已经翻不完，文件也就几百 KB；
         * 更多的价值不如让用户开新话题。
         */
        const val MAX_MESSAGES = 200

        /**
         * 消息列表 → JSON 文本。
         *
         * 抽成纯函数是为了能在单测里直接验证序列化正确性（不碰文件 IO）。
         */
        fun encode(messages: List<StoredMessage>): String {
            val kept = messages
                .filter { it.isPersistable }
                .takeLast(MAX_MESSAGES)

            val payload = Json.Obj(
                linkedMapOf(
                    "version" to Json.Num(VERSION.toDouble()),
                    "updatedAt" to Json.Num(System.currentTimeMillis().toDouble()),
                    "messages" to Json.Arr(
                        kept.map { m ->
                            val fields = linkedMapOf<String, Json>(
                                "role" to Json.Str(m.role),
                                "text" to Json.Str(m.text),
                            )
                            m.reasoning?.let { fields["reasoning"] = Json.Str(it) }
                            m.imagePath?.let { fields["imagePath"] = Json.Str(it) }
                            m.audioPath?.let { fields["audioPath"] = Json.Str(it) }
                            Json.Obj(fields)
                        },
                    ),
                ),
            )
            return payload.stringify()
        }

        /**
         * JSON 文本 → 消息列表。
         *
         * **不抛异常**：任何解析问题都收成 [Snapshot.error]，
         * 让调用方能继续用一个空的对话界面。损坏的文件不删。
         */
        fun decode(text: String): Snapshot {
            if (text.isBlank()) return Snapshot()

            val root = JsonParser.parseOrNull(text)
                ?: return Snapshot(error = "历史文件不是合法 JSON")

            val arr = root["messages"]?.asArray.orEmpty()
            val messages = arr.mapNotNull { item ->
                val obj = item.asObject ?: return@mapNotNull null
                // role 是必需的；缺失说明这条记录结构不对，跳过而不是猜
                val role = obj["role"]?.asString ?: return@mapNotNull null
                val msg = StoredMessage(
                    role = role,
                    text = obj["text"]?.asString.orEmpty(),
                    reasoning = obj["reasoning"]?.asString,
                    imagePath = obj["imagePath"]?.asString,
                    audioPath = obj["audioPath"]?.asString,
                )
                // 顺便丢掉历史里已经残缺的条目，避免空气泡被反复恢复
                msg.takeIf { it.isPersistable }
            }

            return Snapshot(
                messages = messages,
                updatedAt = root["updatedAt"]?.asLong ?: 0L,
            )
        }
    }
}
