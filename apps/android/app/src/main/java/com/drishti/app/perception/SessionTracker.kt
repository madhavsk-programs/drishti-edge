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
)

private data class Track(
    val trackId: Int,
    val label: String,
    var detection: DetectionCandidate,
    val firstSeenMillis: Long,
    var lastSeenMillis: Long,
    var lastFrameId: Int,
)

class SessionTracker(
    private val iouThreshold: Double,
    private val centreDistanceThreshold: Double,
    private val maxAgeFrames: Int,
) {
    private val tracks = LinkedHashMap<Int, Track>()
    private var nextTrackId = 1

    fun update(
        detections: List<DetectionCandidate>,
        frameId: Int,
        capturedAtMillis: Long,
    ): List<TrackedDetection> {
        val expired = tracks.filterValues { frameId - it.lastFrameId > maxAgeFrames }.keys
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
