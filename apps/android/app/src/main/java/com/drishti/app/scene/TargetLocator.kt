package com.drishti.app.scene

import com.drishti.app.R
import com.drishti.app.feedback.GuidanceStrings
import com.drishti.app.feedback.SpeechEngine
import com.drishti.app.net.TargetRangeHint
import com.drishti.app.perception.Landmark
import com.drishti.app.perception.NormalizedBox
import com.drishti.app.perception.labelsMatch
import com.drishti.app.perception.normalizeLabel
import com.drishti.app.walk.LocalWalkPipeline
import kotlin.math.abs

/**
 * "Ask -> Lock", resolved **on the phone** (ARCHITECTURE.md §14.1).
 *
 * Resolution order: reject `person`; search the session's landmark memory,
 * which the walk loop has been filling from the full COCO view at no extra
 * inference; then the live view of the last frame. No VLM in the path. A miss
 * is spoken as a miss — "I can't find that" is a correct answer, and guiding
 * toward a guess is not.
 *
 * On a hit the target is handed to [TargetGuidance]; per-frame turn-by-turn
 * cues then flow through `targetTracking` on every walk frame, subordinate to
 * the safety verdict.
 */
class TargetLocator(
    private val speech: SpeechEngine,
    private val strings: GuidanceStrings,
) {
    sealed interface Outcome {
        data class Locked(val fromMemory: Boolean, val bearingDegrees: Double?) : Outcome
        data object Refused : Outcome
        data object NotFound : Outcome
        data object Unavailable : Outcome
    }

    /**
     * @param pipeline the running walk pipeline, or null when no session is
     *   active. Must be called under the same lock that serialises
     *   [LocalWalkPipeline.process]; the walk loop is paused during Ask, so in
     *   practice this only waits for an in-flight frame.
     * @param headingDegrees current device heading, if the sensor has one.
     */
    suspend fun locateOnce(
        target: String,
        pipeline: LocalWalkPipeline?,
        headingDegrees: Double?,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome {
        speech.say(strings.string(R.string.locate_working, target), flush = true)

        if (pipeline == null) {
            speech.speakBlocking(strings.string(R.string.locate_unavailable), maxWaitMs = 8_000L)
            return Outcome.Unavailable
        }

        if (normalizeLabel(target) == "person" || labelsMatch(target, "person")) {
            speech.speakBlocking(strings.string(R.string.locate_person_refused), maxWaitMs = 8_000L)
            pipeline.targetGuidance.failSeeking()
            return Outcome.Refused
        }

        pipeline.targetGuidance.beginSeeking(target)

        // 1. Multi-frame landmark memory: seen at least twice, within TTL.
        var landmark = pipeline.landmarks.resolve(target, nowMs)
        var fromMemory = true

        // 2. The live view: something that has only just entered the frame
        //    and has not yet earned its second sighting.
        if (landmark == null) {
            val live = pipeline.lastFullView
                .filter { it.confidence >= LIVE_MIN_CONFIDENCE && labelsMatch(target, it.label) }
                .maxByOrNull { it.confidence }
            if (live != null) {
                val box = NormalizedBox(live.x1, live.y1, live.x2, live.y2)
                landmark = Landmark(
                    label = normalizeLabel(live.label),
                    worldBearingDeg = headingDegrees?.let {
                        com.drishti.app.perception.wrap180(
                            it + (box.centerX - 0.5) * TargetGuidance.WALK_CAMERA_HFOV_DEGREES,
                        )
                    },
                    lastCenterX = box.centerX,
                    lastBoxH = box.height,
                    lastBoxBottom = box.y2,
                    lastBox = box,
                    firstSeenMs = pipeline.lastFrameMillis,
                    lastSeenMs = pipeline.lastFrameMillis,
                    sightings = 1,
                )
                fromMemory = false
            }
        }

        if (landmark == null) {
            speech.speakBlocking(strings.string(R.string.locate_not_found, target), maxWaitMs = 8_000L)
            pipeline.targetGuidance.failSeeking()
            return Outcome.NotFound
        }

        // "Visible" means the memory entry is from the last frame or two; an
        // older sighting is guided by its stored world bearing until the
        // detector sees it again.
        val visible = nowMs - landmark.lastSeenMs <= VISIBLE_WINDOW_MS
        val seed = pipeline.targetGuidance.startGuidance(target, landmark, headingDegrees, visible)
        speech.speakBlocking(guidanceText(target, seed.bearingDegrees, seed.rangeHint), maxWaitMs = 8_000L)
        return Outcome.Locked(fromMemory, seed.bearingDegrees)
    }

    /** Port of `guidance_text`: the one-shot confirmation after a lock. */
    private fun guidanceText(target: String, bearing: Double?, range: TargetRangeHint): String {
        val name = target.replaceFirstChar { it.uppercase() }
        if (bearing == null) return strings.string(R.string.locate_found_scan, name)
        if (abs(bearing) <= 10.0) {
            return if (range == TargetRangeHint.NEAR) {
                strings.string(R.string.locate_ahead_near, name)
            } else {
                strings.string(R.string.locate_ahead, name)
            }
        }
        val side = strings.string(if (bearing > 0) R.string.side_right else R.string.side_left)
        if (abs(bearing) >= 135.0) return strings.string(R.string.locate_behind, name, side)
        return strings.string(R.string.locate_side, name, side)
    }

    private companion object {
        /** Same floor as landmark memory; the live view carries the full tail. */
        const val LIVE_MIN_CONFIDENCE = 0.45
        const val VISIBLE_WINDOW_MS = 1_500L
    }
}
