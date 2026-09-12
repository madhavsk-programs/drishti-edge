package com.drishti.app.scene

import com.drishti.app.R
import com.drishti.app.feedback.GuidanceStrings
import com.drishti.app.feedback.SpeechEngine
import com.drishti.app.feedback.SpokenLanguage
import com.drishti.app.feedback.VoicePrompt
import com.drishti.app.walk.CameraFramePipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-demand scene description and Q&A, answered **on the phone**. The user
 * speaks a question, we take one still and run it through [SceneVlm], then speak
 * the answer. There is no network call and no server.
 *
 * This is the deliberately-slow path: the caller pauses the Walk loop (mode =
 * DESCRIBING), invokes this, then resumes. It never runs automatically and never
 * shares timing with the walking analysis. [SceneVlm] loads the model, answers
 * once and frees it, so each question pays a load — that is the Class B contract
 * in `docs/SCENE_MODE_VLM.md` §5, not an oversight.
 *
 * @param vlm null when the native library or the GGUF files are not present. The
 *   feature then degrades audibly rather than silently: the user is told scene
 *   description is unavailable instead of hearing nothing and assuming the way
 *   ahead is clear.
 */
class SceneDescriber(
    private val vlm: SceneVlm?,
    private val pipeline: CameraFramePipeline,
    private val speech: SpeechEngine,
    private val strings: GuidanceStrings,
    private val voice: VoicePrompt,
) {

    /**
     * The shipping 450M checkpoint was device-tested with Tamil input and
     * echoed the question instead of inspecting the image. Refuse that one
     * combination explicitly; static Tamil guidance and OCR readout remain
     * available throughout the app.
     */
    suspend fun rejectUnsupportedLanguage(language: SpokenLanguage): Boolean {
        if (language != SpokenLanguage.TAMIL) return false
        speech.speakBlocking(
            strings.string(R.string.vlm_language_unavailable),
            maxWaitMs = 8_000L,
        )
        return true
    }

    data class Result(val question: String, val answer: String, val totalMs: Double)

    private sealed interface Attempt {
        data class Done(val text: String, val totalMs: Double) : Attempt
        data object RetryOnce : Attempt
        data object GiveUp : Attempt
    }

    /**
     * Speak the prompt, open the mic once, and return the raw transcript (or
     * null on denial / silence / error). The caller decides whether that text
     * is a scene question or an "Ask -> Lock" target.
     */
    suspend fun listenForRequest(languageTag: String): String? {
        speech.speakBlocking(strings.string(R.string.vlm_prompt_ask), maxWaitMs = 8_000L)
        delay(250)
        if (voice.blocked()) {
            speech.speakBlocking(strings.string(R.string.vlm_mic_denied), maxWaitMs = 8_000L)
            return null
        }
        return withTimeoutOrNull(14_000) { voice.listen(languageTag) }
    }

    /**
     * @param heard raw transcript from [listenForRequest], or null.
     * @return the answer on success (for on-screen display), else null.
     *
     * Every spoken line here is [SpeechEngine.speakBlocking]: the caller flips
     * back to `WALKING` the instant this returns, and the resumed Walk loop's
     * next guidance line does a `QUEUE_FLUSH`. If we only queued speech, the
     * answer would be cut off mid-sentence and replaced by "STOP, path blocked".
     */
    suspend fun describeOnce(heard: String?, language: SpokenLanguage): Result? {
        if (rejectUnsupportedLanguage(language)) return null
        val question = heard ?: strings.string(R.string.vlm_default_prompt)
        if (heard == null && !voice.blocked()) {
            speech.speakBlocking(strings.string(R.string.vlm_no_speech), maxWaitMs = 8_000L)
        }

        // 2. "Working on it" is fire-and-forget: the capture + slow round-trip
        //    that follow always outlast it.
        speech.say(strings.string(R.string.vlm_working), flush = true)
        // 640 is already twice the model's 320 px bound, so the extra pixels
        // would only be thrown away by SceneImage.
        val jpeg = pipeline.captureStill(maxWidth = 640, quality = 85)
        if (jpeg == null) {
            speech.speakBlocking(strings.string(R.string.vlm_unavailable), maxWaitMs = 8_000L)
            return null
        }

        // 3. Answer locally. There is no "busy" state to retry: the model is
        //    loaded for this call alone and nothing else can hold it.
        return when (val attempt = answer(jpeg, question, language)) {
            is Attempt.Done -> {
                speech.speakBlocking(attempt.text)
                Result(question = question, answer = attempt.text, totalMs = attempt.totalMs)
            }
            Attempt.RetryOnce, Attempt.GiveUp -> null // already voiced (blocking)
        }
    }

    private suspend fun answer(
        jpeg: ByteArray,
        prompt: String,
        language: SpokenLanguage,
    ): Attempt {
        val model = vlm ?: run {
            speech.speakBlocking(strings.string(R.string.vlm_unavailable), maxWaitMs = 8_000L)
            return Attempt.GiveUp
        }

        val image = SceneImage.fromJpeg(jpeg)
        if (image == null) {
            speech.speakBlocking(strings.string(R.string.vlm_unavailable), maxWaitMs = 8_000L)
            return Attempt.GiveUp
        }

        // Inference is blocking native work; keep it off the caller's thread.
        // On timeout we raise the cancel flag and then WAIT for the native
        // call to come back — it polls the flag between graph nodes, so this
        // is bounded — rather than abandoning a thread that still owns ~330 MB.
        // The Class B contract is that the model is gone when this returns.
        val started = System.nanoTime()
        // Deliberately not a child of this coroutine: the timeout must be able
        // to give up on the *wait* while the native call keeps running to its
        // (cancelled) end, so it lives in its own scope.
        val inference = CoroutineScope(Dispatchers.Default).async {
            model.ask(
                image.rgb,
                image.width,
                image.height,
                prompt,
                language,
            )
        }
        val result = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { inference.await() } ?: run {
            model.cancel()
            inference.await() // returns Cancelled once the native call unwinds
            speech.speakBlocking(strings.string(R.string.vlm_timeout), maxWaitMs = 8_000L)
            return Attempt.GiveUp
        }

        return when (result) {
            is SceneVlm.Result.Answer ->
                Attempt.Done(result.text, (System.nanoTime() - started) / 1_000_000.0)

            is SceneVlm.Result.NotEnoughMemory -> {
                // Say what is true. Refusing loudly is the contract; pretending
                // the scene is empty is the failure this exists to avoid.
                speech.speakBlocking(strings.string(R.string.vlm_low_memory), maxWaitMs = 8_000L)
                Attempt.GiveUp
            }

            SceneVlm.Result.Cancelled -> Attempt.GiveUp

            SceneVlm.Result.ModelMissing, SceneVlm.Result.Failed -> {
                speech.speakBlocking(strings.string(R.string.vlm_unavailable), maxWaitMs = 8_000L)
                Attempt.GiveUp
            }
        }
    }

    private companion object {
        /**
         * Generous against the measured 1.4 - 2 s answer, because the floor is
         * not the worry: a cold CPU governor after thermal throttling produced a
         * 7.4 s run (`docs/SCENE_MODE_VLM.md` §4.5). This bounds the pathological
         * case without cutting off a merely slow one.
         */
        const val ANSWER_TIMEOUT_MS = 20_000L
    }
}
