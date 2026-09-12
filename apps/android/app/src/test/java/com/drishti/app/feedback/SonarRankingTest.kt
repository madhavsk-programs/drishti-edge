package com.drishti.app.feedback

import com.drishti.app.net.ApproachState
import com.drishti.app.net.DetectionResult
import com.drishti.app.net.Direction
import com.drishti.app.net.DisplayColor
import com.drishti.app.net.NormalizedBoundingBox
import com.drishti.app.net.NormalizedPoint
import com.drishti.app.net.ProximityBand
import com.drishti.app.net.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sonar reported "everything in the room, equally loud". These pin the two
 * reasons: selection ignored distance, and so did gain.
 */
class SonarRankingTest {

    private fun detection(
        label: String,
        cx: Double,
        proximityScore: Double,
        band: ProximityBand,
        riskScore: Double,
        level: RiskLevel,
    ) = DetectionResult(
        trackId = null,
        label = label,
        confidence = 0.8,
        bbox = NormalizedBoundingBox(cx - 0.05, 0.5, cx + 0.05, 0.9),
        anchor = NormalizedPoint(cx, 0.9),
        direction = Direction.CENTRE,
        proximity = band,
        proximityScore = proximityScore,
        approachState = ApproachState.UNKNOWN,
        approachRate = null,
        motionVector = null,
        pathOverlap = 1.0,
        riskScore = riskScore,
        riskLevel = level,
        displayColor = DisplayColor.YELLOW,
    )

    /** A car down the street: wide path overlap and severity 0.95 inflate risk. */
    private val farHighRisk = detection(
        "car", cx = 0.20, proximityScore = 0.42, band = ProximityBand.MEDIUM,
        riskScore = 0.78, level = RiskLevel.HIGH,
    )

    /** A bag at the user's feet: low severity, but it is right there. */
    private val nearLowRisk = detection(
        "bag", cx = 0.80, proximityScore = 0.95, band = ProximityBand.IMMEDIATE,
        riskScore = 0.41, level = RiskLevel.WATCH,
    )

    @Test
    fun theNearestHazardIsTheLoudestVoice() {
        val voices = SonarMapping.voicesFrom(listOf(farHighRisk, nearLowRisk))
        assertEquals(2, voices.size)
        assertTrue(
            "the near object must take the first voice, not the higher-scoring far one",
            voices[0].pan > 0f,
        )
        assertTrue(
            "near ${voices[0].gain} must be louder than far ${voices[1].gain}",
            voices[0].gain > voices[1].gain,
        )
    }

    @Test
    fun distanceAttenuatesGainWithinOneRiskBand() {
        val near = detection("chair", 0.5, 0.95, ProximityBand.IMMEDIATE, 0.5, RiskLevel.WARN)
        val middling = detection("chair", 0.5, 0.40, ProximityBand.MEDIUM, 0.5, RiskLevel.WARN)
        val nearGain = SonarMapping.voicesFrom(listOf(near)).single().gain
        val middlingGain = SonarMapping.voicesFrom(listOf(middling)).single().gain
        assertTrue(
            "same risk band must not mean same loudness ($nearGain vs $middlingGain)",
            nearGain > middlingGain * 2,
        )
    }

    @Test
    fun farDetectionsAreSilentRatherThanQuiet() {
        val far = detection("person", 0.3, 0.20, ProximityBand.FAR, 0.6, RiskLevel.WARN)
        assertTrue(SonarMapping.voicesFrom(listOf(far)).isEmpty())
    }

    @Test
    fun aRoomfulOfFarPeopleNeverMasksTheOneNearObject() {
        val crowd = (1..6).map {
            detection("person", it / 7.0, 0.30, ProximityBand.FAR, 0.7, RiskLevel.WARN)
        }
        val voices = SonarMapping.voicesFrom(crowd + nearLowRisk)
        assertEquals(1, voices.size)
        assertTrue(voices.single().pan > 0f)
    }

    @Test
    fun clearDetectionsStaySilent() {
        val clear = detection("tv", 0.5, 0.95, ProximityBand.IMMEDIATE, 0.1, RiskLevel.CLEAR)
        assertTrue(SonarMapping.voicesFrom(listOf(clear)).isEmpty())
    }

    @Test
    fun nearnessGainSpansTheAudibleRange() {
        assertEquals(0.2, SonarMapping.nearnessGain(SonarMapping.MIN_AUDIBLE_PROXIMITY).toDouble(), 1e-6)
        assertEquals(1.0, SonarMapping.nearnessGain(1.0).toDouble(), 1e-6)
        assertTrue(SonarMapping.nearnessGain(0.6) < SonarMapping.nearnessGain(0.9))
    }
}
