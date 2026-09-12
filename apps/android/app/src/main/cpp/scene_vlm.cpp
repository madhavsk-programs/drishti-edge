// JNI bridge for the Scene Mode vision-language model (docs/SCENE_MODE_VLM.md).
//
// SCOPE. This is deliberately the smallest surface that can answer one question
// about one image: load, ask, close. There is no streaming, no session, no KV
// reuse across calls. That is not minimalism for its own sake — the Class B
// contract in docs/SCENE_MODE_VLM.md §5 requires the model to be resident for
// exactly one inference and then gone, so anything that outlives a call would
// be a bug rather than a feature.
//
// The model runs on the CPU. It is NOT part of the NPU claim and must never be
// described as such; the continuous YOLO/SegFormer safety loop is a separate
// pipeline that keeps running on the Hexagon NPU while this is idle.
//
// Images arrive as tight RGB888 from Kotlin, which already has the camera frame
// in that layout, so nothing here decodes JPEG or touches the filesystem for
// pixels.

#include <jni.h>

#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "SceneVlmNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// One loaded model plus everything derived from it. Owned by a single jlong
// handle so Kotlin cannot half-release it.
struct SceneVlm {
    llama_model   * model   = nullptr;
    llama_context * lctx    = nullptr;
    mtmd_context  * mctx    = nullptr;
    int             nThreads = 6;

    // Set from another thread to abort a running generation. See §5's
    // "deterministic cancellation" requirement.
    std::atomic<bool> cancelled{false};

    ~SceneVlm() {
        // Reverse construction order: mtmd holds references into the model.
        if (mctx)  mtmd_free(mctx);
        if (lctx)  llama_free(lctx);
        if (model) llama_model_free(model);
    }
};

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return {};
    const char * raw = env->GetStringUTFChars(s, nullptr);
    std::string out = raw ? raw : "";
    if (raw) env->ReleaseStringUTFChars(s, raw);
    return out;
}

// llama.cpp logs at INFO for routine progress; route it to logcat but keep the
// noise down so a real error is still findable.
void logCallback(ggml_log_level level, const char * text, void *) {
    if (!text) return;
    if (level == GGML_LOG_LEVEL_ERROR) {
        LOGE("%s", text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        LOGW("%s", text);
    } else if (level == GGML_LOG_LEVEL_INFO) {
        // mtmd-helper reports "encoding image slice" / "image slice encoded in
        // N ms" / "decoding image batch" at INFO. Those three lines are what
        // separate a stall in the CLIP encoder from one in llama_decode, so
        // they go to logcat at DEBUG: visible with `logcat SceneVlmNative:D`,
        // silent at the default filter.
        __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", text);
    }
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_drishti_app_scene_SceneVlm_nativeLoad(
        JNIEnv * env, jobject, jstring jModel, jstring jMmproj,
        jint nThreads, jint nCtx, jint imageMaxTokens) {

    static std::once_flag once;
    std::call_once(once, [] {
        llama_log_set(logCallback, nullptr);
        mtmd_helper_log_set(logCallback, nullptr);
        llama_backend_init();
    });

    const std::string modelPath  = jstr(env, jModel);
    const std::string mmprojPath = jstr(env, jMmproj);

    auto * vlm = new SceneVlm();
    vlm->nThreads = nThreads > 0 ? nThreads : 6;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;             // CPU only. Not an NPU claim.
    // `use_mmap` left this struct upstream; mapping is the default. Do not
    // reintroduce it from an older llama.cpp example — it will not compile
    // against the pinned revision.

    vlm->model = llama_model_load_from_file(modelPath.c_str(), mparams);
    if (!vlm->model) {
        LOGE("could not load %s", modelPath.c_str());
        delete vlm;
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx        = nCtx > 0 ? (uint32_t) nCtx : 4096;
    // n_batch/n_ubatch stay at llama.cpp's defaults (2048 / 512), matching
    // llama-mtmd-cli.
    cparams.n_threads    = vlm->nThreads;
    cparams.n_threads_batch = vlm->nThreads;
    // Flash attention OFF, here and for the CLIP encoder below. At b10926 the
    // CPU flash-attention kernel's tiled path (taken for any batch of 64+
    // rows, so the 80-token image batch but never the short text chunks)
    // writes past the end of its work buffer on this phone: SIGSEGV in
    // memset inside ggml_compute_forward_flash_attn_ext. In a -O0 build the
    // same corruption wedged the process with no crash and no log, which is
    // the "hang" docs/SCENE_MODE_VLM.md §8 spent an evening on. Measured cost
    // of the non-fused path on a 350M model over ~110 tokens: within noise.
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    vlm->lctx = llama_init_from_model(vlm->model, cparams);
    if (!vlm->lctx) {
        LOGE("could not create llama context");
        delete vlm;
        return 0;
    }
    // Polled by ggml between graph nodes, so a cancel lands within one node's
    // compute time during prefill as well as during the token loop. That is
    // what makes §5's "deterministic cancellation" true: the Kotlin side can
    // wait for nativeAsk to return instead of abandoning the thread. The CLIP
    // encoder has no such hook through the mtmd API; its whole pass is under
    // 0.6 s on this phone and is the one window a cancel cannot cut short.
    llama_set_abort_callback(vlm->lctx, [](void * data) -> bool {
        return static_cast<SceneVlm *>(data)->cancelled.load();
    }, vlm);

    mtmd_context_params pparams = mtmd_context_params_default();
    pparams.use_gpu        = false;
    pparams.print_timings  = false;
    pparams.n_threads      = vlm->nThreads;
    // Warmup allocates the CLIP compute buffer at load rather than on the
    // first image, so the first Ask pays the same as every later one.
    pparams.warmup         = true;
    // Same kernel, same reason as the llama context above: the encoder runs
    // 300 patches through the tiled flash-attention path. Measured on this
    // phone: 466 ms fused vs 481-524 ms without, i.e. noise.
    pparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    if (imageMaxTokens > 0) {
        // The measured lever from §4.2: vision cost is superlinear in tokens.
        pparams.image_max_tokens = imageMaxTokens;
    }

    vlm->mctx = mtmd_init_from_file(mmprojPath.c_str(), vlm->model, pparams);
    if (!vlm->mctx) {
        LOGE("could not load mmproj %s", mmprojPath.c_str());
        delete vlm;
        return 0;
    }
    if (!mtmd_support_vision(vlm->mctx)) {
        LOGE("mmproj has no vision support");
        delete vlm;
        return 0;
    }

    LOGI("scene vlm ready (threads=%d, n_ctx=%u)", vlm->nThreads, cparams.n_ctx);
    return reinterpret_cast<jlong>(vlm);
}

JNIEXPORT void JNICALL
Java_com_drishti_app_scene_SceneVlm_nativeCancel(JNIEnv *, jobject, jlong handle) {
    auto * vlm = reinterpret_cast<SceneVlm *>(handle);
    if (vlm) vlm->cancelled.store(true);
}

JNIEXPORT void JNICALL
Java_com_drishti_app_scene_SceneVlm_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto * vlm = reinterpret_cast<SceneVlm *>(handle);
    delete vlm;   // destructor releases mtmd, context and model in order
}

/**
 * One image, one question, one answer. Returns null on failure or cancellation
 * rather than a partial string — a half-sentence read aloud to someone crossing
 * a road is worse than silence.
 */
JNIEXPORT jstring JNICALL
Java_com_drishti_app_scene_SceneVlm_nativeAsk(
        JNIEnv * env, jobject, jlong handle,
        jbyteArray jRgb, jint width, jint height,
        jstring jPrompt, jstring jAnswerInstruction, jint maxTokens) {

    auto * vlm = reinterpret_cast<SceneVlm *>(handle);
    if (!vlm || !vlm->mctx || !jRgb) return nullptr;

    vlm->cancelled.store(false);

    const jsize rgbLen = env->GetArrayLength(jRgb);
    if (rgbLen != (jsize) width * height * 3) {
        LOGE("rgb length %d does not match %dx%d", rgbLen, width, height);
        return nullptr;
    }

    std::vector<unsigned char> rgb((size_t) rgbLen);
    env->GetByteArrayRegion(jRgb, 0, rgbLen, reinterpret_cast<jbyte *>(rgb.data()));

    mtmd_bitmap * bitmap = mtmd_bitmap_init((uint32_t) width, (uint32_t) height, rgb.data());
    if (!bitmap) {
        LOGE("could not build bitmap");
        return nullptr;
    }

    // LFM2.5-VL uses ChatML. The media marker is substituted by mtmd_tokenize.
    // A specific instruction beats "describe this": §4.5 measured the same model
    // missing a backpack when captioning freely and finding it when asked.
    const std::string marker = mtmd_default_marker();
    const std::string answerInstruction = jstr(env, jAnswerInstruction);
    const std::string prompt =
        "<|im_start|>system\nYou are a careful visual assistant for a blind pedestrian. "
        "Inspect the image before answering. If people are visible, report them first. "
        "Mention only clearly visible objects or readable text; never invent details. "
        "If uncertain, say so. Answer the visual question directly; never repeat or "
        "paraphrase the question. Answer in one short sentence. " + answerInstruction +
        "<|im_end|>\n"
        "<|im_start|>user\n" + marker + "\n" + jstr(env, jPrompt) + "<|im_end|>\n"
        "<|im_start|>assistant\n";

    mtmd_input_text text{};
    text.text          = prompt.c_str();
    text.text_len      = prompt.size();
    text.add_special   = true;
    text.parse_special = true;

    mtmd_input_chunks * chunks = mtmd_input_chunks_init();
    const mtmd_bitmap * bitmaps[1] = { bitmap };

    LOGI("step: tokenize");
    int32_t rc = mtmd_tokenize(vlm->mctx, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(bitmap);
    if (rc != 0) {
        LOGE("mtmd_tokenize failed: %d", rc);
        mtmd_input_chunks_free(chunks);
        return nullptr;
    }

    LOGI("step: memory_clear");
    llama_memory_clear(llama_get_memory(vlm->lctx), true);

    // Evaluated chunk by chunk rather than via mtmd_helper_eval_chunks so a
    // failure is attributable to a specific chunk in logcat. The helper is a
    // thin loop over exactly this call; there is no behavioural difference.
    // A cancel surfaces here as a non-zero rc from llama_decode (aborted).
    const size_t nChunks = mtmd_input_chunks_size(chunks);
    LOGI("step: eval %zu chunks", nChunks);

    llama_pos nPast = 0;
    rc = 0;
    for (size_t i = 0; i < nChunks && rc == 0; ++i) {
        const mtmd_input_chunk * chunk = mtmd_input_chunks_get(chunks, i);
        const mtmd_input_chunk_type type = mtmd_input_chunk_get_type(chunk);
        LOGI("  chunk %zu/%zu type=%d tokens=%zu n_past=%d",
             i + 1, nChunks, (int) type, mtmd_input_chunk_get_n_tokens(chunk), (int) nPast);
        rc = mtmd_helper_eval_chunk_single(
                vlm->mctx, vlm->lctx, chunk, nPast, /*seq_id*/ 0,
                /*n_batch*/ 512, /*logits_last*/ (i + 1 == nChunks), &nPast);
        LOGI("  chunk %zu done rc=%d n_past=%d", i + 1, rc, (int) nPast);
    }
    mtmd_input_chunks_free(chunks);
    if (rc != 0) {
        if (vlm->cancelled.load()) {
            LOGW("prefill cancelled");
        } else {
            LOGE("chunk evaluation failed: %d", rc);
        }
        return nullptr;
    }

    // Greedy decode. Scene answers must be reproducible for the same frame —
    // a demo that says something different each time is not evidence.
    LOGI("step: decode loop (n_past=%d)", (int) nPast);
    llama_sampler * sampler = llama_sampler_init_greedy();
    const llama_vocab * vocab = llama_model_get_vocab(vlm->model);

    std::string answer;
    char piece[512];
    const int limit = maxTokens > 0 ? maxTokens : 48;

    for (int i = 0; i < limit; ++i) {
        if (vlm->cancelled.load()) {
            LOGW("generation cancelled at token %d", i);
            llama_sampler_free(sampler);
            return nullptr;
        }

        const llama_token tok = llama_sampler_sample(sampler, vlm->lctx, -1);
        if (llama_vocab_is_eog(vocab, tok)) break;

        const int n = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) answer.append(piece, n);

        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&tok), 1);
        if (llama_decode(vlm->lctx, batch) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }
        nPast++;
    }

    LOGI("step: done, %zu chars", answer.size());
    llama_sampler_free(sampler);

    // Trim: the template can leave leading whitespace before the first word.
    const size_t begin = answer.find_first_not_of(" \n\r\t");
    if (begin == std::string::npos) return nullptr;
    answer = answer.substr(begin);

    return env->NewStringUTF(answer.c_str());
}

} // extern "C"
