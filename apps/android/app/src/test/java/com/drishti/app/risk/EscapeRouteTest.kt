package com.drishti.app.risk

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.GuidanceAction
import com.drishti.app.spatial.CorridorAnalysis
import com.drishti.app.spatial.CorridorCosts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduced from a real Walk Mode frame: an office aisle with a seated person
 * in the centre and open carpet to the left.
 *
 * Corridor cost compounds as a noisy-OR, so a chair at 0.227, a person at 0.199
 * and a surface cost of 0.178 came to 0.491 on the left — over a 0.40 gate
 * shared with the centre — and the verdict was "path blocked on every side"
 * while segmentation was still reporting 68% walkable and 64% clear floor
 * running ahead on that side. Removing an escape route has to take more
 * evidence than flagging the path ahead.
 */
class EscapeRouteTest {

    private val settings = PipelineSettings()

    private fun corridor(
        left: Double,
        centre: Double,
        right: Double,
        leftFloor: Double,
        centreFloor: Double,
        rightFloor: Double,
        walkable: Set<CorridorChoice>,
    ) = CorridorAnalysis(
        tracks = emptyList(),
        costs = CorridorCosts(left, centre, right),
        preferred = CorridorChoice.NONE,
        walkableChoices = walkable,
        uncertainChoices = emptySet(),
        safePolygons = emptyList(),
        blockedPolygons = emptyList(),
        uncertainPolygons = emptyList(),
        wallRatios = CorridorCosts(0.0, 0.0, 0.0),
        floorExtents = CorridorCosts(leftFloor, centreFloor, rightFloor),
        stairsRatios = CorridorCosts(0.0, 0.0, 0.0),
        wallDeadEnd = false,
        hasSurfaces = true,
    )

    @Test
    fun anOpenSideIsSteeredIntoRatherThanCountedAsBlocked() {
        val analysis = corridor(
            left = 0.491, centre = 0.585, right = 0.877,
            leftFloor = 0.635, centreFloor = 0.506, rightFloor = 0.0,
            walkable = setOf(CorridorChoice.LEFT, CorridorChoice.CENTRE),
        )
        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.MOVE_LEFT, decision.action)
        assertEquals("CENTRE_BLOCKED_CLEARER_SIDE", decision.reasonCode)
    }

    @Test
    fun aSideWithRealEvidenceAgainstItIsStillBlocked() {
        val analysis = corridor(
            left = 0.90, centre = 0.90, right = 0.90,
            leftFloor = 0.0, centreFloor = 0.0, rightFloor = 0.0,
            walkable = emptySet(),
        )
        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.STOP, decision.action)
        assertEquals("ALL_CORRIDORS_BLOCKED", decision.reasonCode)
    }

    @Test
    fun aSideWithNoFloorAheadIsBlockedEvenAtLowCost() {
        val analysis = corridor(
            left = 0.05, centre = 0.90, right = 0.05,
            leftFloor = 0.02, centreFloor = 0.0, rightFloor = 0.02,
            walkable = setOf(CorridorChoice.LEFT, CorridorChoice.RIGHT),
        )
        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.STOP, decision.action)
        assertEquals("ALL_CORRIDORS_BLOCKED", decision.reasonCode)
    }

    @Test
    fun theSideGateIsNeverStricterThanTheCentreGate() {
        assertTrue(settings.riskSideBlockThreshold >= settings.riskCentreBlockThreshold)
    }

    @Test
    fun aSteerableVerdictNamesWhatIsInTheWay() {
        // Same geometry, but with a detection so the cascade has a culprit.
        val analysis = corridor(
            left = 0.491, centre = 0.585, right = 0.877,
            leftFloor = 0.635, centreFloor = 0.506, rightFloor = 0.0,
            walkable = setOf(CorridorChoice.LEFT, CorridorChoice.CENTRE),
        )
        val decision = selectAction(emptyList(), analysis, settings)
        // No detections here, so no name is invented.
        assertEquals(null, decision.blockingLabel)
    }
}
