# Local AI v0.2.0-beta1

> 这是**测试版**。核心链路（模型商店 / 下载 / Skill / MCP / 记忆库 / 液态玻璃）已跑通，
> 对话链路刚修掉两个阻塞性 bug，但**还没有在真机上验证过**（本轮开发机没有连接设备）。
> 请把它当作「愿意帮忙试错」的版本，不要用于日常。

推理后端是 [MNN](https://github.com/alibaba/MNN)。**模型不随 APK 分发** ——
装好后请在应用内的「模型商店」自己下载（可选 HuggingFace 或魔搭镜像）。

---

## 安装

- **ABI**：仅 `arm64-v8a`（64 位 ARM，近几年的手机基本都是）
- **最低系统**：Android 13（API 33）
- **体积**：8.74 MB（不含模型）
- **签名**：本地测试密钥 `CN=MNNKit`（**不是**正式发布密钥）
  - SHA-256：`dc5036bda119d38128d721b24cd915d5366511b88df507c8216cb7d52d8b7d58`

因为换了 `versionCode`（1 → 2）但签名密钥与 0.1.0 的测试包**不同**，
如果之前装过 0.1.0，需要先卸载再装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

---

## 本版修了什么

### 1. API 流式对话界面全白 —— 阻塞式网络读写跑在主线程上

**症状**：选了 API 提供商发消息，日志里只有「生成开始：路径=API(…)」，
之后**一条 token 日志都没有**，界面一个字不显示。而切回本地模型是正常的。

**根因**：`OpenAiCompatibleClient.chatStream` 里的请求体写入与 SSE 逐行读取**没有切线程**。

`callbackFlow` 的块是在**收集端的上下文**里执行的（结构化并发，不会自动切到 IO）。
调用点是 `AppRoot` 的 `generateJob = scope.launch { … }`，而
`rememberCoroutineScope()` 给的是 `AndroidUiDispatcher.Main` —— 于是
`conn.outputStream` 和 `reader.readLine()` **全在主线程上阻塞**。

之所以难查，是因为它同时解释了好几个看起来无关的现象：

| 观察到的现象 | 真实原因 |
|---|---|
| 请求已发出、服务端**有**响应 | 阻塞发生在写请求体/读响应，连接本身是建立成功的 |
| **没有任何 token 日志** | 不是「没收到数据」，而是读取循环压根没跑起来 |
| 界面**全白**、不逐字上屏 | 主线程被占住，`messages` 的状态写入既推不动重组也轮不到执行 |
| **只有 API 路径**坏，本地路径好 | 本地路径 `MnnLlmEngine.stream` 里显式写了 `withContext(Dispatchers.IO)` |

最后那条不对称，其实是最有力的线索 —— 两条路径唯一的差别就在这里。

**修复**：把整段请求 + 读取放进 `withContext(Dispatchers.IO)`，
并补上「上报片段数」的诊断日志，下次再出问题能一眼看出是「收到但没上报」
还是「根本没收到」。

### 2. 思考内容被当成正文显示

**症状**：跟推理模型（DeepSeek reasoner / flash、QwQ 等）对话时，回答里会夹进
一整段「让我想想……」的思考文字。

**根因**：`delta.reasoning_content` 和 `delta.content` 被 `trySend` 到**同一个流**，
上游累加进同一个 `StringBuilder`。

**修复**：新增 `ChatDelta` 密封接口，把两条流分开上报：

```kotlin
sealed interface ChatDelta {
    data class Content(val text: String) : ChatDelta
    data class Reasoning(val text: String) : ChatDelta
}
```

界面上新增可折叠的**「思考过程」**块：默认折叠、只显示一行预览，点标题展开；
正文还没开始时标题显示「思考中…」。
另外，「正文为空」的提示现在会区分两种情况 ——
「只有思考、正文被 `max_tokens` 截断」和「服务端真的什么都没返回」。

想要纯文本的调用方（例如「测试连接」）可以用新的 `chatStreamText()`。

### 3. 版本号

`versionCode 1 → 2`、`versionName 0.1.0 → 0.2.0-beta1`。
之前 27 个 APK 全都叫 `0.1.0`，从版本号上完全看不出哪个是哪个。

---

## 顺手修正的一处**错误结论**

仓库里（README + 代码注释）此前把本地 LLM「只出 1 个 token」的原因写成：

> `enable_thinking` 经 extra config 传**不生效** —— MNN 的 `LlmConfig` 是浅合并，
> `jinja` 被 `llm_config.json` 整体覆盖。

**这个结论是错的。** 逐行核对 MNN 源码后确认：

- `LlmConfig::LlmConfig` 用的是 `config_.merge(llm_config_)`
  （`transformers/llm/engine/src/llmconfig.hpp:94`）；
- `ujson::json::merge` 对**两侧都是 object** 的键是**递归深合并**
  （`ujson.hpp:292-302`）——

  ```cpp
  if (contains(key) && (*this)[key].is_object() && it.value().is_object()) {
      (*this)[key].merge(it.value());   // 递归下去
  } else {
      (*this)[key] = it.value();
  }
  ```

所以 `{"jinja":{"context":{"enable_thinking":false}}}` 会与模型自带的
`jinja.chat_template` / `jinja.context` **合并**，而不是替换掉 `jinja`。

下游链路也确认能到模板：`Llm::set_config` → `setChatTemplate()`
→ `Tokenizer::set_chat_template_context` → `apply_chat_template`
把 context 作为 `extra_context` 传给 jinja 渲染
（`llm.cpp:139-142`、`llm.cpp:113-137`、`tokenizer.cpp:1018-1054`、`jinja.hpp:2234`）。

「只出 1 个 token」的**真正**根因是另一件事：曾经把整段 `history` 数组传给原生
`ResponseWithHistory()`，而那条路径**不套 chat template**，模型收到的是没有
`<|im_start|>` / `<|im_end|>` 包装的裸文本，于是第一个 token 就吐 `<eop>`。
这个修复**已经在 0.1.0 的后期版本里落地**（见 `MnnLlmEngine.stream` 的长注释），
本版只是把注释里那个错误的病因改成正确说法，免得后人照着错误结论去改配置。

> ⚠️ 澄清一点：**本版没有改动任何模型配置文件**，也没有验证过本地 LLM 的实际生成质量。
> `ModelConfigPatcher` 保持「什么都不改」，只负责回滚早期版本留下的改动。

---

## 本次源码改动

5 个文件：

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/mnnkit/app/data/api/OpenAiCompatibleClient.kt` | IO 调度修复；`ChatDelta`；`chatStreamText()` |
| `app/src/main/java/com/mnnkit/app/ui/AppRoot.kt` | 分开累积思考与正文；区分两种「正文为空」 |
| `app/src/main/java/com/mnnkit/app/ui/screens/ChatScreen.kt` | 可折叠「思考过程」块 |
| `app/src/main/java/com/mnnkit/app/llm/MnnLlmSession.kt` | 修正 `enable_thinking` 的错误注释 |
| `app/build.gradle.kts` | 版本号 |

---

## 验证到什么程度

诚实说明 —— 本轮开发机**没有连接 Android 设备**，所以：

- ✅ `gradle clean :app:assembleRelease` 通过（clean 全量重建）
- ✅ `:core:test` 44 个测试全通过、0 失败
- ✅ APK 用 `aapt2 dump badging` 核对过包名 / 版本 / ABI
- ✅ APK 用 `apksigner verify --print-certs` 核对过签名
- ❌ **没有**真机运行验证（上面两个 bug 的修复效果需要你在设备上确认）

---

## 已知问题（未变）

| 问题 | 状态 |
|---|---|
| 本地 LLM 生成质量 | 修复已落地，但**未真机验证** |
| 文生图 | 未接入，`UnavailableDiffusionEngine` 占位 |
| 端侧模型转换 | 官方转换器已编进 APK，未跑过完整流程 |
| 语音 STT / TTS | sherpa-mnn JNI 已接入，未实机验证 |

另外两条**刻意**的取舍（不是 bug）：

- `usesCleartextTraffic="true"` —— 为了让局域网 MCP 服务端（`http://192.168.x.x`）
  能连上。`network-security-config` 不支持 CIDR，没法只放行私有网段。
- API Key 明文存储 —— 在应用私有目录 `files/api_providers.json`，仅本机可读，没有加密。

---

## 反馈

请附上日志（`adb logcat -s LocalAI-Api LocalAI-Gen MnnLlmEngine MnnLlmSession`），
尤其是走 API 路径时的 `chatStream 已连接` / `chatStream 结束：… 上报片段=N` 两行 ——
有它们才能区分「服务端没发数据」「我们读错了」「上报链路断了」。
