package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.SurfaceKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corridor is a perspective trapezoid, so its outer columns are clipped to
 * a sliver at the bottom of the frame — exactly where the ground at the user's
 * feet is, and exactly where it is floor. Those slivers outnumber the
 * full-height columns, so a median taken over every column reported "floor all
 * the way ahead" with an obstacle filling everything above ankle height.
 */
class FloorExtentTest {

    private val settings = PipelineSettings()
    private val size = 128

    /** A square segmentation map that is floor below [floorTopFraction], solid above. */
    private fun frame(floorTopFraction: Double): SegFormerSegmenter.SegmentationFrame {
        val kind = IntArray(size * size)
        val boundary = (size * floorTopFraction).toInt()
        for (y in 0 until size) {
            val value = if (y >= boundary) {
                SurfaceKind.WALKABLE.ordinal
            } else {
                SurfaceKind.NON_WALKABLE.ordinal
            }
            for (x in 0 until size) kind[y * size + x] = value
        }
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

    private fun centreExtent(floorTopFraction: Double): Double {
        // A square source into a square tensor: no letterbox padding, so the
        // corridor polygons land on the map unscaled.
        val letterbox = Letterbox(size, size, size, size)
        return SurfaceEvidenceBuilder
            .build(frame(floorTopFraction), letterbox, settings)
            .floorExtent
            .getValue(CorridorChoice.CENTRE)
    }

    @Test
    fun floorVisibleOnlyAtTheFeetReadsAsAShortExtent() {
        // Floor starts at 92% down the frame: a strip at the toes and nothing
        // beyond it. Anything above `freespaceBlockedMax` here is the bug.
        val extent = centreExtent(0.92)
        assertTrue("expected a short extent, got $extent", extent <= settings.freespaceBlockedMax)
    }

    @Test
    fun anUnobstructedFloorReadsAsAFullExtent() {
        val extent = centreExtent(0.0)
        assertTrue("expected a full extent, got $extent", extent >= 0.95)
    }

    @Test
    fun aHalfObstructedCorridorLandsBetweenTheTwo() {
        val extent = centreExtent(0.70)
        assertTrue("got $extent", extent > settings.freespaceBlockedMax && extent < 0.95)
    }
}
