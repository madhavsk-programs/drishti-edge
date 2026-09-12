package com.drishti.app.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** BUILD_PLAN.md A6 acceptance: NMS against a fixture. */
class NmsTest {

    private fun box(
        classId: Int,
        score: Double,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ) = RawBox(classId, score, left, top, right, bottom)

    @Test
    fun emptyInputYieldsEmptyOutput() {
        assertEquals(emptyList<RawBox>(), nonMaximumSuppression(emptyList(), 0.45))
    }

    @Test
    fun identicalBoxesCollapseToTheHighestScore() {
        val kept = nonMaximumSuppression(
            listOf(
                box(0, 0.60, 10.0, 10.0, 50.0, 50.0),
                box(0, 0.90, 10.0, 10.0, 50.0, 50.0),
                box(0, 0.75, 10.0, 10.0, 50.0, 50.0),
            ),
            iouThreshold = 0.45,
        )
        assertEquals(1, kept.size)
        assertEquals(0.90, kept.single().score, 1e-9)
    }

    @Test
    fun disjointBoxesAllSurvive() {
        val kept = nonMaximumSuppression(
            listOf(
                box(0, 0.9, 0.0, 0.0, 10.0, 10.0),
                box(0, 0.8, 100.0, 100.0, 110.0, 110.0),
                box(0, 0.7, 200.0, 200.0, 210.0, 210.0),
            ),
            iouThreshold = 0.45,
        )
        assertEquals(3, kept.size)
    }

    @Test
    fun suppressionIsPerClassNotAgnostic() {
        // Same geometry, different classes: both must survive, matching
        // Ultralytics' default agnostic=False.
        val kept = nonMaximumSuppression(
            listOf(
                box(0, 0.9, 10.0, 10.0, 50.0, 50.0),
                box(1, 0.8, 10.0, 10.0, 50.0, 50.0),
            ),
            iouThreshold = 0.45,
        )
        assertEquals(2, kept.size)
        assertEquals(setOf(0, 1), kept.map { it.classId }.toSet())
    }

    @Test
    fun overlapExactlyAtThresholdIsKept() {
        // 0.5 overlap on a strict `>` comparison must survive at 0.5.
        val a = box(0, 0.9, 0.0, 0.0, 10.0, 10.0)
        val b = box(0, 0.8, 5.0, 0.0, 15.0, 10.0)
        val overlap = iou(a, b)
        assertTrue("fixture IoU should be 1/3, was $overlap", abs(overlap - 1.0 / 3.0) < 1e-9)
        assertEquals(2, nonMaximumSuppression(listOf(a, b), iouThreshold = 1.0 / 3.0).size)
        assertEquals(1, nonMaximumSuppression(listOf(a, b), iouThreshold = 0.30).size)
    }

    @Test
    fun outputIsOrderedByDescendingScore() {
        val kept = nonMaximumSuppression(
            listOf(
                box(0, 0.3, 0.0, 0.0, 10.0, 10.0),
                box(1, 0.9, 100.0, 100.0, 110.0, 110.0),
                box(2, 0.6, 200.0, 200.0, 210.0, 210.0),
            ),
            iouThreshold = 0.45,
        )
        assertEquals(listOf(0.9, 0.6, 0.3), kept.map { it.score })
    }

    @Test
    fun maxDetectionsCapsTheOutput() {
        val many = (0 until 50).map { box(it, 0.5 + it / 1000.0, it * 20.0, 0.0, it * 20.0 + 10.0, 10.0) }
        assertEquals(10, nonMaximumSuppression(many, 0.45, maxDetections = 10).size)
    }

    @Test
    fun decodeReadsChannelMajorLayoutAndFiltersByScore() {
        // 6 channels (4 box + 2 classes), 3 anchors, laid out channel-major.
        val anchors = 3
        val channels = 6
        val output = FloatArray(channels * anchors)
        fun set(channel: Int, anchor: Int, value: Double) {
            output[channel * anchors + anchor] = value.toFloat()
        }
        // anchor 0: strong class 1
        set(0, 0, 100.0); set(1, 0, 100.0); set(2, 0, 40.0); set(3, 0, 20.0)
        set(4, 0, 0.10); set(5, 0, 0.90)
        // anchor 1: below the floor, must be dropped
        set(0, 1, 10.0); set(1, 1, 10.0); set(2, 1, 4.0); set(3, 1, 4.0)
        set(4, 1, 0.05); set(5, 1, 0.02)
        // anchor 2: strong class 0
        set(0, 2, 300.0); set(1, 2, 200.0); set(2, 2, 20.0); set(3, 2, 10.0)
        set(4, 2, 0.77); set(5, 2, 0.11)

        val decoded = decodeYoloHead(output, channels, anchors, confidenceThreshold = 0.35)
        assertEquals(2, decoded.size)

        val first = decoded.first { it.classId == 1 }
        assertEquals(0.90, first.score, 1e-6)
        // cx,cy,w,h = 100,100,40,20 → xyxy = 80,90,120,110
        assertEquals(80.0, first.left, 1e-6)
        assertEquals(90.0, first.top, 1e-6)
        assertEquals(120.0, first.right, 1e-6)
        assertEquals(110.0, first.bottom, 1e-6)

        val second = decoded.first { it.classId == 0 }
        assertEquals(0.77, second.score, 1e-6)
        assertEquals(290.0, second.left, 1e-6)
    }
}
