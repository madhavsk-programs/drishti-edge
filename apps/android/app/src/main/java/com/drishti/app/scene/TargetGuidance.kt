package com.drishti.app.scene

import com.drishti.app.net.NormalizedPoint
import com.drishti.app.net.TargetGuidanceStep
import com.drishti.app.net.TargetHapticPattern
import com.drishti.app.net.TargetRangeHint
import com.drishti.app.net.TargetTrackingState
import com.drishti.app.net.TargetTrackingTelemetry
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.Landmark
import com.drishti.app.perception.NormalizedBox
import com.drishti.app.perception.labelsMatch
import com.drishti.app.perception.wrap180
import kotlin.math.abs

/**
 * Kotlin port of `app/guidance/target_guidance.py` (BUILD_PLAN.md task A10):
 * turn-by-turn guidance to a locked target, on the phone, with no VLM in the
 * path (ARCHITECTURE.md §14).
 *
 * Perception and safety stay external. Each walk frame calls [step] with the
 * full COCO view and whether a safety action is active; when it is, this
 * returns a telemetry with `isSafetyOverridden = true`, blank speech and no
 * haptic, so a target cue can never preempt or delay a safety instruction
 * (§3.2, §13.4). The cue is dropped, not queued.
 *
 * Spoken lines are chosen by the caller from [TargetTrackingTelemetry.guidanceStep]
 * so they can be localised; `speech` in the returned telemetry is left empty.
 */
class TargetGuidance(
    private val cameraHfovDegrees: Double = WALK_CAMERA_HFOV_DEGREES,
    private val turnThresholdDegrees: Double = 25.0,
    private val faceToleranceDegrees: Double = 10.0,
    private val reacquireTimeoutMs: Long = 8_000,
    private val arrivedDwellMs: Long = 2_000,
    private val speechIntervalMs: Long = 4_000,
    private val minConfidence: Double = 0.45,
) {
    private class Session(
        var state: TargetTrackingState = TargetTrackingState.IDLE,
        var targetName: String? = null,
        var landmark: Landmark? = null,
        var guidanceStep: TargetGuidanceStep = TargetGuidanceStep.NONE,
        var targetCenter: NormalizedPoint? = null,
        var rangeHint: TargetRangeHint = TargetRangeHint.UNKNOWN,
        var bearingDegrees: Double? = null,
        var lastRangeHint: TargetRangeHint = TargetRangeHint.UNKNOWN,
        var lastSpokenMs: Long? = null,
        var arrivedSinceMs: Long? = null,
        var lostAnnouncementPending: Boolean = false,
    )

    private var session = Session()

    val state: TargetTrackingState get() = session.state
    val targetName: String? get() = session.targetName

    /** Drop any active target. Called when the walk session ends. */
    fun reset() { session = Session() }

    fun beginSeeking(targetName: String) {
        session = Session(state = TargetTrackingState.SEEKING, targetName = targetName)
    }

    /**
     * A locate miss. The locator has already spoken it ("I could not find
     * the X"), so nothing is scheduled here; the session simply holds no
     * target.
     */
    fun failSeeking() {
        session = Session()
    }

    /** Relative bearing to a landmark: negative = left, positive = right. */
    data class Seed(
        val state: TargetTrackingState,
        val bearingDegrees: Double?,
        val rangeHint: TargetRangeHint,
        val targetCenter: NormalizedPoint?,
    )

    /**
     * Lock onto [landmark]. [visible] says whether it is in the current frame;
     * an out-of-view landmark starts in SEEKING and is guided by its stored
     * world bearing until it is reacquired.
     */
    fun startGuidance(
        targetName: String,
        landmark: Landmark,
        headingDegrees: Double?,
        visible: Boolean,
    ): Seed {
        val center = if (visible) NormalizedPoint(landmark.lastCenterX, landmark.lastBox.centerY) else null
        val bearing = relativeBearing(landmark, headingDegrees, center)
        session = Session(
            state = if (visible) TargetTrackingState.GUIDING else TargetTrackingState.SEEKING,
            targetName = targetName,
            landmark = landmark,
            targetCenter = center,
            rangeHint = rangeHint(landmark.lastBox),
            bearingDegrees = bearing,
        )
        return Seed(session.state, bearing, session.rangeHint, center)
    }

    fun step(
        nowMs: Long,
        headingDegrees: Double?,
        detections: List<DetectionCandidate>,
        isSafetyOverridden: Boolean,
        hapticsEnabled: Boolean,
    ): TargetTrackingTelemetry {
        val s = session
        if (isSafetyOverridden) return telemetry(s, isSafetyOverridden = true)
        if (s.state == TargetTrackingState.ARRIVED) {
            session = Session()
            return telemetry(session)
        }
        if (s.state == TargetTrackingState.IDLE) return telemetry(s)

        val landmark = s.landmark
        if (landmark == null) {
            val speak = s.lostAnnouncementPending
            s.lostAnnouncementPending = false
            return telemetry(s, speak = speak, step = if (speak) TargetGuidanceStep.REACQUIRE else s.guidanceStep)
        }

        val match = latestMatch(s.targetName ?: landmark.label, detections)
        if (match != null) {
            val box = NormalizedBox(match.x1, match.y1, match.x2, match.y2)
            val center = NormalizedPoint(box.centerX, box.centerY)
            val worldBearing = headingDegrees?.let { wrap180(it + (center.x - 0.5) * cameraHfovDegrees) }
                ?: landmark.worldBearingDeg
            s.landmark = landmark.copy(
                worldBearingDeg = worldBearing,
                lastCenterX = center.x,
                lastBoxH = box.height,
                lastBoxBottom = box.y2,
                lastBox = box,
                lastSeenMs = nowMs,
                sightings = landmark.sightings + 1,
            )
            s.targetCenter = center
            s.rangeHint = rangeHint(box)
            s.state = TargetTrackingState.GUIDING
        } else {
            s.targetCenter = null
        }

        val current = s.landmark!!
        val elapsed = nowMs - current.lastSeenMs
        if (match == null && elapsed > reacquireTimeoutMs) {
            val changed = s.guidanceStep != TargetGuidanceStep.REACQUIRE
            s.state = TargetTrackingState.LOST
            s.guidanceStep = TargetGuidanceStep.REACQUIRE
            s.arrivedSinceMs = null
            return telemetry(s, speak = changed, lost = true)
        }

        val bearing = relativeBearing(current, headingDegrees, s.targetCenter)
        s.bearingDegrees = bearing
        val step = selectStep(s, bearing, nowMs)
        val changed = step != s.guidanceStep
        s.guidanceStep = step
        var speak = changed || (
            step != TargetGuidanceStep.REACQUIRE &&
                s.lastSpokenMs != null &&
                nowMs - s.lastSpokenMs!! >= speechIntervalMs
            )
        if (s.lastSpokenMs == null) speak = true
        if (speak) s.lastSpokenMs = nowMs
        val haptic = if (hapticsEnabled) haptic(step, bearing) else TargetHapticPattern.NONE
        val out = telemetry(s, bearing = bearing, speak = speak, haptic = haptic)
        s.lastRangeHint = s.rangeHint
        return out
    }

    private fun selectStep(s: Session, bearing: Double?, nowMs: Long): TargetGuidanceStep {
        if (bearing == null) {
            s.state = TargetTrackingState.SEEKING
            return TargetGuidanceStep.REACQUIRE
        }
        val magnitude = abs(bearing)
        if (magnitude > turnThresholdDegrees) {
            s.arrivedSinceMs = null
            return if (bearing > 0) TargetGuidanceStep.TURN_RIGHT else TargetGuidanceStep.TURN_LEFT
        }
        if (magnitude > faceToleranceDegrees) {
            s.arrivedSinceMs = null
            return TargetGuidanceStep.KEEP_TURNING
        }
        if (s.rangeHint == TargetRangeHint.NEAR) {
            if (s.arrivedSinceMs == null) s.arrivedSinceMs = nowMs
            if (nowMs - s.arrivedSinceMs!! >= arrivedDwellMs) {
                s.state = TargetTrackingState.ARRIVED
                return TargetGuidanceStep.ARRIVED
            }
        } else {
            s.arrivedSinceMs = null
        }
        s.state = TargetTrackingState.GUIDING
        if (s.lastRangeHint != TargetRangeHint.UNKNOWN && rangeRank(s.rangeHint) > rangeRank(s.lastRangeHint)) {
            return TargetGuidanceStep.WALKING
        }
        return TargetGuidanceStep.FACE_AND_WALK
    }

    private fun relativeBearing(
        landmark: Landmark,
        headingDegrees: Double?,
        targetCenter: NormalizedPoint?,
    ): Double? {
        if (headingDegrees != null && landmark.worldBearingDeg != null) {
            return wrap180(landmark.worldBearingDeg - headingDegrees)
        }
        if (targetCenter != null) return (targetCenter.x - 0.5) * cameraHfovDegrees
        return null
    }

    /**
     * Strongest live box for the target. The full COCO stream carries the
     * detector's whole low-confidence tail, so re-acquisition applies the
     * same floor landmark memory does — otherwise a weak false positive could
     * pull guidance off the real target.
     */
    private fun latestMatch(targetName: String, detections: List<DetectionCandidate>): DetectionCandidate? =
        detections
            .filter { it.confidence >= minConfidence && labelsMatch(targetName, it.label) }
            .maxByOrNull { it.confidence }

    private fun telemetry(
        s: Session,
        isSafetyOverridden: Boolean = false,
        bearing: Double? = null,
        speak: Boolean = false,
        haptic: TargetHapticPattern = TargetHapticPattern.NONE,
        step: TargetGuidanceStep = s.guidanceStep,
        lost: Boolean = false,
    ): TargetTrackingTelemetry {
        val center = s.targetCenter
        return TargetTrackingTelemetry(
            trackingState = s.state,
            targetName = s.targetName,
            guidanceStep = if (lost) TargetGuidanceStep.REACQUIRE else step,
            bearingDegrees = bearing ?: s.bearingDegrees,
            rangeHint = s.rangeHint,
            targetCenter = center,
            confidence = null,
            isSafetyOverridden = isSafetyOverridden,
            speech = "",
            speak = speak && !isSafetyOverridden,
            hapticPattern = if (isSafetyOverridden) TargetHapticPattern.NONE else haptic,
        )
    }

    companion object {
        /** ARCHITECTURE.md §14.3 `walk_camera_hfov_degrees`. */
        const val WALK_CAMERA_HFOV_DEGREES = 67.0

        fun rangeHint(box: NormalizedBox): TargetRangeHint {
            if (box.height >= 0.55 || box.y2 >= 0.90) return TargetRangeHint.NEAR
            if (box.height <= 0.15) return TargetRangeHint.FAR
            return TargetRangeHint.MID
        }

        private fun rangeRank(value: TargetRangeHint): Int = when (value) {
            TargetRangeHint.UNKNOWN -> 0
            TargetRangeHint.FAR -> 1
            TargetRangeHint.MID -> 2
            TargetRangeHint.NEAR -> 3
        }

        private fun haptic(step: TargetGuidanceStep, bearing: Double?): TargetHapticPattern = when (step) {
            TargetGuidanceStep.TURN_LEFT, TargetGuidanceStep.TURN_RIGHT, TargetGuidanceStep.KEEP_TURNING ->
                if ((bearing ?: 0.0) > 0) TargetHapticPattern.TARGET_RIGHT_PULSE else TargetHapticPattern.TARGET_LEFT_PULSE
            TargetGuidanceStep.FACE_AND_WALK, TargetGuidanceStep.WALKING, TargetGuidanceStep.ARRIVED ->
                TargetHapticPattern.TARGET_CENTRE_PULSE
            else -> TargetHapticPattern.NONE
        }
    }
}
