package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.ApproachState
import com.drishti.app.net.ProximityBand
import com.drishti.app.perception.DetectionCandidate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kotlin port of `app/spatial/proximity.py` (BUILD_PLAN.md task A3).
 *
 * This is a RELATIVE proximity signal, never a distance. `docs/SAFETY_RULES.md`
 * forbids stating distance in absolute units, and nothing here can produce one.
 */
data class RelativeProximity(
    val score: Double,
    val band: ProximityBand,
)

fun estimateRelativeProximity(
    detection: DetectionCandidate,
    settings: PipelineSettings,
): RelativeProximity {
    val area = (detection.x2 - detection.x1) * (detection.y2 - detection.y1)
    val areaSignal = min(1.0, sqrt(max(0.0, area)) / settings.proximityAreaScale)
    val lowerEdgeWeight = 1.0 - settings.proximityAreaWeight
    val score = min(
        1.0,
        max(
            0.0,
            settings.proximityAreaWeight * areaSignal + lowerEdgeWeight * detection.y2,
        ),
    )
    val band = when {
        score < settings.proximityFarThreshold -> ProximityBand.FAR
        score < settings.proximityMediumThreshold -> ProximityBand.MEDIUM
        score < settings.proximityNearThreshold -> ProximityBand.NEAR
        else -> ProximityBand.IMMEDIATE
    }
    return RelativeProximity(score = score, band = band)
}

fun classifyApproach(areaChange: Double?, threshold: Double): ApproachState = when {
    areaChange == null -> ApproachState.UNKNOWN
    areaChange >= threshold -> ApproachState.APPROACHING
    areaChange <= -threshold -> ApproachState.RECEDING
    else -> ApproachState.STATIONARY
}
