package com.drishti.app.risk

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.Direction
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.ProximityBand
import com.drishti.app.net.RiskLevel
import com.drishti.app.spatial.CorridorAnalysis
import com.drishti.app.spatial.CorridorCosts
import kotlin.math.max

/**
 * Kotlin port of `app/risk/rules.py` (BUILD_PLAN.md task A4).
 *
 * The order of the cascade is the safety contract. Reordering these branches
 * changes what the system says to a blind user, so the sequence below is a
 * verbatim port and the golden vectors pin every branch.
 */

val VEHICLE_LABELS: Set<String> = setOf("bicycle", "motorcycle", "car", "bus")

data class ProposedDecision(
    val action: GuidanceAction,
    val level: RiskLevel,
    val reasonCode: String,
    val preferredCorridor: CorridorChoice,
    val evidenceScore: Double = 0.0,
    val criticalTrackIds: Set<Int> = emptySet(),
)

fun selectAction(
    assessments: List<RiskAssessment>,
    corridor: CorridorAnalysis,
    settings: PipelineSettings,
): ProposedDecision {
    val highestScore = assessments.maxOfOrNull { it.score } ?: 0.0

    val criticalVehicles = assessments
        .filter { isCriticalApproachingVehicle(it, settings) }
        .map { it.spatial.tracked.trackId }
        .toSet()
    if (criticalVehicles.isNotEmpty()) {
        return ProposedDecision(
            action = GuidanceAction.STOP,
            level = RiskLevel.CRITICAL,
            reasonCode = "APPROACHING_VEHICLE_CENTRE",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = highestScore,
            criticalTrackIds = criticalVehicles,
        )
    }

    if (corridor.wallDeadEnd) {
        return ProposedDecision(
            action = GuidanceAction.STOP,
            level = RiskLevel.HIGH,
            reasonCode = "WALL_OR_DEAD_END_AHEAD",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = max(
                corridor.wallRatios.centreCost,
                1.0 - corridor.floorExtents.centreCost,
            ),
        )
    }

    if (corridor.stairsRatios.centreCost >= settings.stairsCentreRatioThreshold) {
        return ProposedDecision(
            action = GuidanceAction.STOP,
            level = RiskLevel.HIGH,
            reasonCode = "STAIRS_OR_LEVEL_CHANGE_AHEAD",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = corridor.stairsRatios.centreCost,
        )
    }

    val centreAssessments = assessments.filter {
        it.spatial.direction == Direction.CENTRE && it.spatial.pathOverlap >= 0.25
    }
    val centreBlocked = corridor.costs.centreCost >= settings.riskCentreBlockThreshold ||
        centreAssessments.any {
            it.level in setOf(RiskLevel.WARN, RiskLevel.HIGH) &&
                it.spatial.proximity.band in setOf(ProximityBand.NEAR, ProximityBand.IMMEDIATE)
        }
    val leftBlocked = corridor.costs.leftCost >= settings.riskSideBlockThreshold
    val rightBlocked = corridor.costs.rightCost >= settings.riskSideBlockThreshold

    val immediateCentre = centreAssessments.any {
        it.spatial.proximity.band == ProximityBand.IMMEDIATE
    }

    if (centreBlocked && leftBlocked && rightBlocked) {
        return ProposedDecision(
            action = GuidanceAction.STOP,
            level = if (immediateCentre) RiskLevel.CRITICAL else RiskLevel.HIGH,
            reasonCode = "ALL_CORRIDORS_BLOCKED",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = highestScore,
            criticalTrackIds = if (immediateCentre) {
                centreAssessments.map { it.spatial.tracked.trackId }.toSet()
            } else {
                emptySet()
            },
        )
    }

    if (centreBlocked) {
        val preferred = clearerSide(corridor, settings.decisionMargin)
        if (
            preferred in setOf(CorridorChoice.LEFT, CorridorChoice.RIGHT) &&
            preferred in corridor.walkableChoices &&
            preferred !in corridor.uncertainChoices &&
            corridorValue(corridor.floorExtents, preferred) >= settings.directionMinFreeExtent &&
            corridorValue(corridor.wallRatios, preferred) < settings.wallSideRatioThreshold
        ) {
            return ProposedDecision(
                action = if (preferred == CorridorChoice.LEFT) {
                    GuidanceAction.MOVE_LEFT
                } else {
                    GuidanceAction.MOVE_RIGHT
                },
                level = RiskLevel.HIGH,
                reasonCode = "CENTRE_BLOCKED_CLEARER_SIDE",
                preferredCorridor = preferred,
                evidenceScore = highestScore,
            )
        }
        // Uncertainty and danger are different answers (docs/SAFETY_RULES.md).
        return ProposedDecision(
            action = GuidanceAction.PAUSE_UNCLEAR,
            level = RiskLevel.WARN,
            reasonCode = "CENTRE_BLOCKED_DIRECTION_UNCLEAR",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = highestScore,
        )
    }

    if (CorridorChoice.CENTRE in corridor.uncertainChoices) {
        return ProposedDecision(
            action = GuidanceAction.PAUSE_UNCLEAR,
            level = RiskLevel.WARN,
            reasonCode = "CENTRE_SURFACE_UNCERTAIN",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = highestScore,
        )
    }

    val highest = assessments.maxByOrNull { it.score }
    if (highest != null && highest.level in setOf(RiskLevel.WARN, RiskLevel.HIGH)) {
        return ProposedDecision(
            action = GuidanceAction.CAUTION,
            level = RiskLevel.WARN,
            reasonCode = "OBSTACLE_NEARBY",
            preferredCorridor = CorridorChoice.CENTRE,
            evidenceScore = highestScore,
        )
    }
    return ProposedDecision(
        action = GuidanceAction.CLEAR,
        level = highest?.level ?: RiskLevel.CLEAR,
        reasonCode = if (highest != null) "LOW_RISK_MONITORED" else "PATH_CLEAR",
        preferredCorridor = CorridorChoice.CENTRE,
        evidenceScore = highestScore,
    )
}

private fun clearerSide(corridor: CorridorAnalysis, decisionMargin: Double): CorridorChoice {
    val left = corridor.costs.leftCost
    val right = corridor.costs.rightCost
    if (left + decisionMargin < right) return CorridorChoice.LEFT
    if (right + decisionMargin < left) return CorridorChoice.RIGHT
    return CorridorChoice.NONE
}

private fun isCriticalApproachingVehicle(
    assessment: RiskAssessment,
    settings: PipelineSettings,
): Boolean {
    val spatial = assessment.spatial
    return spatial.tracked.detection.label in VEHICLE_LABELS &&
        spatial.direction == Direction.CENTRE &&
        assessment.approachState == com.drishti.app.net.ApproachState.APPROACHING &&
        (spatial.tracked.approachRate ?: 0.0) >= settings.riskCriticalApproach &&
        spatial.pathOverlap >= settings.riskCriticalPathOverlap &&
        spatial.proximity.score >= settings.riskCriticalProximity
}

private fun corridorValue(values: CorridorCosts, choice: CorridorChoice): Double =
    values.valueFor(choice)
