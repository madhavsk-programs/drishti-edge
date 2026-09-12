package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.SurfaceKind
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.TrackedDetection
import com.drishti.app.risk.scoreTracks
import com.drishti.app.risk.selectAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Blocked on every side" with open floor beside the user.
 *
 * Three live screenshots showed a single office chair ahead, plainly clear
 * carpet to one side of it, and a full-screen STOP reading "blocked on every
 * side". The chair was found and measured correctly; what was wrong was the
 * question asked of the floor.
 *
 * Free depth was measured COLUMN-wise: walk up each corridor column until the
 * floor stops, take the median over the columns tall enough to be worth
 * measuring. A corridor third is a slanted trapezoid, so almost none of its
 * columns run its full depth — on the shipped geometry over a 128-px map the
 * LEFT third covers columns 24..60 and only 45..53 were measured, a nine-pixel
 * strip pressed against the centre. "Is there room to my left?" was answered by
 * looking at the strip immediately beside whatever was blocking the centre, so
 * one wide object in the middle of the path zeroed all three corridors at once.
 *
 * These tests use the real letterbox geometry of the analysis frame (960x1280
 * into a 512 tensor, decoded to a 128-px map), a segmentation map that is floor
 * everywhere, and detection boxes as the only thing taking floor away. The
 * segmenter being unanimously right is the point: every failure below is the
 * measurement, not the model.
 */
class SideCorridorExtentTest {

    private val settings = PipelineSettings()
    private val size = 128
    private val letterbox = Letterbox(960, 1280, 512, 512)

    private fun allFloor() = SegFormerSegmenter.SegmentationFrame(
        width = size,
        height = size,
        classId = IntArray(size * size),
        kind = IntArray(size * size) { SurfaceKind.WALKABLE.ordinal },
        hazard = BooleanArray(size * size),
        wall = BooleanArray(size * size),
        inferenceMillis = 0.0,
    )

    private fun evidence(vararg occluders: DetectionCandidate) =
        SurfaceEvidenceBuilder.build(allFloor(), letterbox, settings, occluders.toList())

    private fun tracked(detection: DetectionCandidate) = TrackedDetection(
        detection = detection,
        trackId = 1,
        approachRate = null,
        areaChange = null,
        motionDx = null,
        motionDy = null,
    )

    private fun decide(vararg occluders: DetectionCandidate) = run {
        val analysis = analyzeCorridors(
            occluders.map(::tracked),
            settings,
            surfaces = evidence(*occluders),
        )
        analysis to selectAction(scoreTracks(analysis.tracks, settings), analysis, settings)
    }

    /**
     * Screenshot 4, mapped back into the analysis frame.
     *
     * The preview is a FILL_CENTER crop of the 3:4 analysis frame, so a box
     * drawn from the left edge to 81% across the phone screen is x 0.19..0.69
     * in the frame the pipeline actually reasoned about — and the open carpet
     * sits to the right of it, from 0.69 out to the edge.
     */
    private val chairAcrossTheLeft =
        DetectionCandidate("chair", 0.41, 0.192, 0.27, 0.691, 1.0)

    @Test
    fun aChairAcrossTheLeftOfTheFrameLeavesTheRightSteerable() {
        val (analysis, decision) = decide(chairAcrossTheLeft)
        assertTrue(
            "the right third must keep some floor, got " +
                "${analysis.floorExtents.rightCost}",
            analysis.floorExtents.rightCost >= settings.directionMinFreeExtent,
        )
        assertEquals(GuidanceAction.MOVE_RIGHT, decision.action)
        assertEquals("CENTRE_BLOCKED_CLEARER_SIDE", decision.reasonCode)
    }

    @Test
    fun anObstacleOnTheInnerEdgeOfASideDoesNotCondemnTheWholeSide() {
        // x 0.60..0.69 is exactly the strip the column measure used to sample
        // for the RIGHT third. Full height, so it is not a small object.
        val insideTheOldBlindSpot =
            DetectionCandidate("bag", 0.60, 0.60, 0.30, 0.69, 1.0)
        val extent = evidence(insideTheOldBlindSpot).floorExtent
            .getValue(CorridorChoice.RIGHT)
        assertTrue("right third read as $extent", extent >= settings.directionMinFreeExtent)
    }

    @Test
    fun anObstacleFillingASideStillCondemnsIt() {
        val acrossTheRight = DetectionCandidate("chair", 0.60, 0.55, 0.30, 1.0, 1.0)
        val extent = evidence(acrossTheRight).floorExtent.getValue(CorridorChoice.RIGHT)
        assertTrue("right third read as $extent", extent <= settings.freespaceBlockedMax)
    }

    @Test
    fun anObstacleFillingTheFrameIsStillBlockedOnEverySide() {
        val (_, decision) = decide(DetectionCandidate("chair", 0.60, 0.02, 0.05, 0.98, 1.0))
        assertEquals(GuidanceAction.STOP, decision.action)
        assertEquals("ALL_CORRIDORS_BLOCKED", decision.reasonCode)
    }

    @Test
    fun anEmptyFloorIsWalkableAllTheWayAhead() {
        val extent = evidence().floorExtent
        for (choice in CorridorChoice.entries) {
            if (choice == CorridorChoice.NONE) continue
            assertEquals("$choice", 1.0, extent.getValue(choice), 1e-9)
        }
    }

    @Test
    fun freeDepthBreaksTheTieWhenCostCannot() {
        // Captured frame: costs 0.381 / 0.352 / 0.448 — no side is cheaper than
        // the other by the decision margin — with free depth 0.430 / 0.0 / 0.0.
        // The old answer was "blocked, direction unclear" over open carpet.
        val analysis = CorridorAnalysis(
            tracks = emptyList(),
            costs = CorridorCosts(0.381, 0.352, 0.448),
            preferred = CorridorChoice.NONE,
            walkableChoices = setOf(CorridorChoice.LEFT),
            uncertainChoices = emptySet(),
            safePolygons = emptyList(),
            blockedPolygons = emptyList(),
            uncertainPolygons = emptyList(),
            wallRatios = CorridorCosts(0.0, 0.0, 0.0),
            floorExtents = CorridorCosts(0.430, 0.0, 0.0),
            stairsRatios = CorridorCosts(0.0, 0.0, 0.0),
            wallDeadEnd = false,
            hasSurfaces = true,
        )
        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.MOVE_LEFT, decision.action)
    }

    @Test
    fun equallyOpenSidesStillRefuseToInventADirection() {
        val analysis = CorridorAnalysis(
            tracks = emptyList(),
            costs = CorridorCosts(0.30, 0.90, 0.30),
            preferred = CorridorChoice.NONE,
            walkableChoices = setOf(
                CorridorChoice.LEFT, CorridorChoice.CENTRE, CorridorChoice.RIGHT,
            ),
            uncertainChoices = emptySet(),
            safePolygons = emptyList(),
            blockedPolygons = emptyList(),
            uncertainPolygons = emptyList(),
            wallRatios = CorridorCosts(0.0, 0.0, 0.0),
            floorExtents = CorridorCosts(1.0, 1.0, 1.0),
            stairsRatios = CorridorCosts(0.0, 0.0, 0.0),
            wallDeadEnd = false,
            hasSurfaces = true,
        )
        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.PAUSE_UNCLEAR, decision.action)
        assertNotEquals(CorridorChoice.LEFT, decision.preferredCorridor)
        assertNotEquals(CorridorChoice.RIGHT, decision.preferredCorridor)
    }
}
