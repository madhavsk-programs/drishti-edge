package com.drishti.app.risk

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.Direction
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.ProximityBand
import com.drishti.app.net.RiskLevel
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.TrackedDetection
import com.drishti.app.spatial.CorridorAnalysis
import com.drishti.app.spatial.CorridorCosts
import com.drishti.app.spatial.RelativeProximity
import com.drishti.app.spatial.SpatialTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Frame 297 of a captured walk: an office chair two metres dead ahead, `chair`
 * at 0.90, proximity IMMEDIATE, filling a third of the corridor. The banner read
 * WALKING.
 *
 * Every signal landed just under its threshold, and they are not independent
 * accidents:
 *
 *  - `path_overlap` 0.366. It is CONTAINMENT, the share of the BOX inside the
 *    corridor, and it is the heaviest term in the risk score at 0.30. A large
 *    obstacle close to the lens overflows the corridor on every side, so its
 *    containment collapses exactly as it becomes dangerous.
 *  - risk score 0.518, which is WATCH; WARN needs 0.65.
 *  - corridor cost 0.362 against a 0.40 gate.
 *  - free floor 0.557, because a mesh chair back lets the carpet through.
 *
 * Nine of that capture's 180 CLEAR frames had something near and dead ahead.
 */
class CloseObstacleAheadTest {

    private val settings = PipelineSettings()

    private fun assessment(
        label: String,
        band: ProximityBand,
        proximity: Double,
        pathOverlap: Double,
        confidence: Double = 0.90,
        direction: Direction = Direction.CENTRE,
    ) = scoreTracks(
        listOf(
            SpatialTrack(
                tracked = TrackedDetection(
                    detection = DetectionCandidate(label, confidence, 0.25, 0.15, 0.70, 0.65),
                    trackId = 1,
                    approachRate = null,
                    areaChange = null,
                    motionDx = null,
                    motionDy = null,
                    labelConfidence = confidence,
                ),
                proximity = RelativeProximity(proximity, band),
                direction = direction,
                pathOverlap = pathOverlap,
            ),
        ),
        settings,
    )

    /** As measured on the frame: nothing here is over any gate. */
    private fun openCorridor() = CorridorAnalysis(
        tracks = emptyList(),
        costs = CorridorCosts(0.322, 0.362, 0.441),
        preferred = CorridorChoice.CENTRE,
        walkableChoices = setOf(
            CorridorChoice.LEFT, CorridorChoice.CENTRE, CorridorChoice.RIGHT,
        ),
        uncertainChoices = emptySet(),
        safePolygons = emptyList(),
        blockedPolygons = emptyList(),
        uncertainPolygons = emptyList(),
        wallRatios = CorridorCosts(0.0, 0.0, 0.0),
        floorExtents = CorridorCosts(0.557, 0.557, 0.557),
        stairsRatios = CorridorCosts(0.0, 0.0, 0.0),
        wallDeadEnd = false,
        hasSurfaces = true,
    )

    @Test
    fun aChairTwoMetresDeadAheadIsNotAClearPath() {
        val assessments = assessment(
            "chair", ProximityBand.IMMEDIATE, proximity = 0.85, pathOverlap = 0.366,
        )
        // The premise: the score really is only WATCH.
        assertEquals(RiskLevel.WATCH, assessments.single().level)

        val decision = selectAction(assessments, openCorridor(), settings)
        assertEquals(GuidanceAction.CAUTION, decision.action)
        assertEquals("chair", decision.blockingLabel)
    }

    @Test
    fun itIsACautionAndNotAStop() {
        // IMMEDIATE is estimated from apparent size and base height, and it reads
        // a chair at two metres as immediate. A band that eager must not be able
        // to stop someone dead.
        val decision = selectAction(
            assessment("chair", ProximityBand.IMMEDIATE, 0.85, 0.366),
            openCorridor(),
            settings,
        )
        assertEquals(GuidanceAction.CAUTION, decision.action)
        assertEquals(CorridorChoice.CENTRE, decision.preferredCorridor)
    }

    @Test
    fun somethingCloseButOffToTheSideIsStillAClearPath() {
        val decision = selectAction(
            assessment(
                "chair", ProximityBand.IMMEDIATE, 0.85, 0.366,
                direction = Direction.LEFT,
            ),
            openCorridor(),
            settings,
        )
        assertEquals(GuidanceAction.CLEAR, decision.action)
    }

    @Test
    fun aBoxThatBarelyClipsTheCorridorIsStillAClearPath() {
        // Pinned by golden vector `centre_object_below_overlap_gate_not_blocking`:
        // the containment gate stays where it is.
        val decision = selectAction(
            assessment("person", ProximityBand.IMMEDIATE, 0.85, pathOverlap = 0.10),
            openCorridor(),
            settings,
        )
        assertEquals(GuidanceAction.CLEAR, decision.action)
    }

    @Test
    fun somethingMerelyNearAheadIsLeftToTheScore() {
        // NEAR is a much weaker claim than IMMEDIATE and is deliberately not
        // widened; the ordinary score decides.
        val decision = selectAction(
            assessment("chair", ProximityBand.NEAR, 0.70, 0.30, confidence = 0.87),
            openCorridor(),
            settings,
        )
        assertEquals(GuidanceAction.CLEAR, decision.action)
    }

    @Test
    fun aCautionWillNotSpeakANameItCannotSupport() {
        val decision = selectAction(
            assessment("suitcase", ProximityBand.IMMEDIATE, 0.85, 0.366, confidence = 0.41),
            openCorridor(),
            settings,
        )
        assertEquals(GuidanceAction.CAUTION, decision.action)
        assertNull(decision.blockingLabel)
    }
}
