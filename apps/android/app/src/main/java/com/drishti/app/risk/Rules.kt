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
        val preferred = clearerSide(corridor, settings)
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
    val subject = cautionSubject(highest, centreAssessments)
    if (subject != null) {
        return ProposedDecision(
            action = GuidanceAction.CAUTION,
            level = RiskLevel.WARN,
            reasonCode = "OBSTACLE_NEARBY",
            preferredCorridor = CorridorChoice.CENTRE,
            evidenceScore = highestScore,
            blockingLabel = subject.spatial.tracked.detection.label
                .takeIf { nameSupport(subject) >= BLOCKING_LABEL_MIN_CONFIDENCE },
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
 * The obstacle a CAUTION is about, or null when the frame does not warrant one.
 *
 * The score is the primary test, unchanged. The second clause exists because the
 * score cannot represent this case: `path_overlap` is CONTAINMENT — the share of
 * the box that falls inside the corridor — and it is the heaviest term in the
 * score at 0.30. A large obstacle close to the lens overflows the corridor on
 * every side, so its containment COLLAPSES exactly as it gets dangerous. The
 * corridor cost was given a separate obstruction measure for this reason; the
 * score still carries the Python one, because `spatial.json` pins it.
 *
 * Measured on frame 297 of a captured walk: an office chair two metres dead
 * ahead, `chair` at 0.90, proximity IMMEDIATE, filling a third of the corridor —
 * containment 0.366, score 0.518, which is WATCH. Corridor cost 0.362 against a
 * 0.40 gate, and free floor 0.557 because the chair's mesh back lets the carpet
 * through. Three independent signals all landed just under their thresholds and
 * the banner read WALKING. Nine of 180 CLEAR frames in that capture had
 * something near and dead ahead.
 *
 * So: an obstacle at IMMEDIATE proximity, inside the centre path, above the
 * watch band, is worth a word even when its score has not reached WARN. CAUTION
 * and not a blocked-centre verdict deliberately — IMMEDIATE is estimated from
 * apparent size and base height, it reads a chair at two metres as immediate,
 * and a mis-estimated band must not be able to stop someone dead.
 */
private fun cautionSubject(
    highest: RiskAssessment?,
    centreAssessments: List<RiskAssessment>,
): RiskAssessment? {
    if (highest != null && highest.level in setOf(RiskLevel.WARN, RiskLevel.HIGH)) {
        return highest
    }
    return centreAssessments
        .filter {
            it.spatial.proximity.band == ProximityBand.IMMEDIATE &&
                it.level != RiskLevel.CLEAR
        }
        .maxByOrNull { it.score }
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
 * The object a blocked-path verdict is about.
 *
 * It has to be something actually IMPLICATED in the verdict. The first version
 * fell back to the highest-scoring assessment in the frame, which in an open-plan
 * office is a person ten metres away: `FAR`, risk level `CLEAR`, path overlap
 * 0.000. The app announced "a person ahead" while the thing in the way was the
 * desk the user was standing at. Naming the wrong obstacle is worse than naming
 * none — it tells a blind user to expect something that is not there.
 *
 * So a candidate must be in the path, close enough to matter, and above the
 * watch band. Null when nothing qualifies, and the cascade falls back to the
 * generic wording, which is all it can defend.
 *
 * It must also be a name the detector has actually EARNED. YOLO11n calls an
 * office chair a chair at 0.64-0.80 from across the room and a `suitcase` at
 * 0.37-0.41 once its back fills the lens; both clear the 0.35 safety gate, which
 * is the right gate for "something is there" and far too low for "and it is a
 * suitcase". Tracks carry the best support their name has ever had
 * ([com.drishti.app.perception.TrackedDetection.labelConfidence]), so a chair
 * recognised on the approach keeps its name through the close-up.
 */
private fun blockingLabel(
    centreAssessments: List<RiskAssessment>,
    assessments: List<RiskAssessment>,
): String? {
    val implicated = { it: RiskAssessment ->
        it.spatial.pathOverlap >= BLOCKING_LABEL_MIN_OVERLAP &&
            it.spatial.proximity.band != ProximityBand.FAR &&
            it.spatial.proximity.band != ProximityBand.UNKNOWN &&
            it.level != RiskLevel.CLEAR &&
            nameSupport(it) >= BLOCKING_LABEL_MIN_CONFIDENCE
    }
    val centre = centreAssessments.filter(implicated).maxByOrNull { it.score }
    val anywhere = assessments.filter(implicated).maxByOrNull { it.score }
    return (centre ?: anywhere)?.spatial?.tracked?.detection?.label
}

/** Same gate the cascade uses to decide a detection is in the centre path. */
private const val BLOCKING_LABEL_MIN_OVERLAP = 0.25

/**
 * How well supported a name has to be before it is spoken.
 *
 * Between the 0.35 safety gate that decides an obstacle exists and the 0.64-0.80
 * the detector produces when it actually recognises the thing. Below this the
 * verdict is unnamed, not wrong.
 */
private const val BLOCKING_LABEL_MIN_CONFIDENCE = 0.50

/**
 * Best evidence there has ever been for this track name, falling back to this
 * frame's confidence for an assessment built without a tracker behind it.
 */
private fun nameSupport(assessment: RiskAssessment): Double = max(
    assessment.spatial.tracked.labelConfidence,
    assessment.spatial.tracked.detection.confidence,
)

/**
 * Which way to step when the path ahead is blocked.
 *
 * Cost first, exactly as the Python does, and the golden vectors pin that.
 * Corridor cost is a noisy-OR over NAMED obstacles, though, and two sides can
 * easily carry similar cost while one of them is open floor and the other is a
 * wall — cost says nothing about what is not detected. When cost cannot
 * separate them the old answer was PAUSE_UNCLEAR: "something is in the way,
 * work it out yourself", which is the least useful thing to say to a blind
 * person mid-stride.
 *
 * So free depth gets to break the tie. It is a different measurement from a
 * different model, it is the one that reads the ground rather than the objects,
 * and it only runs where the previous answer was "no idea". Whichever side it
 * picks still has to pass the walkable / free-extent / wall-ratio test in the
 * caller before anyone is steered into it.
 */
private fun clearerSide(corridor: CorridorAnalysis, settings: PipelineSettings): CorridorChoice {
    val left = corridor.costs.leftCost
    val right = corridor.costs.rightCost
    if (left + settings.decisionMargin < right) return CorridorChoice.LEFT
    if (right + settings.decisionMargin < left) return CorridorChoice.RIGHT
    if (!corridor.hasSurfaces) return CorridorChoice.NONE

    val leftFloor = corridor.floorExtents.leftCost
    val rightFloor = corridor.floorExtents.rightCost
    if (leftFloor >= rightFloor + settings.directionFreeExtentMargin) return CorridorChoice.LEFT
    if (rightFloor >= leftFloor + settings.directionFreeExtentMargin) return CorridorChoice.RIGHT
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
