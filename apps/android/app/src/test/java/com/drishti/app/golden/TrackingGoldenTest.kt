package com.drishti.app.golden

import com.drishti.app.golden.GoldenSupport.arr
import com.drishti.app.golden.GoldenSupport.assertCloseOrNull
import com.drishti.app.golden.GoldenSupport.candidate
import com.drishti.app.golden.GoldenSupport.int
import com.drishti.app.golden.GoldenSupport.num
import com.drishti.app.golden.GoldenSupport.numOrNull
import com.drishti.app.golden.GoldenSupport.obj
import com.drishti.app.golden.GoldenSupport.str
import com.drishti.app.perception.SessionTracker
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** BUILD_PLAN.md A2 acceptance: `tracking.json` passes. */
class TrackingGoldenTest {

    @Test
    fun matchesPythonTracking() {
        val cases = GoldenSupport.cases("tracking.json")
        for (case in cases) {
            val name = case.str("name")
            val overrides = case.obj("settings_overrides")
            val tracker = SessionTracker(
                iouThreshold = overrides.num("track_iou_threshold"),
                centreDistanceThreshold = overrides.num("track_centre_distance_threshold"),
                maxAgeFrames = overrides.int("track_max_age_frames"),
            )
            val frames = case.obj("input").arr("frames").map { it.jsonObject }
            val expectedFrames = case.arr("expected").map { it.jsonArray }

            assertEquals("[$name] frame count", expectedFrames.size, frames.size)

            frames.forEachIndexed { frameIndex, frame ->
                val frameId = frame.int("frame_id")
                val actual = tracker.update(
                    detections = frame.arr("detections").map { candidate(it.jsonObject) },
                    frameId = frameId,
                    // The exporter stamps 100 ms per frame id; the tracker reads
                    // timestamps only for bookkeeping, never for association.
                    capturedAtMillis = 100L * frameId,
                )
                val expected = expectedFrames[frameIndex].map { it.jsonObject }
                assertEquals(
                    "[$name] frame $frameId tracked count",
                    expected.size,
                    actual.size,
                )
                expected.forEachIndexed { index, want ->
                    val got = actual[index]
                    val where = "[$name] frame $frameId #$index"
                    assertEquals("$where track_id", want.int("track_id"), got.trackId)
                    assertEquals("$where label", want.str("label"), got.detection.label)
                    assertCloseOrNull(
                        want.numOrNull("approach_rate"), got.approachRate, "$where approach_rate",
                    )
                    assertCloseOrNull(
                        want.numOrNull("area_change"), got.areaChange, "$where area_change",
                    )
                    assertCloseOrNull(
                        want.numOrNull("motion_dx"), got.motionDx, "$where motion_dx",
                    )
                    assertCloseOrNull(
                        want.numOrNull("motion_dy"), got.motionDy, "$where motion_dy",
                    )
                }
            }
        }
    }
}
