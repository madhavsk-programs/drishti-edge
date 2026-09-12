package com.drishti.app.risk

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.Direction
import com.drishti.app.net.ProximityBand
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
 * The app announced "a person ahead" while the thing in the way was the desk the
 * user was standing at. In an open-plan office every frame contains people ten
 * metres away, and the first version of [blockingLabel] simply took the
 * highest-scoring assessment in the frame — `FAR`, level `CLEAR`, path overlap
 * 0.000. Naming the wrong obstacle is worse than naming none.
 */
class BlockingLabelTest {

    private val settings = PipelineSettings()

    private fun track(
        label: String,
        band: ProximityBand,
        proximity: Double,
        direction: Direction,
        pathOverlap: Double,
    ) = SpatialTrack(
        tracked = TrackedDetection(
            detection = DetectionCandidate(label, 0.8, 0.4, 0.4, 0.6, 0.9),
            trackId = 1,
            approachRate = null,
            areaChange = null,
            motionDx = null,
            motionDy = null,
        ),
        proximity = RelativeProximity(proximity, band),
        direction = direction,
        pathOverlap = pathOverlap,
    )

    private val distantPerson = track(
        "person", ProximityBand.FAR, 0.20, Direction.UNKNOWN, pathOverlap = 0.0,
    )
    private val deskUnderfoot = track(
        "desk", ProximityBand.IMMEDIATE, 1.0, Direction.CENTRE, pathOverlap = 0.99,
    )

    private fun decide(tracks: List<SpatialTrack>): ProposedDecision {
        val assessments = scoreTracks(tracks, settings)
        val corridor = CorridorAnalysis(
            tracks = tracks,
            costs = CorridorCosts(0.90, 0.90, 0.90),
            preferred = CorridorChoice.NONE,
            walkableChoices = emptySet(),
            uncertainChoices = emptySet(),
            safePolygons = emptyList(),
            blockedPolygons = emptyList(),
            uncertainPolygons = emptyList(),
            wallRatios = CorridorCosts(0.0, 0.0, 0.0),
            floorExtents = CorridorCosts(0.0, 0.0, 0.0),
            stairsRatios = CorridorCosts(0.0, 0.0, 0.0),
            wallDeadEnd = false,
            hasSurfaces = true,
        )
        return selectAction(assessments, corridor, settings)
    }

    @Test
    fun aRoomfulOfDistantPeopleNamesNothing() {
        val decision = decide(List(5) { distantPerson })
        assertNull(
            "nothing here is in the path; the generic wording is the honest one",
            decision.blockingLabel,
        )
    }

    @Test
    fun theThingActuallyInThePathIsNamed() {
        assertEquals("desk", decide(listOf(distantPerson, deskUnderfoot)).blockingLabel)
    }

    @Test
    fun aDistantPersonNeverOutranksANearObstacle() {
        // Order reversed: selection must not depend on list position.
        assertEquals("desk", decide(listOf(deskUnderfoot, distantPerson)).blockingLabel)
    }

    @Test
    fun anObstacleOffToTheSideStillNamesItselfWhenNothingIsInTheCentre() {
        val besideYou = track(
            "chair", ProximityBand.NEAR, 0.70, Direction.LEFT, pathOverlap = 0.60,
        )
        assertEquals("chair", decide(listOf(distantPerson, besideYou)).blockingLabel)
    }
}
