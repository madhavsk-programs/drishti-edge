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
    val detectorConfidenceThreshold: Double = 0.45,
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
    val riskSideBlockThreshold: Double = 0.40,
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
