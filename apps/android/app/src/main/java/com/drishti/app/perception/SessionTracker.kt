package com.drishti.app.perception

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of `app/perception/tracking.py` (BUILD_PLAN.md task A2).
 *
 * Runs on the CPU. Deterministic: the same frames in the same order produce
 * the same track IDs.
 */
data class TrackedDetection(
    val detection: DetectionCandidate,
    val trackId: Int,
    val approachRate: Double?,
    val areaChange: Double?,
    val motionDx: Double?,
    val motionDy: Double?,
    /**
     * 0 when the detector produced this box on this frame; >0 when the track is
     * COASTING on its last observation because the detector missed it. Carried
     * so downstream can tell a measurement from a memory.
     */
    val framesSinceSeen: Int = 0,
)

private data class Track(
    val trackId: Int,
    val label: String,
    var detection: DetectionCandidate,
    val firstSeenMillis: Long,
    var lastSeenMillis: Long,
    /** Last frame this track was reported on, whether observed or coasted. */
    var lastFrameId: Int,
    /** Last frame the DETECTOR actually produced a box for it. */
    var lastSeenFrameId: Int = lastFrameId,
)

class SessionTracker(
    private val iouThreshold: Double,
    private val centreDistanceThreshold: Double,
    private val maxAgeFrames: Int,
    /**
     * How many frames an unmatched track keeps REPORTING its last box.
     *
     * `0` is the Python behaviour and what the `tracking.json` vectors pin: a
     * track survived in the table for [maxAgeFrames] so it could keep its id,
     * but it produced no output on a frame where the detector missed it, so the
     * obstacle simply vanished from the risk engine.
     *
     * That is a safety defect, and it is the mechanism behind "it sees the desk
     * sometimes". Measured over five consecutive Walk Mode frames of one static
     * desk, YOLO11n emitted `dining table` on ONE of them (0.37, just over the
     * gate) and nothing on the other four. The verdict flickered between STOP
     * and CLEAR, and because the alert state machine needs two consecutive
     * frames to commit, the STOP never even reached the user.
     *
     * An obstacle seen 150 ms ago has not stopped existing because a confidence
     * dipped. Coasting reports the last known box with a decaying confidence, so
     * it fades out over [coastFrames] instead of blinking out — and a decaying
     * confidence directly decays the corridor cost it contributes.
     */
    private val coastFrames: Int = 0,
) {
    private val tracks = LinkedHashMap<Int, Track>()
    private var nextTrackId = 1

    fun update(
        detections: List<DetectionCandidate>,
        frameId: Int,
        capturedAtMillis: Long,
    ): List<TrackedDetection> {
        val expired = tracks.filterValues { frameId - it.lastSeenFrameId > maxAgeFrames }.keys
        expired.forEach { tracks.remove(it) }

        val available = LinkedHashSet(tracks.keys)
        val output = ArrayList<TrackedDetection>(detections.size)

        for (detection in detections) {
            val track = bestMatch(detection, available)
            if (track == null) {
                val created = Track(
                    trackId = nextTrackId,
                    label = detection.label,
                    detection = detection,
                    firstSeenMillis = capturedAtMillis,
                    lastSeenMillis = capturedAtMillis,
                    lastFrameId = frameId,
                    lastSeenFrameId = frameId,
                )
                nextTrackId += 1
                tracks[created.trackId] = created
                output.add(
                    TrackedDetection(
                        detection = detection,
                        trackId = created.trackId,
                        approachRate = null,
                        areaChange = null,
                        motionDx = null,
                        motionDy = null,
                    )
                )
                continue
            }

            available.remove(track.trackId)
            val previous = track.detection
            val previousArea = area(previous)
            val currentArea = area(detection)
            val areaChange = (currentArea - previousArea) / max(previousArea, 1e-6)
            val previousCentre = centre(previous)
            val currentCentre = centre(detection)
            output.add(
                TrackedDetection(
                    detection = detection,
                    trackId = track.trackId,
                    approachRate = min(1.0, max(0.0, areaChange)),
                    areaChange = areaChange,
                    motionDx = currentCentre.first - previousCentre.first,
                    motionDy = currentCentre.second - previousCentre.second,
                )
            )
            track.detection = detection
            track.lastSeenMillis = capturedAtMillis
            track.lastFrameId = frameId
            track.lastSeenFrameId = frameId
        }

        for (trackId in available) {
            val track = tracks[trackId] ?: continue
            val age = frameId - track.lastSeenFrameId
            if (age !in 1..coastFrames) continue
            val decay = 1.0 - age.toDouble() / (coastFrames + 1)
            output.add(
                TrackedDetection(
                    detection = track.detection.copy(
                        confidence = track.detection.confidence * decay,
                    ),
                    trackId = track.trackId,
                    // Motion needs two OBSERVATIONS. Coasting has one and a
                    // guess; reporting a rate here would be inventing evidence,
                    // and `APPROACHING_VEHICLE_CENTRE` reads exactly that field.
                    approachRate = null,
                    areaChange = null,
                    motionDx = null,
                    motionDy = null,
                    framesSinceSeen = age,
                )
            )
            track.lastFrameId = frameId
        }
        return output
    }

    private fun bestMatch(
        detection: DetectionCandidate,
        availableTrackIds: Set<Int>,
    ): Track? {
        var bestScore = Double.NEGATIVE_INFINITY
        var best: Track? = null
        for (trackId in availableTrackIds) {
            val track = tracks[trackId] ?: continue
            if (track.label != detection.label) continue
            val overlap = iou(track.detection, detection)
            val distance = centreDistance(track.detection, detection)
            if (overlap < iouThreshold && distance > centreDistanceThreshold) continue
            val score = overlap + max(0.0, 1.0 - distance / centreDistanceThreshold) * 0.1
            if (best == null || score > bestScore) {
                bestScore = score
                best = track
            }
        }
        return best
    }
}

internal fun area(detection: DetectionCandidate): Double =
    (detection.x2 - detection.x1) * (detection.y2 - detection.y1)

internal fun centre(detection: DetectionCandidate): Pair<Double, Double> =
    Pair((detection.x1 + detection.x2) / 2, (detection.y1 + detection.y2) / 2)

internal fun centreDistance(left: DetectionCandidate, right: DetectionCandidate): Double {
    val l = centre(left)
    val r = centre(right)
    return hypot(l.first - r.first, l.second - r.second)
}

internal fun iou(left: DetectionCandidate, right: DetectionCandidate): Double {
    val width = max(0.0, min(left.x2, right.x2) - max(left.x1, right.x1))
    val height = max(0.0, min(left.y2, right.y2) - max(left.y1, right.y1))
    val intersection = width * height
    val union = area(left) + area(right) - intersection
    return if (union > 0) intersection / union else 0.0
}
