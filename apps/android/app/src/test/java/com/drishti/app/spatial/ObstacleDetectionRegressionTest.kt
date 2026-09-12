package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.SurfaceKind
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.TrackedDetection
import com.drishti.app.risk.scoreTracks
import com.drishti.app.risk.selectAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The field failures this file exists to prevent, each reproduced from a Walk
 * Mode screenshot in which the banner read WALKING:
 *
 *  1. a desk edge filling the lower half of the frame,
 *  2. the same desk with its floor line clearly visible,
 *  3. an office swivel chair pressed against the lens.
 *
 * All three shared one property — the obstacle was LARGE and CLOSE — and the
 * pipeline scored every one of them as a clear path.
 */
class ObstacleDetectionRegressionTest {

    private val settings = PipelineSettings()

    private fun detection(
        label: String,
        x1: Double,
        y1: Double,
        x2: Double,
        y2: Double,
        confidence: Double = 0.6,
    ) = DetectionCandidate(label, confidence, x1, y1, x2, y2)

    private fun tracked(detection: DetectionCandidate, id: Int = 1) = TrackedDetection(
        detection = detection,
        trackId = id,
        approachRate = null,
        areaChange = null,
        motionDx = null,
        motionDy = null,
    )

    // ---- the obstruction measure -------------------------------------------

    @Test
    fun frameFillingObstacleFullyObstructsTheCorridor() {
        val chair = detection("chair", 0.02, 0.05, 0.98, 1.0)
        // Containment collapses to the corridor's share of the frame (~0.31)
        // precisely because the obstacle is everywhere. That was the bug.
        assertTrue(bboxPathOverlap(chair, settings) < 0.40)
        assertEquals(1.0, pathObstruction(chair, settings), 1e-9)
    }

    @Test
    fun obstructionNeverFallsBelowThePythonContainmentMeasure() {
        val boxes = listOf(
            detection("bag", 0.45, 0.80, 0.58, 0.95),
            detection("person", 0.42, 0.45, 0.58, 0.86),
            detection("person", 0.02, 0.85, 0.25, 0.99),
            detection("car", 0.44, 0.39, 0.56, 0.52),
            detection("chair", 0.0, 0.0, 1.0, 1.0),
        )
        for (box in boxes) {
            assertTrue(
                "obstruction must dominate containment for $box",
                pathObstruction(box, settings) >= bboxPathOverlap(box, settings) - 1e-12,
            )
        }
    }

    @Test
    fun smallObstacleInsideTheCorridorStillBlocksTheCentre() {
        val analysis = analyzeCorridors(
            listOf(tracked(detection("bag", 0.45, 0.80, 0.58, 0.95))),
            settings,
        )
        assertTrue(analysis.costs.centreCost >= settings.riskCentreBlockThreshold)
        assertTrue(analysis.costs.leftCost < settings.riskSideBlockThreshold)
    }

    @Test
    fun aChairFillingTheFrameStopsTheWalker() {
        val analysis = analyzeCorridors(
            listOf(tracked(detection("chair", 0.02, 0.05, 0.98, 1.0))),
            settings,
        )
        assertTrue("centre", analysis.costs.centreCost >= settings.riskCentreBlockThreshold)
        assertTrue("left", analysis.costs.leftCost >= settings.riskSideBlockThreshold)
        assertTrue("right", analysis.costs.rightCost >= settings.riskSideBlockThreshold)

        val decision = selectAction(scoreTracks(analysis.tracks, settings), analysis, settings)
        assertEquals(GuidanceAction.STOP, decision.action)
        assertEquals("ALL_CORRIDORS_BLOCKED", decision.reasonCode)
    }

    @Test
    fun aDeskEdgeAcrossTheLowerFrameStopsTheWalker() {
        val analysis = analyzeCorridors(
            listOf(tracked(detection("desk", 0.0, 0.55, 1.0, 1.0))),
            settings,
        )
        val decision = selectAction(scoreTracks(analysis.tracks, settings), analysis, settings)
        assertEquals(GuidanceAction.STOP, decision.action)
    }

    @Test
    fun aPersonWellAheadInTheCentreLeavesBothSidesOpen() {
        val analysis = analyzeCorridors(
            listOf(tracked(detection("person", 0.42, 0.45, 0.58, 0.86, confidence = 0.8))),
            settings,
        )
        assertTrue(analysis.costs.leftCost < settings.riskSideBlockThreshold)
        assertTrue(analysis.costs.rightCost < settings.riskSideBlockThreshold)
    }

    // ---- surface evidence ---------------------------------------------------

    private fun surfaces(
        walkable: Double,
        nonWalkable: Double,
        floorExtent: Double,
    ): SurfaceEvidence {
        val order = listOf(CorridorChoice.LEFT, CorridorChoice.CENTRE, CorridorChoice.RIGHT)
        fun all(value: Double) = order.associateWith { value }
        return SurfaceEvidence(
            walkableRatio = all(walkable),
            roadRatio = all(0.0),
            nonWalkableRatio = all(nonWalkable),
            unknownRatio = all(1.0 - walkable - nonWalkable),
            wallRatio = all(0.0),
            stairsRatio = all(0.0),
            floorExtent = all(floorExtent),
        )
    }

    @Test
    fun anOfficeSwivelChairIsASolidObstacleNotAnUnknownSurface() {
        assertEquals(SurfaceKind.NON_WALKABLE, Surfaces.kindFor("swivel chair"))
        assertEquals(SurfaceKind.NON_WALKABLE, Surfaces.kindFor("coffee table"))
        assertEquals(SurfaceKind.NON_WALKABLE, Surfaces.kindFor("counter"))
        assertEquals(SurfaceKind.NON_WALKABLE, Surfaces.kindFor("bench"))
        assertEquals(SurfaceKind.NON_WALKABLE, Surfaces.kindFor("refrigerator"))
        assertEquals(SurfaceKind.NON_WALKABLE, Surfaces.kindFor("river"))
        // Overhead classes stay out of the way.
        assertEquals(SurfaceKind.UNKNOWN, Surfaces.kindFor("chandelier"))
        assertEquals(SurfaceKind.UNKNOWN, Surfaces.kindFor("sky"))
        // Natural ground is walkable, not merely unclassified.
        assertEquals(SurfaceKind.WALKABLE, Surfaces.kindFor("grass"))
    }

    @Test
    fun aCorridorFilledByASegmentedObstacleIsBlockedWithoutAnyDetection() {
        val analysis = analyzeCorridors(
            emptyList(),
            settings,
            surfaces = surfaces(walkable = 0.12, nonWalkable = 0.85, floorExtent = 0.05),
        )
        assertTrue(analysis.costs.centreCost >= settings.riskCentreBlockThreshold)
        assertTrue(analysis.safePolygons.isEmpty())

        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.STOP, decision.action)
        assertEquals("ALL_CORRIDORS_BLOCKED", decision.reasonCode)
    }

    @Test
    fun noFloorAheadBlocksTheCorridorEvenWhenNothingIsNamedAWall() {
        val analysis = analyzeCorridors(
            emptyList(),
            settings,
            // Walkable enough to pass the ratio gate, but the floor stops short.
            surfaces(walkable = 0.30, nonWalkable = 0.10, floorExtent = 0.05),
        )
        assertFalse("the wall branch must not be what catches this", analysis.wallDeadEnd)
        assertTrue(analysis.costs.centreCost < settings.riskCentreBlockThreshold)

        val decision = selectAction(emptyList(), analysis, settings)
        assertTrue(
            "expected a blocked-corridor answer, got " +
                "${decision.action}/${decision.reasonCode}",
            decision.action != GuidanceAction.CLEAR,
        )
    }

    @Test
    fun floorVisibleOnlyAtTheFeetIsNotPaintedAsASafeCorridor() {
        val analysis = analyzeCorridors(
            emptyList(),
            settings,
            surfaces(walkable = 0.30, nonWalkable = 0.10, floorExtent = 0.05),
        )
        assertTrue(
            "green means floor continuing ahead, not floor somewhere in the wedge",
            analysis.safePolygons.isEmpty(),
        )
    }

    @Test
    fun anOpenCorridorIsStillWalkable() {
        val analysis = analyzeCorridors(
            emptyList(),
            settings,
            surfaces(walkable = 0.90, nonWalkable = 0.02, floorExtent = 0.95),
        )
        assertTrue(analysis.safePolygons.isNotEmpty())
        assertEquals(GuidanceAction.CLEAR, selectAction(emptyList(), analysis, settings).action)
    }

    @Test
    fun missingSegmentationNeverFabricatesABlockedCorridor() {
        val analysis = analyzeCorridors(emptyList(), settings, surfaces = null)
        assertFalse(analysis.hasSurfaces)
        // Uncertainty, not danger (docs/SAFETY_RULES.md).
        val decision = selectAction(emptyList(), analysis, settings)
        assertEquals(GuidanceAction.PAUSE_UNCLEAR, decision.action)
        assertEquals("CENTRE_SURFACE_UNCERTAIN", decision.reasonCode)
    }

    @Test
    fun truckAndTrainReachTheSafetyEngineAndCountAsVehicles() {
        assertTrue("truck" in com.drishti.app.perception.CANONICAL_LABELS)
        assertTrue("train" in com.drishti.app.perception.CANONICAL_LABELS)
        assertTrue("truck" in com.drishti.app.risk.VEHICLE_LABELS)
        assertTrue("train" in com.drishti.app.risk.VEHICLE_LABELS)
        for (label in com.drishti.app.risk.VEHICLE_LABELS) {
            assertTrue(
                "$label must reach the risk view to ever be judged a vehicle",
                label in com.drishti.app.perception.CANONICAL_LABELS,
            )
        }
        for (label in com.drishti.app.perception.CANONICAL_LABELS) {
            assertTrue(
                "$label has no severity, so it would silently score as 0.5",
                label in PipelineSettings.DEFAULT_CLASS_SEVERITIES,
            )
        }
    }
}
