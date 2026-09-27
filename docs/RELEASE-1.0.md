# Local AI 1.0 正式版发布说明

> 本文件与 GitHub Release `v1.0` 的说明保持一致。

**正式版 1.0**。本版把每个页面的顶栏（额头）改成与底栏一致的**液态玻璃浮层**：顶栏移出 Scaffold 槽位，作为采集层之外的兄弟节点浮在页面之上，页面因此从 y=0 起画，滚动时内容从玻璃**下面穿过**，玻璃才有真实的模糊与折射；7 个页面的列表顶部内边距按量测到的顶栏高度补足，首项不会被玻璃永久盖住。

玻璃结构严格遵循「修饰符挂在空 Box 上、内容做兄弟」的约束，避免自引用导致渲染树递归闪退（本机实测过该崩溃：RenderThread 栈溢出、黑屏约 1 秒即闪退）。顶栏的填充/颗粒/折射参数与底栏 dock 完全一致，两种材质是同一块玻璃。

## 真机实测（ca637ff6 / SM8750 / Android 17）

- **逐算子执行证据**（隔离探针按需开启，正常 App 恒关闭）：CPU 后端 24,675 条 CPU 扩展调度同步完成；OpenCL 后端 25,150 条 GPU 调度并通过队列同步，execution_backend_verified=true。
- ⚠️ 本机 0.8B 模型 **CPU(83 tok/s) 比 OpenCL(37 tok/s) 更快**；且开启证据采集会给每次 OpenCL Pipeline 末尾插入阻塞 finish()，**采集期间的吞吐不能当性能数据**。
- 顶栏玻璃：7 个页面全部切换无崩溃（logcat 无 FATAL，无 RenderThread 栈溢出）。
- 升级方式：adb install -r / 直接装 APK，**不卸载、不清数据**；实测对话与模型均在。

## 已知限制

- **QNN/NPU 未随本版发布**（应用侧 QNN 选项与探针代码已回退，只提供 CPU/OpenCL/Vulkan）。NPU 通路本身已在真机验证可执行：MNN 的 QNN 后端在 Hexagon v79 上 graphExecute 成功（htpGraphSuccess=1）。
- 让 LLM 跑在 NPU 上必须先用高通 QAIRT SDK 的 host 转换器做一次离线导出（config_qnn.json + qnn/llm.mnn(.weight) + qnn/graphllm*.bin，soc_id 69 / dsp_arch v79）。该 SDK 需接受其许可协议，本版未下载；完整步骤与实测坑（ADSP_LIBRARY_PATH 必须与 userspace 运行库同目录，否则 deviceCreate 报 1008 = QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES）见仓库 docs/QNN-NPU.md。
- 请勿把「设置里选了某个后端」当作硬件执行证据。

## 测试

core 单元测试通过；release APK v2 签名验证通过；adb install -r 保留数据升级验证通过。源码位于 `release/v0.5.0-beta2` 分支；未覆盖 main。

SHA-256: F3F9948371E1274BED65285D75D40AC4FCAE3B3048426F1B48871DE6709CFFA5

