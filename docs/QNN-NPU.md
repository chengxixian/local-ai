# QNN / NPU（高通 HTP）接入说明

> **当前发布状态（0.5.0-beta2）**：本工程**发布包不含 QNN**，只提供 CPU / GPU(OpenCL/Vulkan)。
> 原因与结论都记录在下面：NPU 通路本身已在真机验证可执行（第 5 节 `htpGraphSuccess=1`），
> 但要跑 LLM 必须先自己做一次 QNN 离线导出（第 4 节），而这一步依赖需要接受许可协议的
> 高通 QAIRT SDK host 转换器。应用侧的 QNN 选项、探测与探针代码已回退，未随包发布。

> 本文件只记录**真机实测过的事实**。凡是「配置里选了 qnn」不等于「算子在 NPU 上执行」，
> 这里一律以 QNN `graphExecute` 的实际返回以及 MNN 的计数为准。

## 1. 设备侧运行时（实测，无需下载 SDK）

测试机 `ca637ff6`：Qualcomm **SM8750**（骁龙 8 Elite），Hexagon **v79**，Android API 37。

该机**自带**一套版本一致的 QNN 运行时，应用可直接 `dlopen`，因此本工程**不打包、不分发**
任何高通运行库：

| 文件 | 设备路径 | SELinux 标签 |
| --- | --- | --- |
| `libQnnHtp.so` | `/system_ext/lib64/litert/` | `system_lib_file` |
| `libQnnHtpPrepare.so` | `/system_ext/lib64/litert/` | `system_lib_file` |
| `libQnnHtpV79Stub.so` | `/system_ext/lib64/litert/` | `system_lib_file` |
| `libQnnHtpV79Skel.so` | `/system_ext/lib64/litert/` 与 `/odm/lib/rfsa/adsp/` | `system_lib_file` / `same_process_hal_file` |
| `libQnnSystem.so` | `/system_ext/lib64/litert/` | `system_lib_file` |
| `libcdsprpc.so`（FastRPC） | `/vendor/lib64/` | `same_process_hal_file`，且在 `public.libraries.txt` 中 |

要点：

- `/system_ext/${LIB}` 同时位于应用默认链接命名空间的 **search.paths** 与 **permitted.paths**，
  所以按绝对路径 `System.load` 这些库是允许的；`libcdsprpc.so` 在 `public.libraries.txt` 里，
  `libQnnHtpV79Stub.so` 的 `DT_NEEDED` 能按名字解析到。
- `libQnnHtp.so` 的 `RUNPATH` 是 `$ORIGIN`，会从**它自己所在目录**再去加载
  `libQnnHtpPrepare.so`，所以这几个库必须放在同一目录使用。
- DSP 侧 skeleton 走 FastRPC 的 `ADSP_LIBRARY_PATH`（默认搜索路径已含 `/odm/lib/rfsa/adsp`）；
  **绝对不要** `System.load` skeleton —— 它是 Hexagon ELF，不是 ARM 库。
- 设备 QNN 运行时版本 **2.39.0**；本工程编译 MNN 用的头文件是 **2.37.0（API 2.27）**。
  MNN 只要求 `major` 相同且运行时 `minor >= 编译期 minor`，因此 2.39 运行时可用。

## 2. 本工程的做法

- `app/src/main/java/com/mnnkit/app/llm/QnnModule.kt`：运行时按
  `/system_ext/priv-app/MiLiteRTServiceStub/lib/arm64` → `/system_ext/lib64/litert` → `/odm/lib64`
  依次探测，要求 `libQnnHtp.so` / `libQnnHtpV79Stub.so` / `libQnnSystem.so` 同时存在，
  未知 SoC **失败关闭**（不猜架构号）。加载顺序：`QnnSystem` → `QnnHtp` → `QnnHtpPrepare` → `QnnHtpV79Stub`。
- 计数证据在 MNN 侧：`include/MNN/ExecutionEvidence.h` 的
  `MNNQnnExecutionEvidenceRead()` 给出 `htpGraphSuccess / htpGraphFailed / dspGraphSuccess / dspGraphFailed`，
  只在**真正调用** QNN `graphExecute` 并返回成功后累加；同时 `MNNExecutionEvidenceRead()`
  给出按后端编号的调度计数（QNN = 16，OpenCL = 3，CPU = 0，CPU 扩展 = 13）。
- 证据采集是**按需开启**（运行时配置 `mnnkit_capture_execution_evidence=true`，仅硬件探针使用），
  因为采集会插入 OpenCL 队列同步，会拖慢推理。

## 3. 普通 MNN 模型不能跑 NPU

NPU（`qnn`）不是「把 `backend_type` 换成 `qnn`」就能用的开关。MNN 上游
`transformers/llm/export/npu/generate_llm_qnn.py` 的产物是一整套离线导出：

| 产物 | 来源（上游脚本） |
| --- | --- |
| `config_qnn.json` | `output_qnn()` 写到模型目录（`os.path.join(args.model, "config_qnn.json")`） |
| `qnn/llm.mnn` | `config_npu["llm_model"] = "qnn/" + model_name`（默认 `llm.mnn`） |
| `qnn/llm.mnn.weight` | `config_npu["llm_weight"] = model_name + ".weight"` |
| `qnn/graphllm*.bin` | `config["graph_name"] = "graphllm"`，由 `npu_convert.py` 产出的 QNN context binary |
| `chunk_limits = [chunk_size, 1]` | 离线导出写死，缺它就说明不是 QNN 配置 |

执行时 MNN 把 `EXTERNAL_NPU_FILE_DIR` 指向模型目录，QNN 插件算子按 `qnn/<path>` 找 context binary。

所以：**普通下载模型的 `config.json` 永远不能被改成 QNN**。本工程只做只读识别
（`core/src/main/kotlin/com/mnnkit/core/model/QnnModelConfig.kt`），缺产物时明确报出缺哪个文件，
且**不会**静默回退到 CPU。

## 4. 自己导出一个 QNN（NPU）LLM

需要一个 QNN 转换器环境（x86_64 Linux + Qualcomm QAIRT/QNN SDK）：

```bash
source $QAIRT/bin/envsetup.sh
# 1) 导出 NPU 版 MNN（量化）：--generate_for_npu --quant_bit 4 --act_bit=16 --sym --smooth --hqq
python3 transformers/llm/export/llmexport.py --path <HF 模型> --export mnn \
        --dst_path <out> --generate_for_npu --quant_bit 4 --act_bit=16 --sym --smooth --hqq
# 2) MNN 图 → QNN 预编译离线图（SM8750 => soc_id 69 / dsp_arch v79）
python3 transformers/llm/export/npu/generate_llm_qnn.py --model <out> --soc_id 69 --dsp_arch v79
# 3) 把 <out>（含 config_qnn.json 与 qnn/ 目录）放进设备模型目录，应用里选 NPU (QNN)
```

注意：

- `--soc_id` / `--dsp_arch` 必须与真机一致（SM8750 → `69` / `v79`；SM8650 → `57` / `v75`；
  SM8550 → `43` / `v73`）。脚本注释里写明「8gen3 是 57 / v75」。
- 官方 MNN 模型库（ModelScope / HuggingFace 的 `taobao-mnn/*`）**没有** NPU 预转换版本，
  必须自己转换。
- Qualcomm 完整 SDK（含 `libQnnHtpPrepare.so`、`qnn-onnx-converter`、`qnn-context-binary-generator`
  等 host 工具）需要接受其许可协议后才能下载，本工程不代为下载。

## 5. 真机实测结果（ca637ff6）

用一个独立的 MNN+QNN 验证程序（源码 `_archive/qnn-probe/qnn_verify.cpp`，用工程里同一个
`libMNN.so` 交叉编译）在真机上跑通了 **HTP 图执行**：

```
MNN_QNN: Loaded HTP backend.
MNN_QNN: Using HTP backend, DSP architecture v79.
MNN_QNN: NHWC host input staging is fused into the QNN graph for t0 [1,16,32,32].
MNN_QNN: NHWC host output staging is fused into the QNN graph for t2 [1,16,32,32].
RUN_OK forwardType=16 outputElements=16384 finite=16384 checksum=1023.348846
QNN_EVIDENCE htpGraphSuccess=1 htpGraphFailed=0 dspGraphSuccess=0 dspGraphFailed=0
PIPELINE backend=16 successful=2 completed=0 unconfirmed=2 syncFailed=0 copies=0 backendErrors=0
VERDICT htp_graph_execute_confirmed
```

要点：

- `htpGraphSuccess=1` 是 QNN `graphExecute` **真的返回成功**才累加的计数，因此这一次推理确实
  在 Hexagon v79（NPU）上执行；`forwardType=16` 就是 `MNN_FORWARD_QNN`。
- 输出 16384 个值全部有限，且「ReLU + 沿 16 通道 Softmax」的校验和 1023.35 ≈ 1024
  （1024 个空间位置各通道和为 1），说明结果不是垃圾数据。
- `PIPELINE backend=16 ... completed=0 unconfirmed=2` 是刻意设计的诚实分类：QNN 的后端
  同步路径不能像 OpenCL 那样证明每个算子完成，所以算子级只记 `unconfirmed`，
  真正的证据放在 `htpGraphSuccess`。

### 必须踩对的坑：DSP skeleton 必须与 userspace 运行库同版本

第一次运行 `deviceCreate` 失败，报 `error:1008`。按 `QnnCommon.h`：
`1008 = QNN_MIN_ERROR_COMMON + 8 = QNN_COMMON_ERROR_INCOMPATIBLE_BINARIES`。
原因是 `ADSP_LIBRARY_PATH` 指到了厂商的旧版
`/odm/lib/rfsa/adsp/libQnnHtpV79Skel.so`，而 userspace 用的是 LiteRT 的 2.39
`libQnnHtp.so` —— 两边不是同一次构建。把 `ADSP_LIBRARY_PATH` 指向
`/system_ext/lib64/litert`（与 2.39 配套的 skeleton）后设备立即创建成功。
**所以这两个环境变量必须成对设置**：

```bash
LD_LIBRARY_PATH=/system_ext/lib64/litert   # libQnnHtp / libQnnHtpPrepare / Stub / System
ADSP_LIBRARY_PATH=/system_ext/lib64/litert # 与之配套的 v79 skeleton
```

### 已知限制

- 用 MNN Express 现搭的 3x3 卷积图会被 HTP 校验拒绝
  （`validateNativeOps ... Conv2d failed 3110`，3110 = `QNN_OP_PACKAGE_ERROR_VALIDATION_FAILURE`），
  而 `Relu` + `Softmax` 这种无权重图正常执行。转换器（MNNConvert / llmexport）产出的模型
  带完整量化参数，是 QNN 期望的输入形态；**结论：QNN 后端要用转换器产物，不要用临时拼的图**。
- LLM 走 NPU 依旧需要第 4 节那套离线导出（host QAIRT SDK）。

## 6. 与 NNAPI 的区别

这台机器的 NNAPI 只枚举到一个 `nnapi-reference`（CPU）驱动，**没有 NPU 驱动**：

```
NNAPI status=0 device_count=1
device=0 status=0 type=2 name=nnapi-reference version=OS4.0.0.16.XOACNXM
```

`type=2` 是 `ANEURALNETWORKS_DEVICE_CPU`。所以 Android 通用 NPU 路径（`MNN_FORWARD_NN` /
`backend_type: "npu"`）在本机不可能加速，本工程不提供该选项，只提供 Qualcomm QNN。
