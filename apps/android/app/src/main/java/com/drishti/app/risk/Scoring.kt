package com.drishti.app.risk

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.ApproachState
import com.drishti.app.net.RiskLevel
import com.drishti.app.spatial.SpatialTrack
import com.drishti.app.spatial.classifyApproach
import kotlin.math.max
import kotlin.math.min

/** Kotlin port of `app/risk/scoring.py` (BUILD_PLAN.md task A4). */
data class RiskAssessment(
    val spatial: SpatialTrack,
    val score: Double,
    val level: RiskLevel,
    val classSeverity: Double,
    val approachState: ApproachState,
)

fun scoreTracks(
    tracks: List<SpatialTrack>,
    settings: PipelineSettings,
    riskSensitivity: Double = 0.5,
): List<RiskAssessment> {
    val sensitivityFactor = 0.75 + 0.5 * clamp(riskSensitivity)
    return tracks.map { spatial ->
        val tracked = spatial.tracked
        val detection = tracked.detection
        val classSeverity = settings.riskClassSeverities[detection.label] ?: 0.5
        val approach = clamp(tracked.approachRate ?: 0.0)
        val score = clamp(
            (
                settings.riskWeightPathOverlap * spatial.pathOverlap +
                    settings.riskWeightProximity * spatial.proximity.score +
                    settings.riskWeightApproach * approach +
                    settings.riskWeightClassSeverity * classSeverity +
                    settings.riskWeightConfidence * detection.confidence
                ) * sensitivityFactor
        )
        RiskAssessment(
            spatial = spatial,
            score = score,
            level = riskLevelForScore(score, settings),
            classSeverity = classSeverity,
            approachState = classifyApproach(
                tracked.areaChange,
                threshold = settings.approachChangeThreshold,
            ),
        )
    }
}

fun riskLevelForScore(score: Double, settings: PipelineSettings): RiskLevel = when {
    score >= settings.riskHighEnter -> RiskLevel.HIGH
    score >= settings.riskWarnEnter -> RiskLevel.WARN
    score >= settings.riskWatchEnter -> RiskLevel.WATCH
    else -> RiskLevel.CLEAR
}

internal fun clamp(value: Double): Double = min(1.0, max(0.0, value))
