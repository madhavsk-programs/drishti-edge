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
    /**
     * Best confidence this track has ever had for the name it is reporting.
     *
     * [DetectionCandidate.confidence] is how sure the detector is on THIS frame,
     * which is what a corridor cost should be built from. This is how well the
     * NAME is supported over the object's whole life, which is what should
     * decide whether the name is worth saying out loud. They come apart exactly
     * when it matters: an office chair recognised at 0.80 across the room is
     * still the same chair when it fills the lens and the detector drops to
     * 0.41 and calls it a suitcase.
     *
     * 0.0 on a value built by hand; readers fall back to the detection's own
     * confidence, which is what a single unmatched observation is worth.
     */
    val labelConfidence: Double = 0.0,
)

/** Everything one track has been called, and how strongly. */
private class LabelVotes {
    private val total = HashMap<String, Double>()
    private val best = HashMap<String, Double>()

    fun add(label: String, confidence: Double) {
        total[label] = (total[label] ?: 0.0) + confidence
        best[label] = maxOf(best[label] ?: 0.0, confidence)
    }

    /** The name the detector has argued for hardest, summed over every frame. */
    fun winner(): String = total.maxByOrNull { it.value }!!.key

    fun bestConfidenceFor(label: String): Double = best[label] ?: 0.0
}

private data class Track(
    val trackId: Int,
    var detection: DetectionCandidate,
    val firstSeenMillis: Long,
    var lastSeenMillis: Long,
    /** Last frame this track was reported on, whether observed or coasted. */
    var lastFrameId: Int,
    /** Last frame the DETECTOR actually produced a box for it. */
    var lastSeenFrameId: Int = lastFrameId,
) {
    val votes = LabelVotes()
    /** The name this track reports: the winner of [votes], not the last guess. */
    var label: String = detection.label
        private set

    fun vote(candidate: DetectionCandidate) {
        votes.add(candidate.label, candidate.confidence)
        label = votes.winner()
    }

    fun labelConfidence(): Double = votes.bestConfidenceFor(label)

    /** The candidate as this track will report it: under the voted name. */
    fun rename(candidate: DetectionCandidate): DetectionCandidate =
        if (candidate.label == label) candidate else candidate.copy(label = label)
}

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
    /**
     * How much a box must overlap an existing track before it is allowed to
     * join it under a DIFFERENT name. `null` disables cross-name association
     * and is the Python behaviour the `tracking.json` vectors pin, including
     * `new_id_when_label_differs`.
     *
     * That behaviour is the mechanism behind "the chair shows as a suitcase".
     * YOLO11n recognises an office chair perfectly well at walking distance —
     * 0.64 and 0.80 on two chairs in one captured frame — and as the user closes
     * in and the chair back fills the lens, a large dark rounded rectangle, it
     * drops to `suitcase` at 0.37-0.41. With names gating association, that is
     * not the same object changing its mind: it is a brand new track with no
     * history, so every frame of careful recognition is thrown away at exactly
     * the moment the obstacle matters most.
     *
     * A name is a guess about an object; the object is the thing that persists.
     * So association is geometric, and the name is a VOTE over the track life
     * (see [LabelVotes]). The gate is deliberately much stricter than the
     * same-name one: a differently-named box has to be essentially the same box
     * before it is treated as the same thing, and same-name matches are taken
     * first, so an overlapping person and chair still end up as two tracks.
     */
    private val crossLabelIouThreshold: Double? = 0.60,
    /** See [com.drishti.app.config.PipelineSettings.trackMotionMaxGapFrames]. */
    private val motionMaxGapFrames: Int = 3,
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

        // Same name first, greedily and in detection order, so a box that has a
        // track of its own name can never be stolen by the looser pass below.
        val matched = arrayOfNulls<Track>(detections.size)
        for ((index, detection) in detections.withIndex()) {
            val track = bestMatch(detection, available) ?: continue
            matched[index] = track
            available.remove(track.trackId)
        }
        crossLabelIouThreshold?.let { threshold ->
            for ((index, detection) in detections.withIndex()) {
                if (matched[index] != null) continue
                val track = bestOverlap(detection, available, threshold) ?: continue
                matched[index] = track
                available.remove(track.trackId)
            }
        }

        for ((index, detection) in detections.withIndex()) {
            val track = matched[index]
            if (track == null) {
                val created = Track(
                    trackId = nextTrackId,
                    detection = detection,
                    firstSeenMillis = capturedAtMillis,
                    lastSeenMillis = capturedAtMillis,
                    lastFrameId = frameId,
                    lastSeenFrameId = frameId,
                )
                created.vote(detection)
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
                        labelConfidence = created.labelConfidence(),
                    )
                )
                continue
            }

            val previous = track.detection
            val previousArea = area(previous)
            val currentArea = area(detection)
            val areaChange = (currentArea - previousArea) / max(previousArea, 1e-6)
            val previousCentre = centre(previous)
            val currentCentre = centre(detection)
            // Motion is a PER-FRAME rate. Across a long gap the two observations
            // are simply too far apart to divide, so it is unknown rather than
            // overstated — the same rule coasting follows below.
            val measurable = frameId - track.lastSeenFrameId <= motionMaxGapFrames
            track.vote(detection)
            val reported = track.rename(detection)
            output.add(
                TrackedDetection(
                    detection = reported,
                    trackId = track.trackId,
                    approachRate = if (measurable) min(1.0, max(0.0, areaChange)) else null,
                    areaChange = if (measurable) areaChange else null,
                    motionDx = if (measurable) {
                        currentCentre.first - previousCentre.first
                    } else {
                        null
                    },
                    motionDy = if (measurable) {
                        currentCentre.second - previousCentre.second
                    } else {
                        null
                    },
                    labelConfidence = track.labelConfidence(),
                )
            )
            track.detection = reported
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
                    labelConfidence = track.labelConfidence(),
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

    /** Pure geometry: the available track this box most nearly IS. */
    private fun bestOverlap(
        detection: DetectionCandidate,
        availableTrackIds: Set<Int>,
        threshold: Double,
    ): Track? {
        var bestOverlap = threshold
        var best: Track? = null
        for (trackId in availableTrackIds) {
            val track = tracks[trackId] ?: continue
            val overlap = iou(track.detection, detection)
            if (overlap >= bestOverlap) {
                bestOverlap = overlap
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
