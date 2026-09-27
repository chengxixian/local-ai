# Local AI

**完全离线的端侧 AI 应用**：LLM 对话 / 语音识别 / 语音合成 / 文生图 / Skill 库 / MCP 客户端 / 记忆库 / 端侧模型转换。

推理走 [MNN](https://github.com/alibaba/MNN)。模型**不随 APK 分发** —— 设计上就是让用户在应用内的「模型商店」自己下载，可选 HuggingFace 或魔搭的镜像。

前端（液态玻璃 + Flutter 风格）已抽成独立开源库：[**liquid-miuix**](https://github.com/chengxixian/liquid-miuix)。

---

## 项目状态：**开发测试中**

这个项目正在积极开发和真机测试。核心链路（模型商店、下载、Skill、MCP、记忆库）已经跑通；
对话链路还在调试中，目前**不建议用于日常使用**。

下面把「已跑通」和「调试中」分开列，是为了让参与开发的人清楚当前进度，
不是在宣布项目完成或停滞。

### 已跑通的

| 功能 | 状态 | 说明 |
|---|---|---|
| 模型商店 | ✅ | 三个数据源（MNN 官方清单 / HuggingFace `taobao-mnn` / 魔搭搜索） |
| 模型下载 | ✅ | 断点续传、镜像改写、进度回调；实测下载 522 MB 模型成功 |
| Skill 库 | ✅ | 内置索引 + GitHub 安装 + 启用停用 + 注入对话系统提示词 |
| MCP 客户端 | ✅ | JSON-RPC 2.0 over Streamable HTTP，**同时处理 `application/json` 与 `text/event-stream`** |
| 记忆库 | ✅ | SQLite FTS4（CJK 按字+bigram 分词）+ 可选向量检索 |
| 动态取色 | ✅ | Monet，跟随壁纸 |
| 液态玻璃 dock | ✅ | 含 lens 折射、progressive blur、颗粒 |
| DeepSeek API 调用 | ✅ | 设备上实测通过（含流式 SSE、`thinking` 参数、`reasoning_content`） |

### 调试中的

| 问题 | 当前症状 | 已经查到的线索 |
|---|---|---|
| 本地 LLM 输出 | 只出 1 个 token，`decode_len=1`，模型立刻吐 `<eop>` | `enable_thinking` 开关经 extra config 传**不生效**（MNN 的 `LlmConfig` 是浅合并，`jinja` 被 `llm_config.json` 整体覆盖）；模型自带的模板在关闭思考时会插入**空 `<think></think>` 块**，社区报告这会让 Qwen3.5 直接结束生成 |
| 对话界面渲染 | 请求已发出、服务端有响应，界面空白 | 日志显示「生成开始：路径=API」之后**没有任何 token 日志**；待排查 SSE 读取 / 协程缓冲 / 重组链路 |
| 文生图 | 未接入 | 官方 diffusion JNI 尚未移植，`UnavailableDiffusionEngine` 占位 |
| 端侧模型转换 | 未实机验证 | 官方纯 C++ 转换器（ONNX/TFLite/Caffe/TorchScript）已编进 APK，还没跑过完整流程 |
| 语音 STT / TTS | 未实机验证 | sherpa-mnn JNI 已接入（94 个导出符号），还没跑过 |

**开发笔记**：`docs/DEBUGGING.md`（若有）记录了完整的排查过程，**包括已经排除掉的方向** ——
那些看着可疑但实测无辜的地方，能省下重复排查的时间。

---

## 架构

```
mnnkit/
├── core/          纯 Kotlin，零第三方依赖（见下）
│   ├── json/      手写 JSON 解析器（Json.kt）
│   ├── net/       Http（基于 HttpURLConnection）+ Sse（SSE 分帧）
│   ├── model/     模型目录、下载、状态
│   ├── chat/      LlmEngine 接口 + 消息类型
│   ├── speech/    SttEngine / TtsEngine 接口
│   ├── mcp/       JSON-RPC 2.0 协议 + 配置解析 + 客户端
│   └── remote/    HuggingFace / 魔搭 / MNN 官方清单客户端
│
└── app/           Android 应用
    ├── cpp/       JNI：mnnkitbridge（LLM）+ mnnkitconvert（模型转换）
    ├── llm/       MnnLlmSession（JNI 封装）+ MnnLlmEngine（LlmEngine 实现）
    ├── data/      设置、模型管理、记忆、Skill、MCP、API 提供商
    ├── speech/    sherpa-mnn 引擎 + SpeechRouter（本地/API 双路）
    ├── ui/        Compose 界面（玻璃、dock、各页面）
    └── util/      媒体导出（MediaStore）
```

### 为什么 core 零第三方依赖

**刻意的**，不是疏忽。五条理由：

1. **端侧应用的体积敏感** —— 一个 OkHttp 就 700 KB，而这个 APK 本体才 8.7 MB。
2. **避免依赖冲突** —— miuix 会重写 `material3` 版本（`1.3.1 → 1.5.0-alpha22`），依赖越多这类问题越难查。
3. **JSON 需求很窄** —— 只需要解析不改写，手写 200 行比引 kotlinx-serialization + 编译器插件划算。
4. **HTTP 需求也很窄** —— 只需要 GET/POST/下载/SSE，`HttpURLConnection` 够用。
5. **可审计** —— 安全敏感的应用（模型、密钥、网络）里，少一个依赖少一份风险。

代价是代码量大一些，但每个文件都短且可读。

---

## 构建

### 前置

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17+ | |
| Android SDK | compileSdk 37 | miuix 0.9.4 要求 |
| Android NDK | 28.2.13676358 | 与预编译 `libMNN.so` 的工具链一致 |
| Gradle | 9.4.1 | AGP 9 要求 |
| MNN 源码 | master | 提供头文件 + 用于构建 `libMNN.so` |

### 关键：原生库需要自己构建

`app/src/main/jniLibs/arm64-v8a/` 下的这几个 `.so` **不在仓库里**（体积大且与 MNN 版本强绑定），需要自行构建：

```
libMNN.so               MNN 推理引擎（含 LLM）
libMNNConvertDeps.so    MNN 模型转换器
libc++_shared.so        STL 共享库
libsherpa-mnn-jni.so    sherpa-mnn 语音 JNI
libmnnkitbridge.so      ← 本仓库构建（LLM JNI）
libmnnkitconvert.so     ← 本仓库构建（转换器 JNI）
```

构建 MNN 的核心命令（完整脚本见 `scripts/build-mnn-android.ps1`）：

```bash
cmake -DMNN_BUILD_LLM=ON \
      -DMNN_LOW_MEMORY=ON \
      -DMNN_SUPPORT_TRANSFORMER_FUSE=ON \
      -DMNN_BUILD_FOR_ANDROID_COMMAND=true \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-26 \
      -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
      ..
```

> `MNN_BUILD_LLM=ON` 会**自动强制**打开 `MNN_LOW_MEMORY` 和 `MNN_SUPPORT_TRANSFORMER_FUSE`，
> 不需要（也不应该）手动再传一遍。

### 路径配置

`local.properties` 需要指向你的 MNN 源码根：

```properties
sdk.dir=/path/to/android-sdk
```

`app/build.gradle.kts` 和 `app/src/main/cpp/CMakeLists.txt` 里的 `MNN_SOURCE_ROOT`
也要改成你的 MNN 源码路径。

### 构建

```bash
./gradlew :app:assembleRelease
```

---

## 深色模式文字可见性：一段值得记录的坑

这个项目在主题上踩了一串坑，写下来免得后来者重蹈：

**本项目是 miuix + Material3 双层主题**：

- miuix 的 `Colors` 由 `ThemeController` **内部构造**，外部**无法覆盖它的属性**；
- `MnnTheme` 只是把它**映射**成 Material3 的 `ColorScheme`。

由此产生三个陷阱：

1. **改 Material3 映射影响不到 miuix 组件**。`MnnTheme.toMaterialScheme()` 里怎么调，
   `MiuixTheme.colorScheme.*` 的消费者（miuix 的 `Text` / `Button` / 卡片）一概不受影响。
2. **`Button` 会覆盖子内容的文字色**。写
   `Button(colors = buttonColorsPrimary()) { Text("x", color = Y) }` 时 **`Y` 不生效**。
3. **`buttonColorsPrimary()` 的配色对比度可能不足**（实测「浅蓝底 + 灰字」）。

**结论**：需要保证可读性的关键文字/按钮，**显式指定颜色**，不要依赖任何一层的语义色继承。
参考 `ui/theme/MnnTextColor.kt` 与 `ui/screens/MnnButtons.kt`。

---

## 已知限制

- **`usesCleartextTraffic="true"`**：为了让局域网 MCP 服务端（`http://192.168.x.x`）能连上。
  `network-security-config` **不支持 CIDR**，无法只放行私有网段，只能全量放行。代价是公网明文也放开了。
- **API Key 明文存储**：存在应用私有目录 `files/api_providers.json`，仅本机可读。
  没有做加密 —— 如果在意，请自行接 `EncryptedSharedPreferences`。
- **模型转换支持的范围**：MNN 的 `tools/converter` 只支持
  ONNX / TFLite / Caffe / TorchScript / TF pb。
  **safetensors / .bin / gguf 不能转** —— 需要先用 Python 的 `mnnconvert` 或 `optimum` 转成 ONNX。

---

## 许可证

Apache-2.0（与 MNN 保持一致）。

第三方组件的许可见 `THIRD-PARTY.md`。

---

## 致谢

- [alibaba/MNN](https://github.com/alibaba/MNN) —— 推理引擎与官方 Android 应用（本项目的 JNI 层大量参考了 `apps/Android/MnnLlmChat`）
- [k2-fsa/sherpa-mnn](https://github.com/k2-fsa/sherpa-mnn) —— 语音
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) —— 组件库
- [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) —— 液态玻璃折射算法
