package com.drishti.app.config

/**
 * The tunables the on-device pipeline reads, ported from `app/config.py`
 * (BUILD_PLAN.md Part 1). Defaults are the Python defaults verbatim — the
 * golden vectors were exported against them, so changing one here without
 * re-exporting breaks parity silently.
 *
 * Only the fields the perception/risk path actually reads are present. Server
 * concerns (paths, database, CORS) have no meaning on the phone.
 */
data class PipelineSettings(
    // Detector
    /**
     * Gate for the FIND/landmark view. A false positive here sends a user
     * walking towards a chair that is not there, so it stays strict.
     */
    val detectorConfidenceThreshold: Double = 0.45,
    /**
     * Gate for the SAFETY view, deliberately lower than the Find gate: the two
     * views have opposite error costs. A miss here is a collision; a false
     * positive is a needless pause, and the corridor cost already multiplies by
     * confidence, so a marginal box contributes marginally.
     *
     * Measured on real Walk Mode frames, YOLO11n scored the desk the user was
     * standing at as `dining table` 0.36–0.45 — straddling the old single 0.45
     * gate, which is exactly why the desk was sometimes seen and sometimes not.
     * 0.35 is the detector's own decode floor, so nothing below it exists to
     * admit anyway.
     */
    val riskConfidenceThreshold: Double = 0.35,
    val detectorImageSize: Int = 640,

    // Tracking
    val trackIouThreshold: Double = 0.20,
    val trackCentreDistanceThreshold: Double = 0.12,
    val trackMaxAgeFrames: Int = 3,

    // Proximity and approach
    val approachChangeThreshold: Double = 0.05,
    val proximityAreaWeight: Double = 0.55,
    val proximityAreaScale: Double = 0.50,
    val proximityFarThreshold: Double = 0.35,
    val proximityMediumThreshold: Double = 0.55,
    val proximityNearThreshold: Double = 0.78,

    // Corridor geometry
    val corridorHorizonY: Double = 0.38,
    val corridorTopHalfWidth: Double = 0.08,
    val corridorBottomHalfWidth: Double = 0.42,
    val corridorClearMargin: Double = 0.10,

    // Surfaces
    val wallMinPixelConfidence: Double = 0.60,
    val wallCentreRatioThreshold: Double = 0.35,
    val wallSideRatioThreshold: Double = 0.20,
    val surfaceCostRoadWeight: Double = 0.0,
    val surfaceCostUnknownWeight: Double = 0.10,
    val freespaceDeadEndMax: Double = 0.12,
    val freespaceSideOpenMin: Double = 0.30,
    /**
     * Below this share of visible floor running ahead, a corridor counts as
     * blocked no matter what is doing the blocking. Set above
     * [freespaceDeadEndMax] because that one gates the much stronger
     * `WALL_OR_DEAD_END_AHEAD` STOP, while this one only marks a corridor
     * blocked and lets the cascade choose between steering, pausing and
     * stopping.
     */
    val freespaceBlockedMax: Double = 0.20,
    val directionMinFreeExtent: Double = 0.35,
    val stairsCentreRatioThreshold: Double = 0.08,

    // Risk bands
    val riskWatchEnter: Double = 0.25,
    val riskWarnEnter: Double = 0.65,
    val riskWarnExit: Double = 0.50,
    val riskHighEnter: Double = 0.80,

    // Risk weights — MUST sum to 1.0
    val riskWeightPathOverlap: Double = 0.30,
    val riskWeightProximity: Double = 0.25,
    val riskWeightApproach: Double = 0.20,
    val riskWeightClassSeverity: Double = 0.15,
    val riskWeightConfidence: Double = 0.10,

    val riskCentreBlockThreshold: Double = 0.40,
    /**
     * Deliberately higher than [riskCentreBlockThreshold]: the two answer
     * different questions and the errors cost different things. Calling the
     * centre blocked costs a pause. Calling a SIDE blocked removes an escape
     * route, and once all three are gone the only verdict left is STOP — so a
     * side needs more evidence against it than the path ahead does.
     *
     * Corridor cost compounds as a noisy-OR, so several moderate contributions
     * add up quickly. Measured on a real Walk Mode frame, a chair at 0.227, a
     * person at 0.199 and a surface cost of 0.178 combined to 0.491 on a left
     * corridor that segmentation still read as 68% walkable with 64% clear floor
     * running ahead. At a shared 0.40 gate that frame said "path blocked on
     * every side" while a clear route was visible in it.
     *
     * This gate only decides whether a side is DISQUALIFIED. A side still has to
     * pass the walkable / free-extent / wall-ratio test before the cascade will
     * steer anyone into it.
     */
    val riskSideBlockThreshold: Double = 0.55,
    val riskCriticalPathOverlap: Double = 0.60,
    val riskCriticalProximity: Double = 0.70,
    val riskCriticalApproach: Double = 0.15,

    // Alert stability
    val alertPersistenceFrames: Int = 2,
    val alertClearFrames: Int = 3,
    val alertCooldownSeconds: Double = 3.0,
    val decisionMargin: Double = 0.15,

    val riskClassSeverities: Map<String, Double> = DEFAULT_CLASS_SEVERITIES,
) {
    init {
        val weightSum = riskWeightPathOverlap + riskWeightProximity + riskWeightApproach +
            riskWeightClassSeverity + riskWeightConfidence
        require(kotlin.math.abs(weightSum - 1.0) <= 1e-6) {
            "Risk weights must sum to 1.0, got $weightSum"
        }
        require(riskWarnExit < riskWarnEnter) {
            "riskWarnExit must be lower than riskWarnEnter"
        }
        require(riskWatchEnter < riskWarnEnter && riskWarnEnter < riskHighEnter) {
            "Risk bands must be strictly increasing"
        }
        require(corridorTopHalfWidth < corridorBottomHalfWidth) {
            "corridorTopHalfWidth must be below corridorBottomHalfWidth"
        }
        require(
            proximityFarThreshold < proximityMediumThreshold &&
                proximityMediumThreshold < proximityNearThreshold
        ) {
            "Proximity bands must be strictly increasing"
        }
        require(freespaceDeadEndMax <= freespaceBlockedMax) {
            "freespaceDeadEndMax must not exceed freespaceBlockedMax"
        }
        require(riskConfidenceThreshold <= detectorConfidenceThreshold) {
            "The safety view must not be stricter than the Find view"
        }
        require(riskSideBlockThreshold >= riskCentreBlockThreshold) {
            "An escape route must not be condemned on less evidence than the path ahead"
        }
    }

    companion object {
        val DEFAULT_CLASS_SEVERITIES: Map<String, Double> = mapOf(
            "person" to 0.55,
            "chair" to 0.75,
            "bag" to 0.45,
            "desk" to 0.80,
            "bicycle" to 0.80,
            "motorcycle" to 1.0,
            "car" to 0.95,
            "bus" to 1.0,
            "truck" to 1.0,
            "train" to 1.0,
            "dog" to 0.60,
            "fire hydrant" to 0.70,
            "stop sign" to 0.70,
            "parking meter" to 0.70,
            "traffic light" to 0.70,
            "bench" to 0.65,
            "door" to 0.80,
            "suitcase" to 0.65,
            "umbrella" to 0.55,
            "potted plant" to 0.70,
            "couch" to 0.75,
            "bed" to 0.80,
            "tv" to 0.45,
            "refrigerator" to 0.85,
            "sink" to 0.65,
            "toilet" to 0.75,
        )
    }
}
