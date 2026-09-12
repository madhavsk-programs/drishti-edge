package com.drishti.app.feedback

import com.drishti.app.net.ApproachState
import com.drishti.app.net.DetectionResult
import com.drishti.app.net.ProximityBand
import com.drishti.app.net.RiskLevel

/**
 * Pure mapping from backend detections to steerable audio voices. Kept free of
 * Android imports so it is unit-testable and reused by [SpatialAudioEngine].
 */
data class SonarVoice(
    val freqHz: Float,
    val pan: Float,        // -1 full-left .. +1 full-right (pre-yaw)
    val gain: Float,       // 0..1
    val cadenceHz: Float,  // pulse rate; 0 = steady tone
)

/**
 * The sonar is an ECHOLOCATION instrument, so distance has to be the thing it is
 * loudest about.
 *
 * It used to pick its two voices by `riskScore` and set their gain from
 * `riskLevel` alone. `riskScore` spends only 0.25 of its weight on proximity
 * against 0.30 on path overlap, 0.15 on class severity and 0.10 on confidence,
 * so a car at the end of the street — wide open path overlap, severity 0.95 —
 * outscored a bag at the user's feet and took its voice. Gain then ignored
 * distance outright: a MEDIUM detection and an IMMEDIATE one at the same risk
 * band were the same loudness. The result was a room-tone of far-away objects
 * masking the near ones, which is the opposite of what the instrument is for.
 *
 * Selection is therefore by proximity, ties broken by risk, and gain falls away
 * with distance. Anything beyond the FAR gate is silent rather than quiet: a
 * detection the user cannot walk into this second has nothing to contribute but
 * masking.
 */
object SonarMapping {

    /**
     * Proximity below which a detection makes no sound. Equal to
     * `PipelineSettings.proximityFarThreshold`, so "audible" means exactly
     * "MEDIUM band or nearer" and the two never drift apart silently.
     */
    const val MIN_AUDIBLE_PROXIMITY = 0.35

    /** Voices for the nearest hazards, loudest for the nearest of them. */
    fun voicesFrom(detections: List<DetectionResult>): List<SonarVoice> =
        detections
            .filter { it.riskLevel != RiskLevel.CLEAR && audible(it) }
            .sortedWith(
                compareByDescending<DetectionResult> { it.proximityScore ?: 0.0 }
                    .thenByDescending { it.riskScore },
            )
            .take(2)
            .map { d ->
                val cx = ((d.bbox.x1 + d.bbox.x2) / 2.0).toFloat()
                val proximityLift = when (d.proximity) {
                    ProximityBand.FAR -> 0f
                    ProximityBand.MEDIUM -> 120f
                    ProximityBand.NEAR -> 280f
                    ProximityBand.IMMEDIATE -> 520f
                    ProximityBand.UNKNOWN -> 0f
                }
                val approachLift = if (d.approachState == ApproachState.APPROACHING) 80f else 0f
                val cadence = when (d.riskLevel) {
                    RiskLevel.WATCH -> 1.5f
                    RiskLevel.WARN -> 2.5f
                    RiskLevel.HIGH -> 5f
                    RiskLevel.CRITICAL -> 7f
                    RiskLevel.CLEAR -> 1f
                }
                val riskGain = when (d.riskLevel) {
                    RiskLevel.WATCH -> 0.4f
                    RiskLevel.WARN -> 0.7f
                    RiskLevel.HIGH, RiskLevel.CRITICAL -> 1.0f
                    RiskLevel.CLEAR -> 0.2f
                }
                SonarVoice(
                    freqHz = 320f + proximityLift + approachLift,
                    pan = (cx * 2f - 1f).coerceIn(-1f, 1f),
                    gain = riskGain * nearnessGain(d.proximityScore ?: 0.0),
                    cadenceHz = cadence,
                )
            }

    private fun audible(d: DetectionResult): Boolean =
        d.proximity != ProximityBand.UNKNOWN &&
            d.proximity != ProximityBand.FAR &&
            (d.proximityScore ?: 0.0) >= MIN_AUDIBLE_PROXIMITY

    /**
     * Distance attenuation: [MIN_AUDIBLE_PROXIMITY] is a whisper, contact is
     * full scale. Squared rather than linear so the nearest voice clearly
     * dominates a second one a little further out instead of merely edging it.
     */
    internal fun nearnessGain(proximityScore: Double): Float {
        val span = 1.0 - MIN_AUDIBLE_PROXIMITY
        val nearness = ((proximityScore - MIN_AUDIBLE_PROXIMITY) / span).coerceIn(0.0, 1.0)
        return (FAR_EDGE_GAIN + (1.0 - FAR_EDGE_GAIN) * nearness * nearness).toFloat()
    }

    private const val FAR_EDGE_GAIN = 0.2
}
