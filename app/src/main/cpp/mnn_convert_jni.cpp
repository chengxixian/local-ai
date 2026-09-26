// ---------------------------------------------------------------------------
//  mnn_convert_jni.cpp
//
//  在 Android 设备上把模型转换为 MNN (.mnn) 格式的 JNI 桥接层。
//
//  为什么这条路可行：
//    MNN 的转换器（tools/converter）是**纯 C++**实现，不依赖 Python / PyTorch /
//    ONNX Runtime —— `tools/converter/CMakeLists.txt` 里没有任何 python 或 pybind
//    引用。因此它可以被交叉编译进 APK，在手机上直接跑。
//    （对比之下，LLM 的 llmexport.py 第一行就 import torch，那条路无法端侧运行。）
//
//  可本地转换的格式（均为纯 C++ 后端）：
//    ONNX / TFLite / Caffe / TorchScript
//
//  对外暴露：com.mnnkit.app.convert.NativeConverter
// ---------------------------------------------------------------------------

#include <jni.h>

#include <android/log.h>

#include <cstdio>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

#include "cli.hpp"
#include "config.hpp"

#define LOG_TAG "MnnConvertJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::mutex g_mutex;

/// 把 Java 的 jstring 转成 std::string（含空值保护）。
std::string toStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return std::string();
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) {
        return std::string();
    }
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

/// 把 MODEL_SOURCE 的字符串名映射到 MNN 的枚举。
/// 只接受纯 C++ 后端；返回 false 表示该格式无法在端侧转换。
bool parseSourceType(const std::string& name, modelConfig::MODEL_SOURCE* out, std::string* reason) {
    if (name == "onnx") {
        *out = modelConfig::ONNX;
        return true;
    }
    if (name == "tflite") {
        *out = modelConfig::TFLITE;
        return true;
    }
    if (name == "caffe") {
        *out = modelConfig::CAFFE;
        return true;
    }
    if (name == "torch" || name == "torchscript") {
        *out = modelConfig::TORCH;
        return true;
    }
    if (name == "tensorflow" || name == "tf") {
        *out = modelConfig::TENSORFLOW;
        return true;
    }
    if (name == "mnn") {
        *out = modelConfig::MNN;
        return true;
    }
    if (name == "safetensors" || name == "bin" || name == "gguf") {
        *reason = "该格式（" + name +
                  "）无法在手机上转换：MNN 对这类权重需要先用 PyTorch 追踪出计算图，"
                  "而端侧无法运行 PyTorch。请改用已经是 .mnn 的模型，或使用电脑端转换服务。";
        return false;
    }
    *reason = "不支持的输入格式：" + name;
    return false;
}

/// 把 MNN 转换过程中的 stdout 输出收集起来返回给 Kotlin，便于界面展示日志。
class StdoutCapture {
public:
    explicit StdoutCapture(std::string* sink) : mSink(sink) {
        mOld = std::cout.rdbuf(mStream.rdbuf());
        mOldErr = std::cerr.rdbuf(mStream.rdbuf());
        mActive = true;
    }

    ~StdoutCapture() {
        if (mActive) {
            std::cout.rdbuf(mOld);
            std::cerr.rdbuf(mOldErr);
        }
        if (mSink != nullptr) {
            *mSink = mStream.str();
        }
    }

private:
    std::string* mSink;
    std::ostringstream mStream;
    std::streambuf* mOld = nullptr;
    std::streambuf* mOldErr = nullptr;
    bool mActive = false;
};

/// 转换结果，回传给 Kotlin。
struct ConvertOutcome {
    bool ok = false;
    std::string outputPath;
    std::string log;
    std::string error;
};

/// 执行一次转换。
ConvertOutcome runConvert(const std::string& inputPath,
                          const std::string& outputPath,
                          const std::string& formatName,
                          int quantBits,
                          int quantBlock,
                          bool saveHalfFloat,
                          bool transformerFuse) {
    ConvertOutcome outcome;

    modelConfig::MODEL_SOURCE source = modelConfig::ONNX;
    std::string reason;
    if (!parseSourceType(formatName, &source, &reason)) {
        outcome.error = reason;
        return outcome;
    }

    const std::string log = std::string("input=") + inputPath + "\noutput=" + outputPath +
                            "\nformat=" + formatName + "\nquantBits=" + std::to_string(quantBits) +
                            "\nquantBlock=" + std::to_string(quantBlock) + "\n";
    outcome.log = log;

    {
        StdoutCapture capture(&outcome.log);
        try {
            modelConfig config;
            config.model = source;
            config.modelFile = inputPath;
            config.MNNModel = outputPath;
            config.bizCode = "MNN";
            config.saveHalfFloat = saveHalfFloat;
            config.saveStaticModel = false;
            // 量化：0 表示不做权重量化
            config.weightQuantBits = quantBits;
            config.weightQuantBlock = quantBlock > 0 ? quantBlock : -1;
            // 超出阈值的大权重写到外部 .weight 文件，避免单文件过大（与官方导出一致）
            config.saveExternalData = false;
            config.transformerFuse = transformerFuse;
            config.transformerFuseC4 = transformerFuse;
            config.alignDenormalizedValue = true;

            LOGI("convertModel begin: %s -> %s", inputPath.c_str(), outputPath.c_str());
            const bool ok = MNN::Cli::convertModel(config);
            LOGI("convertModel end: %s", ok ? "ok" : "failed");
            outcome.ok = ok;
            if (!ok) {
                outcome.error = "MNN 转换器返回失败，请查看日志（模型可能使用了不支持的自定义算子）";
            }
        } catch (const std::exception& e) {
            outcome.ok = false;
            outcome.error = std::string("转换抛出异常：") + e.what();
        } catch (...) {
            outcome.ok = false;
            outcome.error = "转换抛出未知异常";
        }
    }

    if (outcome.ok) {
        // 校验产物确实生成
        FILE* f = std::fopen(outputPath.c_str(), "rb");
        if (f == nullptr) {
            outcome.ok = false;
            outcome.error = "转换器返回成功，但没有找到输出文件：" + outputPath;
        } else {
            std::fseek(f, 0, SEEK_END);
            const long size = std::ftell(f);
            std::fclose(f);
            if (size <= 0) {
                outcome.ok = false;
                outcome.error = "输出文件为空：" + outputPath;
            } else {
                outcome.log += "\noutputBytes=" + std::to_string(size) + "\n";
            }
        }
    }
    return outcome;
}

}  // namespace

extern "C" {

/// 转换器是否可用（原生库加载成功即视为可用）。
JNIEXPORT jboolean JNICALL
Java_com_mnnkit_app_convert_NativeConverter_nativeIsAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}

/// 返回该格式能否在端侧转换，不能则给出原因。
JNIEXPORT jstring JNICALL
Java_com_mnnkit_app_convert_NativeConverter_nativeCheckFormat(JNIEnv* env, jclass, jstring format) {
    const std::string name = toStdString(env, format);
    modelConfig::MODEL_SOURCE src = modelConfig::ONNX;
    std::string reason;
    if (parseSourceType(name, &src, &reason)) {
        return nullptr;  // 可用
    }
    return env->NewStringUTF(reason.c_str());
}

/**
 * 执行转换。
 *
 * @return 长度 >= 3 的字符串数组：
 *         [0] "1" 成功 / "0" 失败
 *         [1] 成功时为输出文件路径，失败时为空
 *         [2] 转换日志
 *         [3] 失败时的错误信息
 */
JNIEXPORT jobjectArray JNICALL
Java_com_mnnkit_app_convert_NativeConverter_nativeConvert(JNIEnv* env,
                                                          jclass,
                                                          jstring inputPath,
                                                          jstring outputPath,
                                                          jstring format,
                                                          jint quantBits,
                                                          jint quantBlock,
                                                          jboolean saveHalfFloat,
                                                          jboolean transformerFuse) {
    const std::string in = toStdString(env, inputPath);
    const std::string out = toStdString(env, outputPath);
    const std::string fmt = toStdString(env, format);

    ConvertOutcome outcome;
    if (in.empty() || out.empty()) {
        outcome.error = "输入或输出路径为空";
    } else {
        // MNN 转换器内部不是线程安全的（共享 pass manager 与全局状态），必须串行化
        std::lock_guard<std::mutex> guard(g_mutex);
        outcome = runConvert(in, out, fmt, quantBits, quantBlock,
                             saveHalfFloat == JNI_TRUE, transformerFuse == JNI_TRUE);
    }

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(4, stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    env->SetObjectArrayElement(result, 0, env->NewStringUTF(outcome.ok ? "1" : "0"));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(outcome.ok ? out.c_str() : ""));
    env->SetObjectArrayElement(result, 2, env->NewStringUTF(outcome.log.c_str()));
    env->SetObjectArrayElement(result, 3, env->NewStringUTF(outcome.error.c_str()));
    return result;
}

/// mnn2json：把 .mnn 反解成可读 JSON，用于排查模型结构。
JNIEXPORT jboolean JNICALL
Java_com_mnnkit_app_convert_NativeConverter_nativeMnn2Json(JNIEnv* env,
                                                           jclass,
                                                           jstring mnnFile,
                                                           jstring jsonFile) {
    const std::string src = toStdString(env, mnnFile);
    const std::string dst = toStdString(env, jsonFile);
    if (src.empty() || dst.empty()) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> guard(g_mutex);
    std::string sink;
    StdoutCapture capture(&sink);
    return MNN::Cli::mnn2json(src.c_str(), dst.c_str(), 3) ? JNI_TRUE : JNI_FALSE;
}

}  // extern "C"
