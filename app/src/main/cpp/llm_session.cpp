//
// Created by ruoyi.sjd on 2025/4/18.
//
// ---------------------------------------------------------------------------
// 本文件是 MNN 官方 Android LLM Demo 中 cpp/llm_session.cpp 的**裁剪复用版**。
//
//   上游：<MNN_ROOT>/apps/Android/MnnLlmChat/app/src/main/cpp/llm_session.cpp (869 行)
//
// 🔴 逐字保留、**绝对不可删**的三段（官方注释已说明是为「链接预编译 libMNN.so」而打）：
//   1. restoreAndroidSteppingStatusIfNeeded()  —— 把 MAX_TOKENS_FINISHED /
//      NORMAL_FINISHED 复位成 RUNNING。删掉会导致「只能生成一轮」。
//   2. resolveAndroidSteppingEop()             —— 预编译 runtime 在每个 generate(1)
//      步末都吐 <eop>，必须把中间 <eop> 当**边界**而非结束。删掉会导致
//      「首 token 即结束」。
//   3. AndroidSteppingStreamState              —— <eop> 状态机（pending_eop /
//      finalizePendingEop）。
//   另外同样必须保留：prefill-only `response(..., 0)` + 自建 `generate(1)` 步进循环、
//   Utf8StreamProcessor（UTF-8 跨 chunk 重组）、LlmStreamBuffer（xsputn 流式回调）。
//
// 裁剪：Firebase 上报、TTS/waveform、benchmark（上游 :582-869）、多模态 processor/
//   （文本路径不需要）、video/。
// 新增：Response(const std::vector<PromptItem>&) 重载 + 私有 RunResponse() 抽取
//   （见 llm_session.h 文件头说明）。
// ---------------------------------------------------------------------------
//
#include "llm_session.h"
#include <utility>
#include <chrono>
#include <fstream>
#include <algorithm>
#include <cctype>
#include <sstream>
#include <dlfcn.h>
#include "MNN/ExecutionEvidence.h"
#include "MNN/MNNForwardType.h"
#include "MNN/expr/ExecutorScope.hpp"
#include "mls_log.h"
#include "utf8_stream_processor.hpp"
#include "llm_stream_buffer.hpp"

namespace mls {

namespace {

void restoreAndroidSteppingStatusIfNeeded(Llm* llm) {
    if (llm == nullptr) {
        return;
    }
    auto* context = llm->getContext();
    if (context == nullptr) {
        return;
    }
    if (context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED ||
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED) {
        // Android links the prebuilt libMNN.so runtime, so this wrapper locally resets
        // terminal step states instead of depending on a shared engine change.
        auto* mutable_context = const_cast<MNN::Transformer::LlmContext*>(context);
        mutable_context->status = MNN::Transformer::LlmStatus::RUNNING;
    }
}

struct AndroidSteppingStreamState {
    std::stringstream& response_buffer;
    const std::function<bool(const std::string&, bool is_eop)>& on_progress;
    bool& generate_text_end;
    bool& stop_requested;
    std::string& response_string_for_debug;
    std::function<void(const std::string&)> on_response_complete;
    const char* result_log_tag;
    bool pending_eop = false;

    void processChunk(const std::string& utf8Char) {
        const bool is_eop = utf8Char.find("<eop>") != std::string::npos;
        if (!is_eop) {
            response_buffer << utf8Char;
            if (on_progress) {
                stop_requested = stop_requested || on_progress(utf8Char, false);
            }
            return;
        }
        pending_eop = true;
    }

    void finalizePendingEop() {
        if (!pending_eop) {
            return;
        }
        std::string response_result = response_buffer.str();
        MNN_DEBUG("%s bytes=%zu", result_log_tag, response_result.size());
        response_string_for_debug = response_result;
        on_response_complete(response_result);
        if (on_progress) {
            stop_requested = stop_requested || on_progress("<eop>", true);
        }
        generate_text_end = true;
        pending_eop = false;
    }
};

void resolveAndroidSteppingEop(
        Llm* llm,
        AndroidSteppingStreamState& stream_state,
        int current_size,
        int max_new_tokens) {
    auto* context = llm != nullptr ? llm->getContext() : nullptr;
    if (context != nullptr &&
        context->status == MNN::Transformer::LlmStatus::MAX_TOKENS_FINISHED &&
        !stream_state.stop_requested &&
        current_size < max_new_tokens) {
        // Android currently links a prebuilt runtime that emits <eop> at the end of
        // every generate(1) step. Treat that as an intermediate boundary, not final end.
        restoreAndroidSteppingStatusIfNeeded(llm);
        if (stream_state.pending_eop) {
            stream_state.generate_text_end = false;
            stream_state.pending_eop = false;
        }
        return;
    }
    if (context != nullptr &&
        context->status == MNN::Transformer::LlmStatus::NORMAL_FINISHED &&
        !stream_state.pending_eop &&
        !stream_state.stop_requested &&
        current_size < max_new_tokens) {
        // After one round finishes naturally, prefill-only response(..., 0) can leave the
        // next round's first generate(1) starting from NORMAL_FINISHED in the prebuilt runtime.
        restoreAndroidSteppingStatusIfNeeded(llm);
        return;
    }
    if (stream_state.pending_eop) {
        stream_state.finalizePendingEop();
    }
}

/** Ends optional MNN Pipeline evidence on this same native thread on every exit. */
class ExecutionEvidenceCapture {
public:
    using Begin = int (*)();
    using End = int (*)(MNNExecutionEvidenceV1*, uint32_t);

    explicit ExecutionEvidenceCapture(bool enabled) {
        if (!enabled) return;
        auto begin = reinterpret_cast<Begin>(dlsym(RTLD_DEFAULT, "MNNExecutionEvidenceBegin"));
        mEnd = reinterpret_cast<End>(dlsym(RTLD_DEFAULT, "MNNExecutionEvidenceEnd"));
        mActive = begin != nullptr && mEnd != nullptr && begin() == 0;
    }

    ~ExecutionEvidenceCapture() { finish(); }
    ExecutionEvidenceCapture(const ExecutionEvidenceCapture&) = delete;
    ExecutionEvidenceCapture& operator=(const ExecutionEvidenceCapture&) = delete;

    bool finish() {
        if (!mActive) return false;
        mActive = false;
        return mEnd(&snapshot, sizeof(snapshot)) == 0;
    }

    MNNExecutionEvidenceV1 snapshot{};

private:
    End mEnd = nullptr;
    bool mActive = false;
};

} // namespace

std::string trimLeadingWhitespace(const std::string& str) {
    auto it = std::find_if(str.begin(), str.end(), [](unsigned char ch) {
        return !std::isspace(ch);
    });
    return {it, str.end()};
}

std::string getUserString(const char* user_content, bool for_history, bool is_r1) {
    if (is_r1) {
        return "<|User|>" + std::string(user_content) + "<|Assistant|>" + (for_history ? "" : "<think>\n");
    } else {
        return user_content;
    }
}

std::string GetSystemPromptString(std::string system_prompt,  bool is_r1) {
    if (is_r1) {
        return std::string("<|begin_of_sentence|>") + system_prompt;
    } else {
        return system_prompt;
    }
}

std::string deleteThinkPart(std::string assistant_content) {
    std::size_t think_start = assistant_content.find("<think>");
    if (think_start == std::string::npos) {
        return assistant_content;
    }
    std::size_t think_end = assistant_content.find("</think>", think_start);
    if (think_end == std::string::npos) {
        return assistant_content;
    }
    think_end += std::string("</think>").length();
    assistant_content.erase(think_start, think_end - think_start);
    return assistant_content;
}

std::string getR1AssistantString(std::string assistant_content) {
    std::size_t pos = assistant_content.find("</think>");
    if (pos != std::string::npos) {
        assistant_content.erase(0, pos + std::string("</think>").length());
    }
    return trimLeadingWhitespace(assistant_content) + "<|end_of_sentence|>";
}

void LlmSession::Reset() {
    history_.resize(1);
    if (llm_) {
        llm_->reset();
    }
}

LlmSession::LlmSession(std::string model_path, json config, json extra_config, std::vector<std::string> history):
        model_path_(std::move(model_path)), extra_config_(std::move(extra_config)),
        config_(std::move(config)) {
    max_new_tokens_ = config_.contains("max_new_tokens") ?  config_["max_new_tokens"].get<int>() : 2048;
    keep_history_ = !extra_config_.contains("keep_history") || extra_config_["keep_history"].get<bool>();
    is_r1_ = extra_config_.contains("is_r1") && extra_config_["is_r1"].get<bool>();
    system_prompt_ = config_.contains("system_prompt") ? config_["system_prompt"].get<std::string>() : "You are a helpful assistant.";
    history_.emplace_back("system", GetSystemPromptString(system_prompt_, is_r1_));
    if (!history.empty()) {
        for (size_t i = 0; i < history.size(); i++) {
            if (is_r1_) {
                if (i % 2 == 0) {
                    history_.emplace_back("user", getUserString(history[i].c_str(), true, is_r1_));
                } else {
                    history_.emplace_back("assistant", getR1AssistantString(history[i]));
                }
            } else {
                history_.emplace_back(i % 2 == 0 ? "user" : "assistant",
                                      i % 2 == 0 ? history[i] :
                                      deleteThinkPart(history[i]));
            }
        }
    }
}

bool LlmSession::Load() {
    last_load_error_.clear();
    // 上游直接 extra_config_["mmap_dir"]，缺键会抛异常；这里补默认值（行为等价：
    // 空串 => use_mmap=false）。
    std::string root_cache_dir_str = extra_config_.contains("mmap_dir")
            ? extra_config_["mmap_dir"].get<std::string>() : std::string();
    bool use_mmap = !root_cache_dir_str.empty();
    llm_ = Llm::createLLM(model_path_);
    if (llm_ == nullptr) {
        last_load_error_ = "createLLM failed for config path: " + model_path_ +
            " (config file missing or invalid)";
        MNN_DEBUG("Failed to create LLM instance: %s", last_load_error_.c_str());
        model_loaded_ = false;
        return false;
    }
    json config = config_;
    config["use_mmap"] = use_mmap;
    if (use_mmap) {
        config["tmp_path"] = root_cache_dir_str;
    }
    if (is_r1_) {
        config["use_template"] = false;
        config["precision"] = "high";
    }
    current_config_ = config;
    auto config_str = config.dump();
    // Never log configuration bodies: they include private system prompts.
    llm_->set_config(config_str);
    // Requested configuration is not runtime execution evidence.
    model_loaded_ = llm_->load();
    if (!model_loaded_) {
        last_load_error_ = "Model load failed for config: " + model_path_ +
            ". Common causes: model file (.mnn) missing or corrupted, wrong backend (e.g. NPU on CPU-only device), insufficient memory.";
        MNN_DEBUG("Model load() returned false: %s", last_load_error_.c_str());
    }
    return model_loaded_;
}

LlmSession::~LlmSession() {
    MNN_DEBUG("LIFECYCLE: LlmSession DESTROYED at %p", this);
    delete llm_;
}

const MNN::Transformer::LlmContext * LlmSession::Response(const std::string &prompt,
                                                          const std::function<bool(const std::string&, bool is_eop)>& on_progress) {
    if (llm_ == nullptr) {
        return nullptr;
    }
    if (!keep_history_) {
        history_.resize(1);
    }
    history_.emplace_back("user", getUserString(prompt.c_str(), false, is_r1_));
    return RunResponse(on_progress);
}

const MNN::Transformer::LlmContext * LlmSession::Response(const std::vector<PromptItem>& messages,
                                                          const std::function<bool(const std::string&, bool is_eop)>& on_progress) {
    if (llm_ == nullptr) {
        return nullptr;
    }
    // 用调用方给定的完整会话替换 history_。首项通常是 ("system", ...)，因此这里
    // 不额外注入 system prompt，避免重复。
    history_.clear();
    for (const auto& message : messages) {
        if (message.first == "system") {
            system_prompt_ = message.second;
            history_.emplace_back("system", GetSystemPromptString(system_prompt_, is_r1_));
        } else if (message.first == "assistant") {
            history_.emplace_back("assistant", deleteThinkPart(message.second));
        } else if (message.first == "json") {
            // 上游约定：role == "json" 时 second 是完整 JSON 消息对象，直接透传
            history_.emplace_back(message);
        } else {
            history_.emplace_back("user", getUserString(message.second.c_str(), false, is_r1_));
        }
    }
    return RunResponse(on_progress);
}

const MNN::Transformer::LlmContext * LlmSession::RunResponse(
        const std::function<bool(const std::string&, bool is_eop)>& on_progress) {
    if (llm_ == nullptr) {
        return nullptr;
    }

    int current_size = 0;
    stop_requested_ = false;
    generate_text_end_ = false;
    std::stringstream response_buffer;
    AndroidSteppingStreamState stream_state{
            response_buffer,
            on_progress,
            generate_text_end_,
            stop_requested_,
            response_string_for_debug,
            [this](const std::string& raw_response) {
                std::string response_result = raw_response;
                if (is_r1_) {
                    auto& last_message = history_.at(history_.size() - 1);
                    std::size_t user_think_pos = last_message.second.find("<think>\n");
                    if (user_think_pos != std::string::npos) {
                        last_message.second.erase(user_think_pos, std::string("<think>\n").length());
                    }
                    response_result = getR1AssistantString(response_result);
                }
                response_result = trimLeadingWhitespace(deleteThinkPart(response_result));
                history_.emplace_back("assistant", response_result);
            },
            "submitNative Result"
    };
    mls::Utf8StreamProcessor processor([&stream_state](const std::string& utf8Char) {
        stream_state.processChunk(utf8Char);
    });
    LlmStreamBuffer stream_buffer{[&processor](const char* str, size_t len){
        processor.processStream(str, len);
    }};
    std::ostream output_ostream(&stream_buffer);

    MNN_DEBUG("submitNative history count %zu", history_.size());
    prompt_string_for_debug.clear();
    for (auto & it : history_) {
        prompt_string_for_debug += it.second;
    }
    MNN_DEBUG("submitNative messages=%zu max_new_tokens=%d", history_.size(), max_new_tokens_);

    last_execution_evidence_ = nullptr;
    // Optional symbols preserve compatibility with the original MNN runtime.
    ExecutionEvidenceCapture evidenceCapture(config_.value("mnnkit_capture_execution_evidence", false));
    restoreAndroidSteppingStatusIfNeeded(llm_);
    // Prefill only. Stepping decode one token at a time via llm_->generate(1) keeps
    // streaming active without letting the engine mark the session as finished up front.
    llm_->response(history_, &output_ostream, "<eop>", 0);
    resolveAndroidSteppingEop(llm_, stream_state, current_size, max_new_tokens_);
    while (!stop_requested_ && !generate_text_end_ && current_size < max_new_tokens_) {
        llm_->generate(1);
        current_size++;
        resolveAndroidSteppingEop(llm_, stream_state, current_size, max_new_tokens_);
    }
    // Two post-decode outcomes:
    // 1. Natural <eop>: finalizePendingEop fires the on_response_complete
    //    callback which adds the assistant reply to history_ — sync cache.
    // 2. No <eop> (max-tokens truncation OR user cancel): the partial
    //    response was streamed to the UI and the caller always persists
    //    the assistant item, so add it to history_ and sync to keep
    //    engine state aligned with the visible conversation.
    size_t history_after = history_.size();
    stream_state.finalizePendingEop();
    if (history_.size() > history_after) {
        // (1) Natural <eop> — callback already added the assistant reply
        llm_->syncPromptCache(history_);
    } else if (current_size > 0) {
        // (2) No <eop> — add the partial response to history_ using the
        //     same processing as the on_response_complete callback.
        std::string response_result = response_buffer.str();
        if (is_r1_) {
            auto& last_message = history_.at(history_.size() - 1);
            std::size_t user_think_pos = last_message.second.find("<think>\n");
            if (user_think_pos != std::string::npos) {
                last_message.second.erase(user_think_pos, std::string("<think>\n").length());
            }
            response_result = getR1AssistantString(response_result);
        }
        response_result = trimLeadingWhitespace(deleteThinkPart(response_result));
        if (!response_result.empty()) {
            history_.emplace_back("assistant", response_result);
        }
        llm_->syncPromptCache(history_);
    }

    auto context = llm_->getContext();
    // 记录本轮完整回复：finalizePendingEop() 走的是 history 增补路径，但
    // 「用户取消 / 达到 max_new_tokens」时也需要把已生成内容交回 Kotlin 侧，
    // 因此这里统一从 response_buffer 落一份。
    response_string_for_debug = response_buffer.str();
    if (evidenceCapture.finish()) {
        const auto& evidence = evidenceCapture.snapshot;
        json backendCounts = json::object();
        for (unsigned i = 0; i < MNN_EXECUTION_EVIDENCE_SLOTS; ++i) {
            if (!evidence.successful[i] && !evidence.copies[i] && !evidence.backendErrors[i]) continue;
            backendCounts[std::to_string(i)] = {
                {"successful_dispatches", evidence.successful[i]},
                {"completed_dispatches", evidence.completed[i]},
                {"unconfirmed_dispatches", evidence.unconfirmed[i]},
                {"sync_failed_dispatches", evidence.syncFailed[i]},
                {"copies", evidence.copies[i]},
                {"backend_errors", evidence.backendErrors[i]},
            };
        }
        last_execution_evidence_ = {
            {"kind", "instrumented_pipeline_dispatch_and_opencl_queue_sync"},
            {"backend_counts", backendCounts},
        };
    }
    float prefill_s = context->prefill_us / 1e6f;
    float decode_s = context->decode_us / 1e6f;
    float prefill_tps = (prefill_s > 0) ? context->prompt_len / prefill_s : 0;
    float decode_tps = (decode_s > 0) ? context->gen_seq_len / decode_s : 0;
    MNN_DEBUG("PERF | prefill: %d tok in %.2fs (%.1f t/s) | decode: %d tok in %.2fs (%.1f t/s) | history: %zu msgs",
              context->prompt_len, prefill_s, prefill_tps,
              context->gen_seq_len, decode_s, decode_tps,
              history_.size());
    return context;
}

const MNN::Transformer::LlmContext * LlmSession::GetContext() const {
    return llm_ != nullptr ? llm_->getContext() : nullptr;
}

std::string LlmSession::getDebugInfo() {
    return ("last_prompt:\n" + prompt_string_for_debug + "\nlast_response:\n" + response_string_for_debug);
}

void LlmSession::SetMaxNewTokens(int i) {
    max_new_tokens_ = i;
}

void LlmSession::setSystemPrompt(std::string system_prompt) {
    system_prompt_= std::move(system_prompt);
    if (history_.size() > 1) {
        history_.at(0).second = GetSystemPromptString(system_prompt_, is_r1_);
    } else {
        history_.emplace_back("system", GetSystemPromptString(system_prompt_, is_r1_));
    }
}

void LlmSession::clearHistory(int numToKeep) {
    if (numToKeep < 0) {
        numToKeep = 0;
    }
    if (history_.size() > static_cast<size_t>(numToKeep)) {
        history_.erase(history_.begin() + numToKeep, history_.end());
    }
    // 清空相关缓存
    prompt_string_for_debug.clear();
    //response_string_for_debug.clear();
    if (llm_) {
        llm_->reset();
    }
}

std::string LlmSession::getSystemPrompt() const {
    return system_prompt_;
}

std::string LlmSession::dumpConfig() const {
    if (llm_ != nullptr) {
        return llm_->dump_config();
    }
    return "{}";
}

void LlmSession::setRuntimeConfig(const std::string& config_json) {
    if (llm_ == nullptr) {
        return;
    }
    // MNN 的 set_config 会把这几个键 merge 进现有配置，并在下一轮 response()
    // 生效（不需要重新加载模型）。用来运行时关掉原生模板渲染。
    llm_->set_config(config_json);
    MNN_DEBUG("setRuntimeConfig updated");
}

std::string LlmSession::backendDiagnostics() const {
    json report;
    report["requested_backend"] = config_.value("backend_type", std::string("model_default"));
    report["executor_runtime_backends"] = json::array();
    report["execution_backend_verified"] = false;
    report["evidence_kind"] = "created_runtime_not_per_operator_execution";
    if (!last_execution_evidence_.is_null()) {
        report["execution_evidence"] = last_execution_evidence_;
        report["evidence_kind"] = "instrumented_pipeline_dispatch_and_opencl_queue_sync";
        const auto& counts = last_execution_evidence_["backend_counts"];
        if (counts.contains("3") && counts["3"].value("completed_dispatches", 0ULL) > 0) {
            // Confirmation is limited to MNN Pipeline dispatch and queue completion,
            // not physical kernel utilization or all execution outside Pipeline.
            report["execution_backend_verified"] = true;
        }
    }
    if (llm_ != nullptr && llm_->getExecutor()) {
        MNN::Express::ExecutorScope scope(llm_->getExecutor());
        const auto runtime = MNN::Express::Executor::getRuntime();
        for (const auto& entry : runtime.first) {
            report["executor_runtime_backends"].push_back(static_cast<int>(entry.first));
        }
        report["cpu_backup_runtime_present"] = runtime.second != nullptr;
    }
    return report.dump();
}

} // namespace mls
