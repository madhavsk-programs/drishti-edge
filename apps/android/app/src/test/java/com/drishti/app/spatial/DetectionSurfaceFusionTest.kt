package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.SurfaceKind
import com.drishti.app.perception.DetectionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SegFormer-B0 calls a wooden desktop viewed along its length `floor`. Measured
 * on a real Walk Mode frame the centre corridor came back 82% WALKABLE with a
 * desk filling it. YOLO saw the same desk as `dining table`. When the two
 * disagree about a surface, the detector holds the positive evidence.
 */
class DetectionSurfaceFusionTest {

    private val settings = PipelineSettings()
    private val size = 128

    /** A map that is entirely floor — the mistake SegFormer makes on a desktop. */
    private fun allFloor(): SegFormerSegmenter.SegmentationFrame {
        val kind = IntArray(size * size) { SurfaceKind.WALKABLE.ordinal }
        return SegFormerSegmenter.SegmentationFrame(
            width = size,
            height = size,
            classId = IntArray(size * size),
            kind = kind,
            hazard = BooleanArray(size * size),
            wall = BooleanArray(size * size),
            inferenceMillis = 0.0,
        )
    }

    private fun evidence(occluders: List<DetectionCandidate>) =
        SurfaceEvidenceBuilder.build(
            allFloor(),
            Letterbox(size, size, size, size),
            settings,
            occluders,
        )

    private fun desk() = DetectionCandidate("desk", 0.38, 0.0, 0.15, 1.0, 1.0)

    @Test
    fun aFloorWithNothingOnItStaysWalkable() {
        val ev = evidence(emptyList())
        assertEquals(1.0, ev.walkableRatio.getValue(CorridorChoice.CENTRE), 1e-9)
        assertEquals(1.0, ev.floorExtent.getValue(CorridorChoice.CENTRE), 1e-9)
    }

    @Test
    fun aDetectedDeskWithdrawsTheWalkableClaimUnderIt() {
        val ev = evidence(listOf(desk()))
        assertTrue(
            "centre must stop reading as floor, got ${ev.walkableRatio[CorridorChoice.CENTRE]}",
            ev.walkableRatio.getValue(CorridorChoice.CENTRE) < MIN_WALKABLE_RATIO,
        )
        assertEquals(0.0, ev.floorExtent.getValue(CorridorChoice.CENTRE), 1e-9)
    }

    @Test
    fun theWithdrawnClaimIsUnknownNotBlocked() {
        val ev = evidence(listOf(desk()))
        // A box is evidence that something is THERE, not a measurement of the
        // surface behind it. Claiming NON_WALKABLE would be inventing evidence.
        assertEquals(0.0, ev.nonWalkableRatio.getValue(CorridorChoice.CENTRE), 1e-9)
        assertTrue(ev.unknownRatio.getValue(CorridorChoice.CENTRE) > 0.9)
    }

    @Test
    fun aDeskOnAFloorSegFormerMiscallsProducesABlockedCorridor() {
        val analysis = analyzeCorridors(emptyList(), settings, evidence(listOf(desk())))
        assertTrue("no green wedge over a desk", analysis.safePolygons.isEmpty())
        val decision = com.drishti.app.risk.selectAction(emptyList(), analysis, settings)
        assertTrue(
            "expected a blocked answer, got ${decision.action}/${decision.reasonCode}",
            decision.action != com.drishti.app.net.GuidanceAction.CLEAR,
        )
    }

    @Test
    fun freeSpaceEndsAtTheObstacleNotBeforeIt() {
        // Something standing in the middle distance: floor up to it, none past.
        val person = DetectionCandidate("person", 0.8, 0.40, 0.30, 0.60, 0.62)
        val ev = evidence(listOf(person))
        val extent = ev.floorExtent.getValue(CorridorChoice.CENTRE)
        assertTrue(
            "floor should run up to the obstacle and stop, got $extent",
            extent > 0.30 && extent < 0.95,
        )
    }

    @Test
    fun aDistantObstacleDoesNotEraseTheFloorAtTheFeet() {
        val farPerson = DetectionCandidate("person", 0.8, 0.45, 0.38, 0.55, 0.46)
        val extent = evidence(listOf(farPerson)).floorExtent.getValue(CorridorChoice.CENTRE)
        assertTrue(
            "a person well ahead must leave a walkable extent, got $extent",
            extent > settings.freespaceBlockedMax,
        )
    }
}
