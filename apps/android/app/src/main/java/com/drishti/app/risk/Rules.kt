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
 * The decision cascade, grown from `app/risk/rules.py` (BUILD_PLAN.md task A4).
 *
 * The ORDER of the cascade is the safety contract. Reordering these branches
 * changes what the system says to a blind user, so the sequence is the Python
 * sequence and the golden vectors pin every branch of it.
 *
 * One thing has been ADDED rather than reordered: a corridor with no visible
 * floor running ahead now counts as blocked (see [noFloorAhead]). It feeds the
 * existing left/centre/right blocked flags, so no branch moved and every
 * `risk.json` vector still holds.
 */

/**
 * Labels that can raise `APPROACHING_VEHICLE_CENTRE`, the one CRITICAL branch
 * that bypasses the alert cooldown. `truck` and `train` belong here for the
 * obvious reason and were missing.
 */
val VEHICLE_LABELS: Set<String> =
    setOf("bicycle", "motorcycle", "car", "bus", "truck", "train")

data class ProposedDecision(
    val action: GuidanceAction,
    val level: RiskLevel,
    val reasonCode: String,
    val preferredCorridor: CorridorChoice,
    val evidenceScore: Double = 0.0,
    val criticalTrackIds: Set<Int> = emptySet(),
    /**
     * Canonical label of the obstacle this decision is ABOUT, when one object
     * is responsible for it. "Stop" tells a blind user to halt; "stop, chair
     * directly ahead" tells them what they are about to walk into and lets them
     * reach for it, step around it, or recognise the room. Null for verdicts
     * that are not about a single object — a wall, stairs, an uncertain surface.
     */
    val blockingLabel: String? = null,
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
            blockingLabel = assessments
                .filter { isCriticalApproachingVehicle(it, settings) }
                .maxByOrNull { it.score }
                ?.spatial?.tracked?.detection?.label,
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
        noFloorAhead(corridor, CorridorChoice.CENTRE, settings) ||
        centreAssessments.any {
            it.level in setOf(RiskLevel.WARN, RiskLevel.HIGH) &&
                it.spatial.proximity.band in setOf(ProximityBand.NEAR, ProximityBand.IMMEDIATE)
        }
    val sideBlockThreshold = sideBlockThreshold(corridor, settings)
    val leftBlocked = corridor.costs.leftCost >= sideBlockThreshold ||
        noFloorAhead(corridor, CorridorChoice.LEFT, settings)
    val rightBlocked = corridor.costs.rightCost >= sideBlockThreshold ||
        noFloorAhead(corridor, CorridorChoice.RIGHT, settings)

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
            blockingLabel = blockingLabel(centreAssessments, assessments),
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
                blockingLabel = blockingLabel(centreAssessments, assessments),
            )
        }
        // Uncertainty and danger are different answers (docs/SAFETY_RULES.md).
        return ProposedDecision(
            action = GuidanceAction.PAUSE_UNCLEAR,
            level = RiskLevel.WARN,
            reasonCode = "CENTRE_BLOCKED_DIRECTION_UNCLEAR",
            preferredCorridor = CorridorChoice.NONE,
            evidenceScore = highestScore,
            blockingLabel = blockingLabel(centreAssessments, assessments),
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
            blockingLabel = highest.spatial.tracked.detection.label,
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

/**
 * How much cost it takes to condemn an escape route.
 *
 * [PipelineSettings.riskSideBlockThreshold] sits above the centre gate because
 * losing a side turns a steerable frame into STOP, and corridor cost compounds
 * as a noisy-OR — several moderate contributions reach 0.49 on a corridor that
 * segmentation still reads as open floor.
 *
 * That generosity is bought from segmentation: it is only safe to be slower to
 * condemn a side when something can still show the side is clear. With no
 * surface evidence there is nothing to show it, the free-space rule below is
 * disabled for the same reason, and the only signal left is the very cost being
 * discounted — so the gate narrows back to the centre's.
 */
private fun sideBlockThreshold(
    corridor: CorridorAnalysis,
    settings: PipelineSettings,
): Double = if (corridor.hasSurfaces) {
    settings.riskSideBlockThreshold
} else {
    settings.riskCentreBlockThreshold
}

/**
 * No visible floor running ahead in this corridor.
 *
 * Free space is the one blocking signal that does not depend on NAMING the
 * obstacle, and until now the only rule that read it was `wallDeadEnd`, gated on
 * `wallRatio >= 0.35`. So a chair, a desk, a parked car or a crowd could reduce
 * the floor ahead to nothing and the cascade still had no branch to take: none
 * of them is a wall. This closes that gap, and it does so by feeding the
 * EXISTING left/centre/right blocked flags rather than adding a fourth STOP
 * branch — a corridor with no floor ahead is blocked, and the audited cascade
 * already knows what to do with a blocked corridor.
 *
 * Gated on [CorridorAnalysis.hasSurfaces]: without segmentation the extents are
 * zeros, and reading those as "no floor ahead" would STOP the user on an empty
 * pavement (docs/SAFETY_RULES.md — uncertainty and danger are different
 * answers).
 */
private fun noFloorAhead(
    corridor: CorridorAnalysis,
    choice: CorridorChoice,
    settings: PipelineSettings,
): Boolean = corridor.hasSurfaces &&
    corridor.floorExtents.valueFor(choice) <= settings.freespaceBlockedMax

/**
 * The object a blocked-path verdict is about: the worst thing actually in the
 * centre of the path, falling back to the worst thing seen at all. Null when
 * the corridor is blocked by surfaces rather than by anything the detector
 * named — the cascade then says "path blocked", which is all it can defend.
 */
private fun blockingLabel(
    centreAssessments: List<RiskAssessment>,
    assessments: List<RiskAssessment>,
): String? = (
    centreAssessments.maxByOrNull { it.score } ?: assessments.maxByOrNull { it.score }
    )?.spatial?.tracked?.detection?.label

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
