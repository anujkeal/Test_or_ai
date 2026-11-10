#include <android/log.h>
#include <jni.h>
#include <iomanip>
#include <cmath>
#include <string>
#include <unistd.h>
#include <sys/sysinfo.h>
#include <chrono>
#include <mutex>
#include <sampling.h>

#include "logging.h"
#include "chat.h"
#include "common.h"
#include "llama.h"

template<class T>
static std::string join(const std::vector<T> &values, const std::string &delim) {
    std::ostringstream str;
    for (size_t i = 0; i < values.size(); i++) {
        str << values[i];
        if (i < values.size() - 1) { str << delim; }
    }
    return str.str();
}

/**
 * LLama resources: context, model, batch and sampler
 */
constexpr int   N_THREADS_MIN           = 2;
constexpr int   N_THREADS_MAX           = 4;
constexpr int   N_THREADS_HEADROOM      = 2;

constexpr int   DEFAULT_CONTEXT_SIZE    = 2048;
constexpr int   OVERFLOW_HEADROOM       = 4;
constexpr int   BATCH_SIZE              = 16;
constexpr float DEFAULT_SAMPLER_TEMP    = 0.3f;

static llama_model                      * g_model;
static llama_context                    * g_context;
static common_batch                       g_batch;
static common_chat_templates_ptr          g_chat_templates;
static common_sampler                   * g_sampler;
static std::mutex g_error_mutex;
static std::string g_last_model_error;

static void log_memory_snapshot(const char *stage) {
    struct sysinfo info {};
    if (sysinfo(&info) == 0) {
        const unsigned long long free_bytes =
            static_cast<unsigned long long>(info.freeram) * info.mem_unit;
        const unsigned long long total_bytes =
            static_cast<unsigned long long>(info.totalram) * info.mem_unit;
        LOGi("NEXUS memory [%s]: system free %.2f GiB / total %.2f GiB",
             stage,
             static_cast<double>(free_bytes) / (1024.0 * 1024.0 * 1024.0),
             static_cast<double>(total_bytes) / (1024.0 * 1024.0 * 1024.0));
    } else {
        LOGw("NEXUS memory [%s]: sysinfo unavailable", stage);
    }
}

static void nexus_log_callback(enum ggml_log_level level, const char * text, void * user_data) {
    aichat_android_log_callback(level, text, user_data);
    if (level == GGML_LOG_LEVEL_ERROR || level == GGML_LOG_LEVEL_WARN) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_model_error.append(text ? text : "");
        if (g_last_model_error.size() > 12000) g_last_model_error.erase(0, g_last_model_error.size() - 12000);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_init(JNIEnv *env, jobject /*unused*/, jstring nativeLibDir) {
    // Set llama log handler to Android
    llama_log_set(nexus_log_callback, nullptr);

    // Loading all CPU backend variants
    const auto *path_to_backend = env->GetStringUTFChars(nativeLibDir, 0);
    LOGi("Loading backends from %s", path_to_backend);
    ggml_backend_load_all_from_path(path_to_backend);
    env->ReleaseStringUTFChars(nativeLibDir, path_to_backend);

    // Initialize backends
    llama_backend_init();
    LOGi("Backend initiated; Log handler set.");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_load(JNIEnv *env, jobject, jstring jmodel_path) {
    llama_model_params model_params = llama_model_default_params();
    // Use llama.cpp defaults for model mapping and page locking. This pinned
    // llama.cpp revision does not expose use_mmap/use_mlock in model params.
    {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        g_last_model_error.clear();
    }

    const auto *model_path = env->GetStringUTFChars(jmodel_path, 0);
    if (model_path == nullptr) return 2;
    LOGi("NEXUS native load started using pinned llama.cpp model defaults");
    log_memory_snapshot("before model load");
    const auto load_started = std::chrono::steady_clock::now();
    auto *model = llama_model_load_from_file(model_path, model_params);
    const auto load_finished = std::chrono::steady_clock::now();
    env->ReleaseStringUTFChars(jmodel_path, model_path);
    const auto load_seconds =
        std::chrono::duration_cast<std::chrono::seconds>(load_finished - load_started).count();
    LOGi("NEXUS native model load returned after %lld seconds",
         static_cast<long long>(load_seconds));
    log_memory_snapshot("after model load");
    if (!model) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        if (g_last_model_error.empty()) g_last_model_error = "llama.cpp returned null while opening the GGUF file. Check file integrity and GGUF compatibility.";
        return 1;
    }
    g_model = model;
    return 0;
}

static llama_context *init_context(llama_model *model, const int n_ctx = DEFAULT_CONTEXT_SIZE) {
    if (!model) {
        LOGe("%s: model cannot be null", __func__);
        return nullptr;
    }

    // Multi-threading setup
    const int n_threads = std::max(N_THREADS_MIN, std::min(N_THREADS_MAX,
                                                     (int) sysconf(_SC_NPROCESSORS_ONLN) -
                                                     N_THREADS_HEADROOM));
    LOGi("%s: Using %d threads", __func__, n_threads);

    // Context parameters setup
    llama_context_params ctx_params = llama_context_default_params();
    const int trained_context_size = llama_model_n_ctx_train(model);
    if (n_ctx > trained_context_size) {
        LOGw("%s: Model was trained with only %d context size! Enforcing %d context size...",
             __func__, trained_context_size, n_ctx);
    }
    ctx_params.n_ctx = n_ctx;
    ctx_params.n_batch = BATCH_SIZE;
    ctx_params.n_ubatch = BATCH_SIZE;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    auto *context = llama_init_from_model(g_model, ctx_params);
    if (context == nullptr) {
        LOGe("%s: llama_new_context_with_model() returned null)", __func__);
    }
    return context;
}

static common_sampler *new_sampler(float temp) {
    common_params_sampling sparams;
    sparams.temp = temp;
    return common_sampler_init(g_model, sparams);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_nativeLastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_model_error.c_str());
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_prepare(JNIEnv * /*env*/, jobject /*unused*/) {
    LOGi("NEXUS context preparation started");
    log_memory_snapshot("before context preparation");
    const auto prepare_started = std::chrono::steady_clock::now();
    auto *context = init_context(g_model);
    if (!context) { return 1; }
    g_context = context;
    g_batch = common_batch(context);
    g_chat_templates = common_chat_templates_init(g_model, "");
    g_sampler = new_sampler(DEFAULT_SAMPLER_TEMP);
    const auto prepare_finished = std::chrono::steady_clock::now();
    LOGi("NEXUS context preparation completed after %lld seconds",
         static_cast<long long>(std::chrono::duration_cast<std::chrono::seconds>(
             prepare_finished - prepare_started).count()));
    log_memory_snapshot("after context preparation");
    return 0;
}

static std::string get_backend() {
    std::vector<std::string> backends;
    for (size_t i = 0; i < ggml_backend_reg_count(); i++) {
        auto *reg = ggml_backend_reg_get(i);
        std::string name = ggml_backend_reg_name(reg);
        if (name != "CPU") {
            backends.push_back(ggml_backend_reg_name(reg));
        }
    }
    return backends.empty() ? "CPU" : join(backends, ",");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_systemInfo(JNIEnv *env, jobject /*unused*/) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_benchModel(JNIEnv *env, jobject /*unused*/, jint pp, jint tg,
                                                      jint pl, jint nr) {
    auto *context = init_context(g_model, pp);
    if (!context) {
        const auto *const err_msg = "Fail to init_context! Bench aborted.";
        LOGe(err_msg);
        return env->NewStringUTF(err_msg);
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const uint32_t n_ctx = llama_n_ctx(context);
    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp = %d)", pp);

        common_batch batch(context);

        const int n_tokens = pp;
        for (i = 0; i < n_tokens; i++) {
            batch.add(0, i, 0, false);
        }

        batch.set_output(batch.size() - 1, true);
        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp_start = ggml_time_us();
        if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            LOGe("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg = %d)", tg);

        llama_memory_clear(llama_get_memory(context), false);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {
            batch.clear();
            for (j = 0; j < pl; j++) {
                batch.add(0, i, j, true);
            }

            if (llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                LOGe("llama_decode() failed during text generation");
            }
        }
        const auto t_tg_end = ggml_time_us();

        llama_memory_clear(llama_get_memory(context), false);

        const auto t_pp = double(t_pp_end - t_pp_start) / 1000000.0;
        const auto t_tg = double(t_tg_end - t_tg_start) / 1000000.0;

        const auto speed_pp = double(pp) / t_pp;
        const auto speed_tg = double(pl * tg) / t_tg;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    llama_free(context);

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(g_model, model_desc, sizeof(model_desc));

    const auto model_size = double(llama_model_size(g_model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(g_model)) / 1e9;

    const auto backend = get_backend();
    std::stringstream result;
    result << std::setprecision(3);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | "
           << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";
    return env->NewStringUTF(result.str().c_str());
}


/**
 * Completion loop's long-term states:
 * - chat management
 * - position tracking
 */
constexpr const char *ROLE_SYSTEM       = "system";
constexpr const char *ROLE_USER         = "user";
constexpr const char *ROLE_ASSISTANT    = "assistant";

static std::vector<common_chat_msg> chat_msgs;
static llama_pos system_prompt_position;
static llama_pos current_position;

static void reset_long_term_states(const bool clear_kv_cache = true) {
    chat_msgs.clear();
    system_prompt_position = 0;
    current_position = 0;

    if (clear_kv_cache && g_context)
        llama_memory_clear(llama_get_memory(g_context), false);
}

/**
 * TODO-hyin: implement sliding-window version as a better alternative
 *
 * Context shifting by discarding the older half of the tokens appended after system prompt:
 * - take the [system_prompt_position] first tokens from the original prompt
 * - take half of the last (system_prompt_position - system_prompt_position) tokens
 * - recompute the logits in batches
 */
static void shift_context() {
    const int n_discard = (current_position - system_prompt_position) / 2;
    LOGi("%s: Discarding %d tokens", __func__, n_discard);
    llama_memory_seq_rm(llama_get_memory(g_context), 0, system_prompt_position, system_prompt_position + n_discard);
    llama_memory_seq_add(llama_get_memory(g_context), 0, system_prompt_position + n_discard, current_position, -n_discard);
    current_position -= n_discard;
    LOGi("%s: Context shifting done! Current position: %d", __func__, current_position);
}

static std::string chat_add_and_format(const std::string &role, const std::string &content) {
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    auto formatted = common_chat_format_single(
            g_chat_templates.get(), chat_msgs, new_msg, role == ROLE_USER, /* use_jinja */ false);
    chat_msgs.push_back(new_msg);
    LOGi("%s: Formatted and added %s message: \n%s\n", __func__, role.c_str(), formatted.c_str());
    return formatted;
}

/**
 * Completion loop's short-term states:
 * - stop generation position
 * - token chars caching
 * - current assistant message being generated
 */
static llama_pos stop_generation_position;
static std::string cached_token_chars;
static std::ostringstream assistant_ss;

static void reset_short_term_states() {
    stop_generation_position = 0;
    cached_token_chars.clear();
    assistant_ss.str("");
}

static int decode_tokens_in_batches(
        llama_context *context,
        common_batch &batch,
        const llama_tokens &tokens,
        const llama_pos start_pos,
        const bool compute_last_logit = false) {
    // Process tokens in batches using the global batch
    LOGd("%s: Decode %d tokens starting at position %d", __func__, (int) tokens.size(), start_pos);
    llama_pos position = start_pos;
    const llama_pos max_position = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    for (int i = 0; i < (int) tokens.size(); i += BATCH_SIZE) {
        const int cur_batch_size = std::min((int) tokens.size() - i, BATCH_SIZE);
        batch.clear();
        LOGv("%s: Preparing a batch size of %d at token position %d", __func__, cur_batch_size, position);

        // Context shifting changes the live position. Never keep using the stale
        // start_pos + i offset after a shift, or llama.cpp receives invalid positions.
        if (position + cur_batch_size > max_position) {
            LOGw("%s: Current batch won't fit into context! Shifting...", __func__);
            current_position = position;
            shift_context();
            position = current_position;
        }
        if (position + cur_batch_size > max_position) {
            LOGe("%s: Prompt batch still exceeds context after shifting (position=%d, batch=%d)",
                 __func__, position, cur_batch_size);
            return 1;
        }

        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos token_position = position + j;
            const bool want_logit = compute_last_logit && (i + j == tokens.size() - 1);
            batch.add(token_id, token_position, 0, want_logit);
        }

        const int decode_result = llama_process(context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        if (decode_result) {
            LOGe("%s: llama_decode failed w/ %d at position %d", __func__, decode_result, position);
            return 1;
        }
        position += cur_batch_size;
        current_position = position;
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processSystemPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring jsystem_prompt
) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Obtain system prompt from JEnv
    const auto *system_prompt = env->GetStringUTFChars(jsystem_prompt, nullptr);
    LOGd("%s: System prompt received: \n%s", __func__, system_prompt);
    std::string formatted_system_prompt(system_prompt);

    // Format system prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_system_prompt = chat_add_and_format(ROLE_SYSTEM, system_prompt);
    }
    env->ReleaseStringUTFChars(jsystem_prompt, system_prompt);

    // Tokenize system prompt
    const auto system_tokens = common_tokenize(g_context, formatted_system_prompt,
                                               has_chat_template, has_chat_template);
    for (auto id: system_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Handle context overflow
    const int max_batch_size = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if ((int) system_tokens.size() > max_batch_size) {
        LOGe("%s: System prompt too long for context! %d tokens, max: %d",
             __func__, (int) system_tokens.size(), max_batch_size);
        return 1;
    }

    // Decode system tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, system_tokens, current_position)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // Update position
    system_prompt_position = current_position = (int) system_tokens.size();
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_processUserPrompt(
        JNIEnv *env,
        jobject /*unused*/,
        jstring juser_prompt,
        jint n_predict
) {
    // Reset short-term states
    reset_short_term_states();

    // Obtain and tokenize user prompt
    const auto *const user_prompt = env->GetStringUTFChars(juser_prompt, nullptr);
    LOGd("%s: User prompt received: \n%s", __func__, user_prompt);
    std::string formatted_user_prompt(user_prompt);

    // Format user prompt if applicable
    const bool has_chat_template = common_chat_templates_was_explicit(g_chat_templates.get());
    if (has_chat_template) {
        formatted_user_prompt = chat_add_and_format(ROLE_USER, user_prompt);
    }
    env->ReleaseStringUTFChars(juser_prompt, user_prompt);

    // Decode formatted user prompts
    auto user_tokens = common_tokenize(g_context, formatted_user_prompt, has_chat_template, has_chat_template);
    for (auto id: user_tokens) {
        LOGv("token: `%s`\t -> `%d`", common_token_to_piece(g_context, id).c_str(), id);
    }

    // Preserve the system prompt and the newest part of the user's message.
    // If older conversation turns occupy the cache, discard their oldest half first.
    const int max_position = DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM;
    if (current_position + (llama_pos) user_tokens.size() > max_position &&
        current_position > system_prompt_position) {
        shift_context();
    }
    const int available_tokens = std::max(0, max_position - (int) current_position);
    if ((int) user_tokens.size() > available_tokens) {
        const int skipped_tokens = (int) user_tokens.size() - available_tokens;
        user_tokens.erase(user_tokens.begin(), user_tokens.begin() + skipped_tokens);
        LOGw("%s: User prompt exceeded remaining context; kept newest %d tokens and skipped %d.",
             __func__, (int) user_tokens.size(), skipped_tokens);
    }
    if (user_tokens.empty()) {
        LOGe("%s: No room remains for user tokens after preserving the system prompt.", __func__);
        return 3;
    }
    const int user_prompt_size = (int) user_tokens.size();

    // Decode user tokens in batches
    if (decode_tokens_in_batches(g_context, g_batch, user_tokens, current_position, true)) {
        LOGe("%s: llama_decode() failed!", __func__);
        return 2;
    }

    // decode_tokens_in_batches updates current_position after each successful batch.
    // Do not add user_prompt_size again: that double-counts every prompt and causes
    // premature context overflow on subsequent turns.
    stop_generation_position = current_position + n_predict;
    return 0;
}

static bool is_valid_utf8(const char *string) {
    if (!string) { return true; }

    const auto *bytes = (const unsigned char *) string;
    int num;

    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            // U+0000 to U+007F
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            // U+0080 to U+07FF
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            // U+0800 to U+FFFF
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            // U+10000 to U+10FFFF
            num = 4;
        } else {
            return false;
        }

        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_generateNextToken(
        JNIEnv *env,
        jobject /*unused*/
) {
    // Infinite text generation via context shifting
    if (current_position >= DEFAULT_CONTEXT_SIZE - OVERFLOW_HEADROOM) {
        LOGw("%s: Context full! Shifting...", __func__);
        shift_context();
    }

    // Stop if reaching the marked position
    if (current_position >= stop_generation_position) {
        LOGw("%s: STOP: hitting stop position: %d", __func__, stop_generation_position);
        return nullptr;
    }

    // Sample next token
    const auto new_token_id = common_sampler_sample(g_sampler, g_context, -1);
    common_sampler_accept(g_sampler, new_token_id, true);

    // Populate the batch with new token, then decode
    g_batch.clear();
    g_batch.add(new_token_id, current_position, 0, true);
    if (llama_process(g_context, LLAMA_PROCESS_TYPE_DECODE, g_batch.get()) != 0) {
        LOGe("%s: llama_decode() failed for generated token", __func__);
        return nullptr;
    }

    // Update position
    current_position++;

    // Stop if next token is EOG
    if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_token_id)) {
        LOGd("id: %d,\tIS EOG!\nSTOP.", new_token_id);
        chat_add_and_format(ROLE_ASSISTANT, assistant_ss.str());
        return nullptr;
    }

    // If not EOG, convert to text
    auto new_token_chars = common_token_to_piece(g_context, new_token_id);
    cached_token_chars += new_token_chars;

    // Create and return a valid UTF-8 Java string
    jstring result = nullptr;
    if (is_valid_utf8(cached_token_chars.c_str())) {
        result = env->NewStringUTF(cached_token_chars.c_str());
        LOGv("id: %d,\tcached: `%s`,\tnew: `%s`", new_token_id, cached_token_chars.c_str(), new_token_chars.c_str());

        assistant_ss << cached_token_chars;
        cached_token_chars.clear();
    } else {
        LOGv("id: %d,\tappend to cache", new_token_id);
        result = env->NewStringUTF("");
    }
    return result;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_unload(JNIEnv * /*unused*/, jobject /*unused*/) {
    // Reset long-term & short-term states
    reset_long_term_states();
    reset_short_term_states();

    // Free up resources
    if (g_sampler) { common_sampler_free(g_sampler); g_sampler = nullptr; }
    g_chat_templates.reset();
    g_batch = common_batch();
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_arm_aichat_internal_InferenceEngineImpl_shutdown(JNIEnv *, jobject /*unused*/) {
    llama_backend_free();
}
