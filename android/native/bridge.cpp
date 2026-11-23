#include <jni.h>
#include "llama.h"
#include "ggml.h"
#include <algorithm>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::atomic<bool> cancelled = false;
std::mutex run_mutex;
std::once_flag initialised;

void initialise() {
    std::call_once(initialised, [] { llama_backend_init(); });
}

std::string bytes(JNIEnv * env, jbyteArray input) {
    if (input == nullptr) throw std::runtime_error("Missing input");
    const auto size = env->GetArrayLength(input);
    std::string value(size, '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(value.data()));
    return value;
}

jbyteArray answer(JNIEnv * env, const std::string & value) {
    auto data = env->NewByteArray(static_cast<jsize>(value.size()));
    if (data != nullptr) {
        env->SetByteArrayRegion(data, 0, static_cast<jsize>(value.size()),
                                reinterpret_cast<const jbyte *>(value.data()));
    }
    return data;
}

std::string format_prompt(const llama_model * model, const std::string & prompt) {
    const char * configured = llama_model_chat_template(model, nullptr);
    if (configured == nullptr) throw std::runtime_error("This GGUF has no chat template");
    const char * system = "You are MokAiCoder. Respond in the language of the request. Be concise. /no_think";
    const llama_chat_message messages[] = {{"system", system}, {"user", prompt.c_str()}};
    int32_t size = llama_chat_apply_template(configured, messages, 2, true, nullptr, 0);
    if (size < 1 || size > 32'000) throw std::runtime_error("Unsupported chat template");
    std::vector<char> buffer(static_cast<size_t>(size) + 1);
    int32_t written = llama_chat_apply_template(configured, messages, 2, true, buffer.data(),
                                                static_cast<int32_t>(buffer.size()));
    if (written != size) throw std::runtime_error("Could not format model input");
    return {buffer.data(), static_cast<size_t>(written)};
}

std::string generate(const std::string & path, const std::string & prompt, int threads) {
    if (prompt.empty() || prompt.size() > 12'000 || path.empty()) throw std::runtime_error("Invalid input");
    initialise();
    auto model_options = llama_model_default_params();
    model_options.n_gpu_layers = 0;
    model_options.use_mmap = true;
    model_options.progress_callback = [](float, void *) { return !cancelled.load(); };
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
        llama_model_load_from_file(path.c_str(), model_options), llama_model_free);
    if (!model || llama_model_has_encoder(model.get()) || cancelled.load())
        throw std::runtime_error("Could not load this local text model");

    auto vocab = llama_model_get_vocab(model.get());
    const std::string formatted = format_prompt(model.get(), prompt);
    const auto size = static_cast<int32_t>(formatted.size());
    const int32_t needed = -llama_tokenize(vocab, formatted.data(), size, nullptr, 0, true, true);
    if (needed < 1 || needed > 1150) throw std::runtime_error("Prompt exceeds the local context");
    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(vocab, formatted.data(), size, tokens.data(), needed, true, true) != needed)
        throw std::runtime_error("Could not tokenize model input");

    auto options = llama_context_default_params();
    options.n_ctx = 1536;
    options.n_batch = 256;
    options.n_ubatch = 128;
    options.n_threads = options.n_threads_batch = std::clamp(threads, 1, 4);
    options.abort_callback = [](void *) { return cancelled.load(); };
    std::unique_ptr<llama_context, decltype(&llama_free)> context(
        llama_init_from_model(model.get(), options), llama_free);
    if (!context) throw std::runtime_error("Could not allocate local inference context");

    for (int start = 0; start < needed; start += 256) {
        auto batch = llama_batch_get_one(tokens.data() + start, std::min(256, needed - start));
        if (cancelled.load() || llama_decode(context.get(), batch) != 0)
            throw std::runtime_error("Local inference stopped");
    }
    std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
        llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(32));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string output;
    for (int step = 0; step < 320 && !cancelled.load(); step++) {
        auto token = llama_sampler_sample(sampler.get(), context.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> piece(256);
        int count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        if (count < 0) {
            piece.resize(-count);
            count = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
        }
        if (count < 0) throw std::runtime_error("Unexpected model output");
        output.append(piece.data(), count);
        if (llama_decode(context.get(), llama_batch_get_one(&token, 1)) != 0)
            throw std::runtime_error("Local inference stopped");
    }
    return output;
}
} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_mokai_coder_LocalRuntime_runNative(JNIEnv * env, jclass, jbyteArray path, jbyteArray prompt, jint threads) {
    std::lock_guard<std::mutex> lock(run_mutex);
    cancelled.store(false);
    try {
        return answer(env, generate(bytes(env, path), bytes(env, prompt), threads));
    } catch (const std::exception & error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_mokai_coder_LocalRuntime_cancelNative(JNIEnv *, jclass) {
    cancelled.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_mokai_coder_LocalRuntime_inspectNative(JNIEnv * env, jclass, jbyteArray path) {
    std::lock_guard<std::mutex> lock(run_mutex);
    try {
        initialise();
        auto options = llama_model_default_params();
        options.n_gpu_layers = 0;
        options.vocab_only = true;
        const auto file = bytes(env, path);
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(file.c_str(), options), llama_model_free);
        if (!model || llama_model_has_encoder(model.get())) return JNI_FALSE;
        format_prompt(model.get(), "test");
        return JNI_TRUE;
    } catch (const std::exception &) {
        return JNI_FALSE;
    }
}
