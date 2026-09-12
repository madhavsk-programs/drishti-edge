package com.drishti.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * BUILD_PLAN.md A6 acceptance: letterbox → un-letterbox round-trip.
 *
 * An inverse that is subtly wrong does not throw. It lands boxes slightly off
 * in a way that reads as camera jitter, so it has to be pinned arithmetically.
 */
class LetterboxTest {

    private fun assertClose(expected: Double, actual: Double, what: String) {
        assertTrue(
            "$what: expected $expected, got $actual",
            abs(expected - actual) < 1e-9,
        )
    }

    @Test
    fun landscapeSourcePadsTopAndBottom() {
        val box = Letterbox(1280, 720, 640, 640)
        assertEquals(640, box.scaledWidth)
        assertEquals(360, box.scaledHeight)
        assertEquals(0, box.padX)
        assertEquals(140, box.padY)
    }

    @Test
    fun portraitSourcePadsLeftAndRight() {
        val box = Letterbox(720, 1280, 640, 640)
        assertEquals(360, box.scaledWidth)
        assertEquals(640, box.scaledHeight)
        assertEquals(140, box.padX)
        assertEquals(0, box.padY)
    }

    @Test
    fun squareSourceNeedsNoPadding() {
        val box = Letterbox(480, 480, 640, 640)
        assertEquals(640, box.scaledWidth)
        assertEquals(640, box.scaledHeight)
        assertEquals(0, box.padX)
        assertEquals(0, box.padY)
    }

    @Test
    fun roundTripIsIdentityAcrossTheUnitSquare() {
        val shapes = listOf(
            Letterbox(1280, 720, 640, 640),
            Letterbox(720, 1280, 640, 640),
            Letterbox(640, 480, 640, 640),
            Letterbox(1920, 1080, 640, 640),
        )
        val samples = listOf(0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0)
        for (box in shapes) {
            for (nx in samples) {
                for (ny in samples) {
                    val (tx, ty) = box.fromOrientedNormalized(nx, ny)
                    val (rx, ry) = box.toOrientedNormalized(tx, ty)
                    assertClose(nx, rx, "x for ${box.sourceWidth}x${box.sourceHeight}")
                    assertClose(ny, ry, "y for ${box.sourceWidth}x${box.sourceHeight}")
                }
            }
        }
    }

    @Test
    fun cornersMapToTheContentEdgesNotTheTensorEdges() {
        val box = Letterbox(1280, 720, 640, 640)
        val (topLeftX, topLeftY) = box.fromOrientedNormalized(0.0, 0.0)
        assertClose(0.0, topLeftX, "top-left x")
        assertClose(140.0, topLeftY, "top-left y sits below the pad band")

        val (bottomRightX, bottomRightY) = box.fromOrientedNormalized(1.0, 1.0)
        assertClose(640.0, bottomRightX, "bottom-right x")
        assertClose(500.0, bottomRightY, "bottom-right y sits above the pad band")
    }

    @Test
    fun paddingBandHasNoSourcePixel() {
        val box = Letterbox(1280, 720, 640, 640)
        assertNull("top pad band", box.sourcePixelFor(320, 10))
        assertNull("bottom pad band", box.sourcePixelFor(320, 630))
        assertTrue("content band", box.sourcePixelFor(320, 320) != null)
    }

    @Test
    fun sourcePixelsSpanTheWholeSourceImage() {
        val box = Letterbox(1280, 720, 640, 640)
        assertEquals(Pair(0, 0), box.sourcePixelFor(0, box.padY))

        // Nearest-neighbour at a 2:1 downscale: the last tensor column covers
        // source columns 1278-1279 and truncation picks the first of the pair,
        // so 1279 is never sampled. That is correct decimation, not an
        // off-by-one — what matters is that the mapping stays in bounds and
        // reaches the final sampled pixel.
        val bottomRight = box.sourcePixelFor(639, box.padY + box.scaledHeight - 1)!!
        assertEquals(1278, bottomRight.first)
        assertEquals(718, bottomRight.second)

        // Monotonic and in-bounds across the whole content band.
        var previousX = -1
        for (tx in 0 until 640) {
            val (sx, _) = box.sourcePixelFor(tx, box.padY)!!
            assertTrue("source x must stay in bounds, got $sx", sx in 0..1279)
            assertTrue("source x must not decrease at tensor column $tx", sx >= previousX)
            previousX = sx
        }
    }

    @Test
    fun outOfRangeTensorPointsClampIntoTheUnitSquare() {
        val box = Letterbox(1280, 720, 640, 640)
        val (x, y) = box.toOrientedNormalized(-50.0, -50.0)
        assertClose(0.0, x, "clamped x")
        assertClose(0.0, y, "clamped y")
        val (x2, y2) = box.toOrientedNormalized(900.0, 900.0)
        assertClose(1.0, x2, "clamped x2")
        assertClose(1.0, y2, "clamped y2")
    }
}
