package com.drishti.app.scene

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import java.io.File

private const val TAG = "SceneVlm"

/**
 * The Scene Mode vision-language model, under the Class B contract
 * (`docs/SCENE_MODE_VLM.md` §5).
 *
 * **This class owns a model for the duration of one question and no longer.**
 * [ask] checks free memory, loads, runs exactly one inference, and frees
 * everything before it returns — including on failure, cancellation and
 * timeout. There is deliberately no `open()`/`close()` pair a caller could
 * leave dangling, and no way to keep the model warm between questions. That
 * costs roughly a second per call on the shipping model and is worth it: the
 * continuous walking loop must never lose memory to an idle VLM.
 *
 * It runs on the **CPU**. It is not part of the NPU claim and must never be
 * presented as one. The YOLO/SegFormer safety loop keeps running on the Hexagon
 * NPU throughout.
 */
class SceneVlm private constructor(
    private val context: Context,
    private val model: File,
    private val mmproj: File,
) {

    private external fun nativeLoad(
        modelPath: String,
        mmprojPath: String,
        nThreads: Int,
        nCtx: Int,
        imageMaxTokens: Int,
    ): Long

    private external fun nativeAsk(
        handle: Long,
        rgb: ByteArray,
        width: Int,
        height: Int,
        prompt: String,
        maxTokens: Int,
    ): String?

    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    /** Why an answer could not be produced. Every branch is speakable. */
    sealed interface Result {
        data class Answer(val text: String, val millis: Long) : Result
        data class NotEnoughMemory(val freeBytes: Long, val neededBytes: Long) : Result
        data object ModelMissing : Result
        data object Failed : Result
        data object Cancelled : Result
    }

    @Volatile private var liveHandle: Long = 0L
    @Volatile private var cancelRequested = false

    /**
     * Cancel an in-flight [ask]. Safe from any thread and safe to call when
     * nothing is running. The answer is discarded rather than truncated — half
     * a sentence spoken to someone crossing a road is worse than silence.
     *
     * The native side polls the flag between graph nodes during prefill and
     * between tokens during generation, so [ask] returns within one node's
     * compute time — well under a second on the shipping phone — and frees the
     * model on its way out. Callers wait for that return; they never abandon
     * the thread.
     */
    fun cancel() {
        cancelRequested = true
        val handle = liveHandle
        if (handle != 0L) nativeCancel(handle)
    }

    /**
     * @param rgb tight RGB888, [width] * [height] * 3 bytes, already scaled to
     *   [IMAGE_LONG_EDGE]. Callers should use [scaleForVlm].
     */
    fun ask(rgb: ByteArray, width: Int, height: Int, question: String): Result {
        if (!model.isFile || !mmproj.isFile) return Result.ModelMissing
        if (rgb.size != width * height * 3) {
            Log.e(TAG, "rgb is ${rgb.size} bytes, expected ${width * height * 3}")
            return Result.Failed
        }

        val needed = model.length() + mmproj.length() + WORKING_SET_BYTES
        val free = availableMemoryBytes()
        if (free < needed + SAFETY_MARGIN_BYTES) {
            Log.w(TAG, "declining: $free free, need $needed + $SAFETY_MARGIN_BYTES margin")
            return Result.NotEnoughMemory(free, needed + SAFETY_MARGIN_BYTES)
        }

        cancelRequested = false
        val started = System.nanoTime()

        val handle = nativeLoad(
            model.absolutePath,
            mmproj.absolutePath,
            THREADS,
            CONTEXT_TOKENS,
            IMAGE_MAX_TOKENS,
        )
        if (handle == 0L) {
            Log.e(TAG, "native load failed")
            return Result.Failed
        }
        liveHandle = handle

        val answer = try {
            if (cancelRequested) null else nativeAsk(handle, rgb, width, height, question, MAX_TOKENS)
        } catch (exc: Throwable) {
            Log.e(TAG, "inference threw", exc)
            null
        } finally {
            // The contract's whole point. Runs on every path.
            liveHandle = 0L
            nativeFree(handle)
        }

        val millis = (System.nanoTime() - started) / 1_000_000
        return when {
            cancelRequested -> Result.Cancelled
            answer.isNullOrBlank() -> Result.Failed
            else -> {
                Log.i(TAG, "answered in $millis ms: $answer")
                Result.Answer(answer.trim(), millis)
            }
        }
    }

    private fun availableMemoryBytes(): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    companion object {
        const val MODEL = "lfm25-vl-450m-q4km.gguf"
        const val MMPROJ = "lfm25-vl-450m-mmproj-q8.gguf"

        /**
         * Long edge for the image handed to the model. Measured, not guessed:
         * `docs/SCENE_MODE_VLM.md` §4.2 found vision cost superlinear in pixels
         * with a cliff, and found that below this the model stops reading text
         * and starts inventing it.
         */
        const val IMAGE_LONG_EDGE = 320

        /** Six, not eight — the little cores drag every step (§4.3). */
        private const val THREADS = 6
        private const val CONTEXT_TOKENS = 2048
        private const val IMAGE_MAX_TOKENS = 256
        private const val MAX_TOKENS = 48

        /** KV cache, the decoded image and llama.cpp's own scratch buffers. */
        private const val WORKING_SET_BYTES = 400L * 1024 * 1024

        /** `ARCHITECTURE.md` §5.1. Fixed, and never tuned down to fit a model. */
        private const val SAFETY_MARGIN_BYTES = 800L * 1024 * 1024

        @Volatile private var loaded = false

        /**
         * @return null when the native library or the model files are absent.
         *   The caller then tells the user scene description is unavailable,
         *   rather than presenting silence as "nothing ahead".
         */
        fun create(context: Context): SceneVlm? {
            if (!loaded) {
                val ok = runCatching { System.loadLibrary("drishti_scene_vlm") }
                    .onFailure { Log.w(TAG, "native library unavailable; Scene VLM disabled", it) }
                    .isSuccess
                if (!ok) return null
                loaded = true
            }
            val dir = context.getExternalFilesDir(null)
            val model = File(dir, MODEL)
            val mmproj = File(dir, MMPROJ)
            if (!model.isFile || !mmproj.isFile) {
                Log.w(TAG, "Scene VLM models not staged in ${dir?.absolutePath}")
                return null
            }
            return SceneVlm(context.applicationContext, model, mmproj)
        }
    }
}
