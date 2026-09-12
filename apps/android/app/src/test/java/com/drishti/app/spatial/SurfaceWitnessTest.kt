package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.SurfaceKind
import com.drishti.app.perception.CANONICAL_LABELS
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.SURFACE_WITNESS_LABELS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk the user is standing at is not reliably detectable. Over sixteen
 * CONSECUTIVE Walk Mode frames, YOLO11n reported `dining table` on one of them
 * and `laptop` on all sixteen at 0.76-0.92. The furniture is invisible; what is
 * sitting on it is the most confident thing in the frame.
 */
class SurfaceWitnessTest {

    private val settings = PipelineSettings()
    private val size = 128

    /** Everything is floor — the mistake SegFormer makes on a wooden desktop. */
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
        SurfaceEvidenceBuilder.build(
            allFloor(),
            Letterbox(size, size, size, size),
            settings,
            occluders.toList(),
        )

    /** As captured: base at 0.39, just inside the corridor. */
    private fun laptopOnTheDesk(confidence: Double = 0.90) =
        DetectionCandidate("laptop", confidence, 0.40, 0.25, 0.61, 0.39)

    @Test
    fun everyWitnessIsAlsoAnAuditedRiskLabel() {
        for (label in SURFACE_WITNESS_LABELS) {
            assertTrue(
                "$label can never reach the risk view, so it can never witness anything",
                label in CANONICAL_LABELS,
            )
        }
    }

    @Test
    fun aLaptopProvesThePlaneUnderItIsNotAFloor() {
        val ev = evidence(laptopOnTheDesk())
        assertEquals(0.0, ev.floorExtent.getValue(CorridorChoice.CENTRE), 1e-9)
        assertTrue(
            "centre must stop reading as floor, got ${ev.walkableRatio[CorridorChoice.CENTRE]}",
            ev.walkableRatio.getValue(CorridorChoice.CENTRE) < MIN_WALKABLE_RATIO,
        )
    }

    @Test
    fun theWitnessShadowReachesTheUsersFeetNotJustTheBox() {
        // An ordinary obstacle of the same shape only masks itself, so the floor
        // below it survives; a witness redefines everything nearer than its base.
        val ordinary = DetectionCandidate("bag", 0.90, 0.40, 0.25, 0.61, 0.39)
        assertTrue(evidence(ordinary).floorExtent.getValue(CorridorChoice.CENTRE) > 0.5)
        assertEquals(0.0, evidence(laptopOnTheDesk()).floorExtent.getValue(CorridorChoice.CENTRE), 1e-9)
    }

    @Test
    fun aWitnessBesideThePathLeavesTheCentreAlone() {
        // A laptop on a desk to the right: its own columns only.
        val aside = DetectionCandidate("laptop", 0.90, 0.84, 0.31, 1.00, 0.49)
        val ev = evidence(aside)
        assertTrue(
            "centre floor must survive, got ${ev.floorExtent[CorridorChoice.CENTRE]}",
            ev.floorExtent.getValue(CorridorChoice.CENTRE) > 0.5,
        )
    }

    @Test
    fun aWitnessAcrossTheRoomSaysNothingAboutTheGroundHere() {
        // A laptop on someone else's desk: small and high in the frame, so FAR.
        // It stands on a different surface with floor in between, and shadowing
        // below it would condemn that floor.
        val acrossTheRoom = DetectionCandidate("laptop", 0.90, 0.46, 0.40, 0.52, 0.45)
        assertTrue(evidence(acrossTheRoom).floorExtent.getValue(CorridorChoice.CENTRE) > 0.5)
    }

    @Test
    fun aWallMountedScreenIsNeverAWitness() {
        // `tv` is the one member of the family that is routinely wall-mounted,
        // with no surface under it at all, so it is not in the set.
        assertTrue("tv" !in SURFACE_WITNESS_LABELS)
    }

    @Test
    fun anUnconfidentWitnessDoesNotRedefineASurface() {
        val unsure = laptopOnTheDesk(confidence = 0.40)
        assertTrue(evidence(unsure).floorExtent.getValue(CorridorChoice.CENTRE) > 0.5)
    }

    @Test
    fun anEmptyFloorIsStillWalkable() {
        val ev = evidence()
        assertEquals(1.0, ev.walkableRatio.getValue(CorridorChoice.CENTRE), 1e-9)
        assertEquals(1.0, ev.floorExtent.getValue(CorridorChoice.CENTRE), 1e-9)
    }
}
