//
// mnn_llm_jni.cpp —— MNN LLM 的 JNI 桥接层（自研精简版）
//
// ---------------------------------------------------------------------------
// 本文件按 MNN 官方 Android LLM Demo 的 cpp/llm_mnn_jni.cpp 裁剪而成：
//
//   上游：<MNN_ROOT>/apps/Android/MnnLlmChat/app/src/main/cpp/llm_mnn_jni.cpp
//
// **逐字搬运**的上游片段：
//   * initNative      —— 上游 :91-141（去掉 Firebase 上报）
//   * submitNative    —— 上游 :144-226 的回调装配 + 线程模型，逐字一致：
//                        `jstring javaString = is_eop ? nullptr : env->NewStringUTF(...)`
//                        `return (bool) env->CallBooleanMethod(...)`
//   * resetNative     —— 上游 :389-397
//   * releaseNative   —— 上游 :460-467
//   * getDebugInfoNative / dumpConfigNative / getSystemPromptNative / clearHistoryNative
//                     —— 上游 :438-458 / :531-552
//   * updateMaxNewTokensNative / updateSystemPromptNative —— 上游 :469-491
//
// 🔴 线程模型与上游**完全一致**：**JNI 层不起任何线程**。submitNative 同步阻塞在
//    调用它的 Java 线程上，token 回调（CallBooleanMethod）也发生在同一个线程。
//    「后台」由 Kotlin 侧 Dispatchers.IO 提供。
//
// 裁剪：Firebase/Crashlytics 上报、setWavformCallbackNative（TTS）、
//   submitFullHistoryNative（API Server）、updateAssistantPromptNative、
//   updateConfigNative、updateEnableAudioOutputNative、runBenchmarkNative、
//   mnn_wrapper_jni / diffusion / sana / crash_util / processor / video。
//
// 新增（本工程自研）：
//   * Java 包名/类名改为 com.mnnkit.app.llm.MnnLlmSession，导出名相应改为
//     Java_com_mnnkit_app_llm_MnnLlmSession_*，且全部为 **static native**
//     （第二参数 jclass）。
//   * submitNative 增加第 4/5 个参数 roles[]/contents[]，用于传入**完整会话历史**
//     （对齐 com.mnnkit.core.chat.ChatMessage 列表）；同时返回完整回复文本，
//     便于「取消后仍保留已生成内容」。
//   * json::parse 包了 try/catch —— 上游未包，config JSON 非法会抛 C++ 异常
//     直接终止进程。
//   * 补上 ReleaseStringUTFChars / DeleteLocalRef（上游 submitNative 漏了）。
// ---------------------------------------------------------------------------
//
#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <sstream>
#include <utility>

#include "mls_log.h"
#include "nlohmann/json.hpp"
#include "llm_session.h"

using json = nlohmann::json;

namespace {

/** 把 Java String[] 里的每一项读成 std::string。 */
std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) {
        return out;
    }
    const jsize count = env->GetArrayLength(array);
    out.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto element = reinterpret_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (element == nullptr) {
            out.emplace_back();
            continue;
        }
        const char* chars = env->GetStringUTFChars(element, nullptr);
        if (chars != nullptr) {
            out.emplace_back(chars);
            env->ReleaseStringUTFChars(element, chars);
        }
        env->DeleteLocalRef(element);
    }
    return out;
}

bool throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass exClass = env->FindClass("java/lang/IllegalStateException");
    if (exClass != nullptr) {
        env->ThrowNew(exClass, message.c_str());
        env->DeleteLocalRef(exClass);
        return true;
    }
    return false;
}

/** 查找 TokenCallback.onToken(Ljava/lang/String;)Z。找不到会抛 NoSuchMethodError。 */
jmethodID findOnTokenMethod(JNIEnv* env, jobject listener) {
    if (listener == nullptr) {
        return nullptr;
    }
    jclass listenerClass = env->GetObjectClass(listener);
    if (listenerClass == nullptr) {
        return nullptr;
    }
    jmethodID method = env->GetMethodID(listenerClass, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(listenerClass);
    return method;
}

jobject makeStringLongHashMap(JNIEnv* env,
                              int64_t promptLen, int64_t decodeLen,
                              int64_t prefillUs, int64_t decodeUs,
                              int64_t visionUs, int64_t audioUs) {
    jclass hashMapClass = env->FindClass("java/util/HashMap");
    if (hashMapClass == nullptr) {
        return nullptr;
    }
    jmethodID hashMapInit = env->GetMethodID(hashMapClass, "<init>", "()V");
    jmethodID putMethod = env->GetMethodID(hashMapClass, "put",
                                           "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jclass longClass = env->FindClass("java/lang/Long");
    jmethodID longInit = longClass != nullptr
            ? env->GetMethodID(longClass, "<init>", "(J)V") : nullptr;
    if (hashMapInit == nullptr || putMethod == nullptr || longInit == nullptr || longClass == nullptr) {
        env->DeleteLocalRef(hashMapClass);
        if (longClass != nullptr) {
            env->DeleteLocalRef(longClass);
        }
        return nullptr;
    }
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit);
    if (hashMap == nullptr) {
        env->DeleteLocalRef(longClass);
        env->DeleteLocalRef(hashMapClass);
        return nullptr;
    }
    struct Entry { const char* key; int64_t value; };
    const Entry entries[] = {
            {"prompt_len", promptLen},
            {"decode_len", decodeLen},
            {"prefill_time", prefillUs},
            {"decode_time", decodeUs},
            {"vision_time", visionUs},
            {"audio_time", audioUs},
    };
    for (const auto& entry : entries) {
        jstring key = env->NewStringUTF(entry.key);
        jobject value = env->NewObject(longClass, longInit, static_cast<jlong>(entry.value));
        env->CallObjectMethod(hashMap, putMethod, key, value);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (value != nullptr) {
            env->DeleteLocalRef(value);
        }
        env->DeleteLocalRef(key);
    }
    env->DeleteLocalRef(longClass);
    env->DeleteLocalRef(hashMapClass);
    return hashMap;
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_DEBUG, "MNN_DEBUG", "mnnkitbridge JNI_OnLoad");
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_DEBUG, "MNN_DEBUG", "mnnkitbridge JNI_OnUnload");
}

/**
 * initNative(configPath, runtimeConfigJson) -> handle（失败返回 0 并抛 IllegalStateException）
 *
 * configPath          ：模型目录下 config.json 的**完整路径**（与上游 initNative 一致）
 * runtimeConfigJson   ：extra_config，形如
 *                       {"system_prompt":"...","max_new_tokens":512,"is_r1":false,
 *                        "keep_history":true,"mmap_dir":""}
 *   其中 system_prompt / max_new_tokens 会合并进传给 Llm::set_config 的 JSON；
 *   is_r1 / keep_history / mmap_dir 走 extra_config 语义（与上游一致）。
 */
JNIEXPORT jlong JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_initNative(JNIEnv* env, jobject thiz,
                                                 jstring configPath,
                                                 jstring runtimeConfigJson) {
    if (configPath == nullptr) {
        throwIllegalState(env, "configPath must not be null");
        return 0;
    }
    const char* config_path_chars = env->GetStringUTFChars(configPath, nullptr);
    if (config_path_chars == nullptr) {
        return 0;
    }
    std::string config_path(config_path_chars);
    env->ReleaseStringUTFChars(configPath, config_path_chars);

    std::string runtime_json_str = "{}";
    if (runtimeConfigJson != nullptr) {
        const char* runtime_chars = env->GetStringUTFChars(runtimeConfigJson, nullptr);
        if (runtime_chars != nullptr) {
            runtime_json_str = runtime_chars;
            env->ReleaseStringUTFChars(runtimeConfigJson, runtime_chars);
        }
    }

    json merged_config = json::object();
    json extra_json_config = json::object();
    // 上游此处未加 try/catch：JSON 非法会抛 C++ 异常直接终止进程。这里兜住。
    try {
        extra_json_config = json::parse(runtime_json_str);
        if (!extra_json_config.is_object()) {
            extra_json_config = json::object();
        }
    } catch (const std::exception& e) {
        throwIllegalState(env, std::string("invalid runtime config JSON: ") + e.what());
        return 0;
    }
    auto take_over = [&extra_json_config, &merged_config](const char* key) {
        if (extra_json_config.contains(key)) {
            merged_config[key] = extra_json_config[key];
            extra_json_config.erase(key);
        }
    };
    take_over("max_new_tokens");
    take_over("system_prompt");

    // `jinja` 必须也搬进 merged_config（= LlmSession 的 `config_`）。
    //
    // 依据 llm_session.cpp:177-182 —— 那一层**只从 `config_` 读配置**：
    //     system_prompt_ = config_.contains("system_prompt") ? ... : ...
    //     is_r1_         = extra_config_.contains("is_r1") && ...
    // 而 chat template 的 `enable_thinking` 取自 config_ 里的 `jinja.context`。
    // 若只把它留在 extra_config_，**覆盖不会生效**。真机日志可证：
    //     我们发的： "jinja":{"context":{"enable_thinking":false}}
    //     最终生效： "jinja":{"context":{"enable_thinking":true}}
    // 于是 Qwen3 仍进入思考模式，吐完思考标记就 <eop>，
    // 整轮只生成 1 个 token（PERF 日志 decode_len=1）。
    take_over("jinja");
    // Forward ALL engine overrides, not just prompt fields. Keeping backend_type
    // only in extra_config silently ignored CPU/OpenCL/Vulkan selections.
    // Wrapper-only options stay available to LlmSession's constructor.
    for (auto it = extra_json_config.begin(); it != extra_json_config.end(); ++it) {
        if (it.key() != "keep_history" && it.key() != "mmap_dir" &&
            it.key() != "backend_diagnostics") {
            merged_config[it.key()] = it.value();
        }
    }

    MNN_DEBUG("createLLM BeginLoad");
    auto* llm_session = new mls::LlmSession(config_path, merged_config, extra_json_config,
                                            std::vector<std::string>());
    bool load_success = llm_session->Load();
    MNN_DEBUG("LIFECYCLE: LlmSession CREATED at %p, load_success=%d",
              static_cast<void*>(llm_session), load_success);
    if (!load_success || !llm_session->isModelReady()) {
        std::string err_msg = llm_session->getLastLoadError();
        if (err_msg.empty()) {
            err_msg = "Model load failed for config: " + config_path;
        }
        MNN_DEBUG("Model load failed, cleaning up LlmSession: %s", err_msg.c_str());
        delete llm_session;
        throwIllegalState(env, err_msg);
        return 0;
    }
    MNN_DEBUG("createLLM EndLoad %ld", reinterpret_cast<jlong>(llm_session));
    return reinterpret_cast<jlong>(llm_session);
}

/**
 * submitNative(handle, prompt, keepHistory, roles, contents, callback) -> 完整回复文本
 *
 * **同步阻塞**：与上游一致，JNI 层不起线程。token 通过 callback.onToken(String?) 逐段
 * 吐出，回调返回 true 表示请求停止生成（协作式取消）。收到 <eop> 时回调 null
 * （对齐上游 onProgress(null) 语义）。
 *
 * roles/contents 非空时走「完整会话」路径：roles[0] 约定为 "system"，
 * 其后按 user/assistant 交替；C++ 侧整体替换 history_ 后再生成（调用前建议
 * 先 resetNative，语义＝重置 KV 后按给定历史重放）。
 * roles 为空时退化为上游单 prompt 路径。
 */
JNIEXPORT jstring JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_submitNative(JNIEnv* env, jobject thiz,
                                                   jlong handle,
                                                   jstring prompt,
                                                   jboolean keepHistory,
                                                   jobjectArray roles,
                                                   jobjectArray contents,
                                                   jobject callback) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm == nullptr) {
        throwIllegalState(env, "Failed, Chat is not ready! (handle == 0)");
        return nullptr;
    }
    jmethodID onTokenMethod = findOnTokenMethod(env, callback);
    if (onTokenMethod == nullptr || env->ExceptionCheck()) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        throwIllegalState(env, "TokenCallback.onToken(String?): Boolean not found");
        return nullptr;
    }
    std::vector<mls::PromptItem> messages;
    const jsize roleCount = roles != nullptr ? env->GetArrayLength(roles) : 0;
    if (roleCount > 0) {
        std::vector<std::string> roleNames = toStringVector(env, roles);
        std::vector<std::string> roleContents = toStringVector(env, contents);
        const size_t count = std::min(roleNames.size(), roleContents.size());
        messages.reserve(count);
        for (size_t i = 0; i < count; ++i) {
            messages.emplace_back(roleNames[i], roleContents[i]);
        }
    }

    const char* input_str = prompt != nullptr ? env->GetStringUTFChars(prompt, nullptr) : nullptr;
    MNN_DEBUG("submitNative begin, handle=%p, messages=%zu, keep_history=%d",
              static_cast<void*>(llm), messages.size(), static_cast<int>(keepHistory));

    // ---- 与上游 llm_mnn_jni.cpp:170-181 的逐字对应（回调 + 停止语义） ----
    const MNN::Transformer::LlmContext* context = nullptr;
    if (!messages.empty()) {
        context = llm->Response(messages, [&, callback, onTokenMethod](
                const std::string& response, bool is_eop) {
            if (callback != nullptr && onTokenMethod != nullptr) {
                jstring javaString = is_eop ? nullptr : env->NewStringUTF(response.c_str());
                jboolean user_stop_requested = env->CallBooleanMethod(callback,
                                                                      onTokenMethod, javaString);
                env->DeleteLocalRef(javaString);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    return true;
                }
                return static_cast<bool>(user_stop_requested);
            }
            return true;
        });
    } else {
        context = llm->Response(input_str != nullptr ? input_str : "", [&, callback, onTokenMethod](
                const std::string& response, bool is_eop) {
            if (callback != nullptr && onTokenMethod != nullptr) {
                jstring javaString = is_eop ? nullptr : env->NewStringUTF(response.c_str());
                jboolean user_stop_requested = env->CallBooleanMethod(callback,
                                                                      onTokenMethod, javaString);
                env->DeleteLocalRef(javaString);
                if (env->ExceptionCheck()) {
                    env->ExceptionDescribe();
                    env->ExceptionClear();
                    return true;
                }
                return static_cast<bool>(user_stop_requested);
            }
            return true;
        });
    }
    if (prompt != nullptr && input_str != nullptr) {
        env->ReleaseStringUTFChars(prompt, input_str);
    }
    if (context == nullptr) {
        throwIllegalState(env, "LlmSession::Response returned null context");
        return nullptr;
    }

    std::string full_text = llm->GetLastResponseText();
    MNN_DEBUG("submitNative end: prompt_len=%d decode_len=%d full_text=%zu chars",
              context->prompt_len, context->gen_seq_len, full_text.size());
    return env->NewStringUTF(full_text.c_str());
}

JNIEXPORT jobject JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_submitStatsNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm == nullptr) {
        return nullptr;
    }
    const auto* context = llm->GetContext();
    if (context == nullptr) {
        return nullptr;
    }
    return makeStringLongHashMap(env, context->prompt_len, context->gen_seq_len,
                                 context->prefill_us, context->decode_us,
                                 context->vision_us, context->audio_us);
}

JNIEXPORT void JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_resetNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm) {
        MNN_DEBUG("RESET");
        llm->Reset();
    }
}

JNIEXPORT void JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_releaseNative(JNIEnv* env, jobject thiz, jlong handle) {
    MNN_DEBUG("LIFECYCLE: About to DESTROY LlmSession at %p", reinterpret_cast<void*>(handle));
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    delete llm;
    MNN_DEBUG("LIFECYCLE: LlmSessionInner DESTROYED at %p", reinterpret_cast<void*>(handle));
}

JNIEXPORT jstring JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_getDebugInfoNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(llm->getDebugInfo().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_dumpConfigNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm == nullptr) {
        return env->NewStringUTF("{}");
    }
    return env->NewStringUTF(llm->dumpConfig().c_str());
}

JNIEXPORT void JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_updateMaxNewTokensNative(JNIEnv* env, jobject thiz,
                                                              jlong handle, jint maxNewTokens) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm) {
        llm->SetMaxNewTokens(maxNewTokens);
    }
}

JNIEXPORT void JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_updateSystemPromptNative(JNIEnv* env, jobject thiz,
                                                              jlong handle, jstring systemPrompt) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (systemPrompt == nullptr) {
        return;
    }
    const char* system_prompt_cstr = env->GetStringUTFChars(systemPrompt, nullptr);
    if (llm && system_prompt_cstr != nullptr) {
        llm->setSystemPrompt(system_prompt_cstr);
    }
    if (system_prompt_cstr != nullptr) {
        env->ReleaseStringUTFChars(systemPrompt, system_prompt_cstr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_getSystemPromptNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm) {
        std::string system_prompt = llm->getSystemPrompt();
        return env->NewStringUTF(system_prompt.c_str());
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_setConfigNative(JNIEnv* env, jobject thiz,
                                                      jlong handle, jstring configJson) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (configJson == nullptr) {
        return;
    }
    const char* json_cstr = env->GetStringUTFChars(configJson, nullptr);
    if (llm && json_cstr != nullptr) {
        llm->setRuntimeConfig(json_cstr);
    }
    if (json_cstr != nullptr) {
        env->ReleaseStringUTFChars(configJson, json_cstr);
    }
}

JNIEXPORT jstring JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_backendDiagnosticsNative(JNIEnv* env, jobject, jlong handle) {
    auto* session = reinterpret_cast<mls::LlmSession*>(handle);
    const std::string report = session ? session->backendDiagnostics() : "{}";
    return env->NewStringUTF(report.c_str());
}

JNIEXPORT void JNICALL
Java_com_mnnkit_app_llm_MnnLlmSession_clearHistoryNative(JNIEnv* env, jobject thiz, jlong handle) {
    auto* llm = reinterpret_cast<mls::LlmSession*>(handle);
    if (llm) {
        llm->clearHistory();
    }
}

} // extern "C"
