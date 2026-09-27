//
// Created by ruoyi.sjd on 2025/4/18.
//
// ---------------------------------------------------------------------------
// 本文件是 MNN 官方 Android LLM Demo 中 cpp/llm_session.h 的**裁剪复用版**。
//
//   上游：<MNN_ROOT>/apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.h
//
// 保留（逐字与上游一致）：
//   * LlmSession 构造函数 / Load() / Reset() / isModelReady() / getLastLoadError()
//   * Response(prompt, on_progress) —— 流式解码主循环入口
//   * SetMaxNewTokens() / setSystemPrompt() / getSystemPrompt()
//   * clearHistory() / dumpConfig() / getDebugInfo()
//   * 全部成员变量
//
// 裁剪（与纯文本 LLM 推理无关，上游属 TTS / 基准测试 / 多轮 API 服务）：
//   * SetWavformCallback() / enableAudioOutput() / waveform / enable_audio_output_
//   * SetAssistantPrompt() / updateConfig()
//   * 整个 benchmark 块（BenchmarkResult / ProgressType / BenchmarkProgressInfo /
//     BenchmarkCallback / runBenchmark 及其 7 个私有 helper）
//   * getLlm()（仅 benchmark 使用）
//   * 手工前置声明 JNI 类型的那段（最小集不需要 <jni.h>）
//
// 新增（本工程自研，非上游）：
//   * Response(const std::vector<PromptItem>&, on_progress) —— 接受**完整会话消息列表**
//     （含 system 轮）的入口。上游只接受单个 user prompt、由 C++ 侧自行滚动维护
//     history_，Kotlin 侧无法按 ChatMessage 列表精确控制上下文；本工程按「每轮
//     reset + 重放完整历史」的语义调用，因此需要这个重载。它复用上游完全相同的
//     流式 / <eop> / syncPromptCache 主循环（RunResponse()），不引入新逻辑。
// ---------------------------------------------------------------------------
//
#pragma once
#include <vector>
#include <string>
#include <chrono>
#include "nlohmann/json.hpp"
#include "llm/llm.hpp"

using nlohmann::json;
using MNN::Transformer::Llm;

namespace mls {
using PromptItem = std::pair<std::string, std::string>;

class LlmSession {
public:
    /** [model_path] = 模型 config.json 的完整路径
     *  [config] = 模型 config.json 的内容（或与运行时配置合并后的 JSON）
     *  [extra_config] = { "is_r1": bool, "keep_history": bool, "mmap_dir": string } */
    LlmSession(std::string model_path, json config, json extra_config, std::vector<std::string> string_history);
    void Reset();
    bool Load();
    bool isModelReady() const { return llm_ != nullptr && model_loaded_; }
    /** Last error message when Load() fails. Cleared on success. */
    const std::string& getLastLoadError() const { return last_load_error_; }
    ~LlmSession();
    std::string getDebugInfo();
    /** 最近一轮生成的完整回复文本（已剔除 <eop> 哨兵）。即使在 eop 最终化之前
     *  就退出（用户取消 / 达到 max_new_tokens），这里也是 response_buffer 的内容，
     *  用于「用户取消后仍把已生成内容交回 Kotlin 侧」。 */
    std::string GetLastResponseText() const { return response_string_for_debug; }
    /** 最近一轮的 LlmContext（prompt_len / gen_seq_len / *_us 等性能计数）。 */
    const MNN::Transformer::LlmContext * GetContext() const;
    const MNN::Transformer::LlmContext *
    Response(const std::string &prompt, const std::function<bool(const std::string &, bool is_eop)> &on_progress);
    /** 用一整个会话消息列表替换当前 history_ 后流式生成。
     *  [messages] 首项约定为 ("system", <system prompt>)，随后按 user/assistant 交替。
     *  调用前通常应先 Reset()，语义即「重置 KV 后按给定历史重放」。 */
    const MNN::Transformer::LlmContext *
    Response(const std::vector<PromptItem> &messages,
             const std::function<bool(const std::string &, bool is_eop)> &on_progress);
    void SetMaxNewTokens(int i);

    void setSystemPrompt(std::string system_prompt);

    std::string getSystemPrompt() const;

    void clearHistory(int numToKeep = 1);

    std::string dumpConfig() const;
    /** Actual executor runtime map; not per-operator execution proof. */
    std::string backendDiagnostics() const;

    /**
     * 把一段 JSON 合并进底层 Llm 的运行时配置（转发到 `Llm::set_config`）。
     *
     * 用途：在**不重新加载模型**的前提下改运行时开关。目前只用于
     * 运行时关掉原生的模板渲染（`{"use_template":false}`）——
     * 因为 prompt 已经在 Kotlin 侧渲染成 ChatML 了，
     * 再让原生套一次模板会变成双重模板。
     *
     * 上游没有这个方法（它走 `updateConfigNative` 整体替换配置），
     * 这里只做最小暴露：不解析、不保存，直接透传。
     */
    void setRuntimeConfig(const std::string& config_json);

private:
    /** 上游 Response() 的流式主循环本体（prefill-only response(...,0) + generate(1) 步进
     *  + <eop> 边界状态机 + syncPromptCache），两个公开重载共用。 */
    const MNN::Transformer::LlmContext *
    RunResponse(const std::function<bool(const std::string &, bool is_eop)> &on_progress);

    std::string response_string_for_debug{};
    std::string model_path_;
    std::vector<PromptItem> history_{};
    json extra_config_{};
    json config_{};
    bool is_r1_{false};
    bool stop_requested_{false};
    bool generate_text_end_{false};
    bool keep_history_{true};
    Llm* llm_{nullptr};
    bool model_loaded_{false};
    std::string prompt_string_for_debug{};
    int max_new_tokens_{2048};
    std::string system_prompt_;
    json current_config_{};
    std::string last_load_error_{};
};
}
