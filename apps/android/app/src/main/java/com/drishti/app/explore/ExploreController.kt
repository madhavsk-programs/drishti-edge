package com.drishti.app.explore

import android.util.Log
import com.drishti.app.R
import com.drishti.app.feedback.GuidanceStrings
import com.drishti.app.feedback.SpeechEngine
import com.drishti.app.net.OcrConfidenceQualification
import com.drishti.app.net.ReadTextResponse
import com.drishti.app.walk.CameraFramePipeline

/**
 * On-demand, offline OCR. The walking inference loop continues while this reads
 * one still; safety guidance therefore remains able to pre-empt the readout.
 */
class ExploreController(
    private val pipeline: CameraFramePipeline,
    private val speech: SpeechEngine,
    private val strings: GuidanceStrings,
    private val reader: OnDeviceTextReader = OnDeviceTextReader(),
) {
    /** @return the read result on success (for on-screen display), else null. */
    suspend fun readTextOnce(): ReadTextResponse? {
        speech.say(strings.string(R.string.explore_listening), flush = true)
        val jpeg = pipeline.captureStill(maxWidth = 2048)
        if (jpeg == null) {
            speech.say(strings.string(R.string.explore_unavailable), flush = true)
            return null
        }
        return runCatching { reader.read(jpeg) }.fold(
            onSuccess = { response ->
                Log.i(
                    TAG,
                    "local OCR ready: quality=${response.confidenceQualification}, " +
                        "characters=${response.text.length}, routes=${response.routeNumbers.size}, " +
                        "decode=%.2f ms, inference=%.2f ms".format(
                            response.timings.decodeMs,
                            response.timings.ocrMs,
                        ),
                )
                announce(response)
                response
            },
            onFailure = { error ->
                Log.e(TAG, "local OCR failed", error)
                speech.say(strings.string(R.string.explore_unavailable), flush = true)
                null
            },
        )
    }

    /**
     * Blocking so the caller does not flip back to `WALKING` — and let the Walk
     * loop's next `QUEUE_FLUSH` guidance line cut the readout off — until it has
     * actually been spoken.
     */
    private suspend fun announce(res: ReadTextResponse) {
        val line = when (res.confidenceQualification) {
            OcrConfidenceQualification.NONE -> strings.string(R.string.explore_none)
            OcrConfidenceQualification.LOW -> strings.string(R.string.explore_possible, res.text)
            OcrConfidenceQualification.HIGH -> res.message
        }
        speech.speakBlocking(line, maxWaitMs = 15_000L)
        res.routeNumbers.forEach { route ->
            speech.speakBlocking(
                strings.string(R.string.explore_route, route.toCharArray().joinToString(" ")),
                maxWaitMs = 8_000L,
            )
        }
    }

    private companion object { const val TAG = "ExploreController" }
}
