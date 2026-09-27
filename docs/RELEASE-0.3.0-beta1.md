# Local AI v0.3.0-beta1

> **测试版**。核心链路（模型商店 / 下载 / Skill / MCP / 记忆库 / 液态玻璃）已跑通，
> 对话链路修了三个阻塞性 bug，但**都还没有在真机上验证过**
> （本轮开发机没有连接 Android 设备）。

推理后端是 [MNN](https://github.com/alibaba/MNN)。**模型不随 APK 分发** ——
装好后在应用内的「模型商店」自己下载（可选 HuggingFace 或魔搭镜像）。

---

## 安装

- **ABI**：仅 `arm64-v8a`
- **最低系统**：Android 13（API 33）
- **体积**：8.75 MB（不含模型）
- **签名**：本地测试密钥 `CN=MNNKit`（**不是**正式发布密钥）
  - SHA-256：`dc5036bda119d38128d721b24cd915d5366511b88df507c8216cb7d52d8b7d58`

签名密钥与 0.1.0 / 0.2.0-beta1 的测试包**不同**，装之前需要先卸载旧版本
（否则报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

---

## 本版新增：对话历史不再丢失

### 症状
应用一退出（划掉、被系统回收、崩溃重启），**整段对话上下文就没了**，
没有任何历史记录。

### 根因
对话消息只活在 Compose 的 `remember { mutableStateOf(...) }` 里：

```kotlin
var messages by remember { mutableStateOf(listOf<ChatMessageUi>()) }
```

`remember` 的生命周期就是这次组合（进程内），进程一结束即丢。
**完全没有落盘逻辑。**

> 注意区分：仓库里已有的**记忆库**（SQLite FTS4）不是对话历史 ——
> 它存的是从对话里**提炼出来的长期记忆**，用于检索注入，
> 不负责在重启后恢复界面。

### 修复
新增 `core/chat/ConversationStore.kt`，把对话落盘到
`Android/data/com.mnnkit.app/files/chat/conversation.json`：

```json
{
  "version": 1,
  "updatedAt": 1758900000000,
  "messages": [
    { "role": "user", "text": "你好" },
    { "role": "assistant", "text": "你好，有什么可以帮你？", "reasoning": "……" }
  ]
}
```

启动时读取恢复，之后自动保存。三个关键实现细节：

| 细节 | 为什么 |
|---|---|
| **恢复时把已有新消息接在后面**（`restored + messages`） | 如果先 await 恢复再挂保存，期间用户已发的消息会被恢复结果冲掉；直接 `messages + restored` 又会颠倒时间线 |
| **保存防抖 300ms** | 流式生成每个 token 都改 `messages`，逐次写盘会在 IO 线程上写上百次文件 |
| **原子写（临时文件 + rename）** | 写到一半被杀，不会留下半截 JSON 把历史毁掉 |

另外：

- 空回复（生成中途被杀留下的空气泡）**不落盘**，避免重启后看到一个空壳；
- 只有思考内容、或只有图片的回复**会**保留；
- 上限 200 条，超出从尾部裁剪；
- 历史文件损坏时**不删除**，只报错并给空界面，留给人工救回；
- 启动恢复后会提示「已恢复上次的对话（N 轮）」，免得消息像凭空冒出来；
- 「新话题」按钮现在**同时清空磁盘上的历史** —— 否则重启后旧对话又回来。

---

## 顺带修掉的另外两个问题（0.2.0-beta1 里的，这里一并列出）

### 1. API 流式对话界面全白
`chatStream` 的请求写入与 SSE 逐行读取**没有切线程**，跑在
`AndroidUiDispatcher.Main` 上 → 主线程被阻塞，界面一个字不显示，
而且**一条 token 日志都没有**。现已整段移入 `withContext(Dispatchers.IO)`。

本地路径本来就有 `withContext(IO)`，所以**只有 API 路径坏** ——
这个不对称是最有力的线索。

### 2. 思考内容被当成正文
`delta.reasoning_content` 与 `delta.content` 原先进同一个累加器。
现已拆成 `ChatDelta.Content` / `ChatDelta.Reasoning`，
界面加可折叠的「思考过程」块（默认折叠、一行预览、正文未开始时显示「思考中…」）。

### 3. 纠正一处**错误结论**
README/注释里曾写「本地 LLM 只出 1 个 token 是因为 MNN 的 `LlmConfig` 浅合并、
`jinja` 被 `llm_config.json` 整体覆盖，所以 `enable_thinking` 传不生效」。

逐行核对 MNN 源码后确认**这是错的**：

- `LlmConfig::LlmConfig` 用 `config_.merge(llm_config_)`（`llmconfig.hpp:94`）；
- `ujson::json::merge` 对两侧都是 object 的键是**递归深合并**（`ujson.hpp:292`）。

所以 `{"jinja":{"context":{"enable_thinking":false}}}` 会与模型自带的
`jinja.chat_template` **合并**而非替换。真正的病因是曾经把 `history` 数组传给
不走 chat template 的 `ResponseWithHistory()`，那个修复更早就落地了。

---

## 验证到什么程度

**本轮开发机没有连接 Android 设备**，所以：

- ✅ `gradle clean :app:assembleRelease` 通过（clean 全量重建）
- ✅ **59 个单元测试全通过、0 失败**
  - 其中 **15 个是本次新加的对话持久化测试**（`ConversationStoreTest`），
    直接测「应用退出后历史还在吗」这条链路：真实文件读写往返、
    用新实例重新打开模拟进程重启、损坏文件不抛异常且不被删除、
    超上限裁剪、`save` 自动建父目录、`clear` 真的删文件
  - 另有 13 个 JSON 解析、14 个记忆向量、31 个仓库/链接解析测试
- ✅ `aapt2 dump badging` 核对包名 / 版本 / ABI
- ✅ `apksigner verify --print-certs` 核对签名
- ✅ 反查 dex 确认新代码确实打进包里（`conversation.json` / 恢复提示字符串）
- ❌ **没有真机运行验证**（界面恢复、磁盘路径、权限都需要你在设备上确认）

---

## 已知问题（未变）

| 问题 | 状态 |
|---|---|
| 本地 LLM 生成质量 | 修复已落地，**未真机验证** |
| 文生图 | 未接入，`UnavailableDiffusionEngine` 占位 |
| 端侧模型转换 | 官方转换器已编进 APK，未跑过完整流程 |
| 语音 STT / TTS | sherpa-mnn JNI 已接入，未实机验证 |
| 会话管理 | 目前只有**一条**对话：退出恢复、新话题清空。**没有**多会话列表 / 按会话切换 |
| 上下文长度上限 | 200 条消息的裁剪**不按 token 数**。核对了 MNN 源码：这一版里 `max_all_tokens`（默认 2048）只是被 `Sampler` 收进配置（`sampler.cpp:241`），**从未被真正用于截断**；上下文实际受模型自带 `max_position_embeddings` 约束。所以如果单轮粘贴超长文档，仍可能超窗口 —— 这一版没有做超长保护 |

## 刻意的取舍（不是 bug）

- `usesCleartextTraffic="true"` —— 为了让局域网 MCP 服务端
  （`http://192.168.x.x`）能连上。`network-security-config` 不支持 CIDR，
  没法只放行私有网段。
- API Key 明文存储 —— 在应用私有目录 `files/api_providers.json`，
  仅本机可读，没有加密。
- 对话历史明文存储 —— 同上，存在应用外部私有目录
  `files/chat/conversation.json`。卸载即清理。

---

## 反馈

对话历史相关的日志 tag：`ConversationStore`。
生成链路：`adb logcat -s LocalAI-Gen LocalAI-Api ConversationStore MnnLlmEngine`
