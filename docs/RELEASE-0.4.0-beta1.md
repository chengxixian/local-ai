# Local AI v0.4.0-beta1

> **测试版**。核心链路（模型商店 / 下载 / Skill / MCP / 记忆库 / 液态玻璃）已跑通，
> 对话链路修了三个阻塞性 bug 并补上了历史持久化，但**都还没有在真机上验证过**
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

测试包的签名密钥一直没变，所以从 0.2.0 / 0.3.0 升上来**可以直接覆盖安装**；
如果装的是 0.1.0 的包，需要先卸载。

---

## 本版新增：对话页右上角「新对话」按钮

### 为什么之前找不到这个入口

清空对话的功能其实早就有，但它**藏在「当前模型」卡片里面**，而且只在
本地模型已加载时才显示：

```kotlin
// 旧代码：只有本地模型才显示
if (state.loadedModelPath != null) {
    MnnCapsuleButton(text = "新话题", onClick = onReset)
}
```

用 **API 提供商**对话时 `loadedModelPath` 是 `null`（切到 API 会把本地模型
`unload()` 掉以释放几百 MB 内存），于是这个按钮根本不渲染 ——
表现就是「用 API 时找不到开新对话的地方」。

### 现在的行为

- 按钮放在**顶栏右上角**，标题「新对话」；
- 只要对话页**已有消息**就显示（本地 / API 两种模式都可见）；
- 没有消息时不显示 —— 没有对话可清的时候露一个按钮出来只会让人困惑；
- 点击后**先弹确认框**，确认后才清：

| 项目 | 说明 |
|---|---|
| 界面消息 | 清空 |
| **磁盘上的历史** | **一并删除**（`files/chat/conversation.json`） |
| 本地模型 KV cache | `resetContext()`（保留 system 轮） |
| 正在进行的生成 | 先取消 |

确认框的文案明确写出「会从本机清除，包括启动时自动恢复的历史记录。此操作不可撤销」——
只说「开始新对话」的话，用户不会意识到历史被删了，等发现时已经找不回来。

> ⚠️ 为什么必须连磁盘一起清：v0.3.0-beta1 加了「退出后自动恢复历史」，
> 如果只清界面不清磁盘，用户点完「新对话」再重启，旧对话又会被恢复回来，
> 看起来像「清不干净」。

同时**移除了**模型卡片里那个只在本地模型下出现的「新话题」按钮 ——
两处同名按钮容易让人以为功能不同。

---

## 沿用 v0.3.0-beta1：对话历史不再丢失

对话现在落盘到 `Android/data/com.mnnkit.app/files/chat/conversation.json`，
启动时自动恢复，并提示「已恢复上次的对话（N 轮）」。

原来 `messages` 只活在 Compose 的 `remember { mutableStateOf(...) }` 里，
进程一退（划掉、被系统回收、崩溃重启）整段上下文就没了。

> 注意与**记忆库**（SQLite FTS4）区分：记忆库存的是从对话里**提炼出来的
> 长期记忆**，用于检索注入，不负责恢复界面。

---

## 沿用 v0.2.0-beta1：两个阻塞性 bug 与一处错误结论修正

### 1. API 流式对话界面全白
`chatStream` 的请求写入与 SSE 逐行读取没有切线程，跑在
`AndroidUiDispatcher.Main` 上 → 主线程被阻塞，界面一个字不显示，
且**一条 token 日志都没有**。现已整段移入 `withContext(Dispatchers.IO)`。

### 2. 思考内容被当成正文
`delta.reasoning_content` 与 `delta.content` 原先进同一个累加器。
现已拆成 `ChatDelta.Content` / `ChatDelta.Reasoning`，
界面加可折叠的「思考过程」块（默认折叠）。

### 3. 纠正一处错误结论
「本地 LLM 只出 1 个 token 是因为 MNN 的 `LlmConfig` 浅合并、
`jinja` 被 `llm_config.json` 整体覆盖」——**这是错的**。
`ujson::json::merge` 对两侧都是 object 的键是**递归深合并**（`ujson.hpp:292`），
`enable_thinking` 确实能到达 jinja 模板。真正的病因是曾经把 `history` 数组
传给不走 chat template 的 `ResponseWithHistory()`。

---

## 验证到什么程度

**本轮开发机没有连接 Android 设备**，所以：

- ✅ `gradle clean :app:assembleRelease` 通过（clean 全量重建）
- ✅ **59 个单元测试全通过、0 失败**（含 15 个对话持久化测试：
  真实文件读写往返、模拟进程重启、损坏文件不删、裁剪、自动建目录、清空）
- ✅ `aapt2 dump badging` 核对包名 / 版本（`0.4.0-beta1`，versionCode 4）/ ABI
- ✅ `apksigner verify --print-certs` 核对签名
- ✅ 反查 dex 确认新代码打进包里（`新对话` / `开始新对话？` / 点击日志字符串）
- ✅ Release 资产下载回来做过 SHA256 逐字节比对（v0.2.0、v0.3.0 两个版本都是 True）
- ❌ **没有真机运行验证** —— 按钮位置、点击行为、确认框、历史恢复
  都需要你在设备上确认

---

## 已知问题

| 问题 | 状态 |
|---|---|
| 本地 LLM 生成质量 | 修复已落地，**未真机验证** |
| 文生图 | 未接入，`UnavailableDiffusionEngine` 占位 |
| 端侧模型转换 | 官方转换器已编进 APK，未跑过完整流程 |
| 语音 STT / TTS | sherpa-mnn JNI 已接入，未实机验证 |
| 会话管理 | 只有**一条**对话：退出恢复、新对话清空。**没有**多会话列表 / 按会话切换 |
| 上下文长度上限 | 200 条消息的裁剪**不按 token 数**。核对了 MNN 源码：这一版里 `max_all_tokens`（默认 2048）只是被 `Sampler` 收进配置（`sampler.cpp:241`），**从未被真正用于截断**；上下文实际受模型自带 `max_position_embeddings` 约束。单轮粘贴超长文档仍可能超窗口 |

## 刻意的取舍（不是 bug）

- `usesCleartextTraffic="true"` —— 为了让局域网 MCP 服务端（`http://192.168.x.x`）能连上。
  `network-security-config` 不支持 CIDR，没法只放行私有网段。
- API Key 明文存储 —— 应用私有目录 `files/api_providers.json`，仅本机可读，未加密。
- 对话历史明文存储 —— 同上，`files/chat/conversation.json`。卸载即清理。

---

## 反馈

```
adb logcat -s LocalAI-Gen LocalAI-Api ConversationStore MnnLlmEngine
```

点右上角「新对话」时，日志里会打印「顶栏「新对话」被点击」——
如果点了没反应，先看这行有没有出来，能直接区分「按钮没接上」还是「弹窗没显示」。
