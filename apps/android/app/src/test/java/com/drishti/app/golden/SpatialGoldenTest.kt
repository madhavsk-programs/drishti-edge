package com.drishti.app.golden

import com.drishti.app.config.PipelineSettings
import com.drishti.app.golden.GoldenSupport.arr
import com.drishti.app.golden.GoldenSupport.assertClose
import com.drishti.app.golden.GoldenSupport.candidate
import com.drishti.app.golden.GoldenSupport.num
import com.drishti.app.golden.GoldenSupport.obj
import com.drishti.app.golden.GoldenSupport.str
import com.drishti.app.net.CorridorChoice
import com.drishti.app.spatial.bboxPathOverlap
import com.drishti.app.spatial.corridorPolygons
import com.drishti.app.spatial.directionForAnchor
import com.drishti.app.spatial.estimateRelativeProximity
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** BUILD_PLAN.md A3 acceptance: `spatial.json` passes. */
class SpatialGoldenTest {

    private val settings = PipelineSettings()

    @Test
    fun matchesPythonSpatialReasoning() {
        val cases = GoldenSupport.cases("spatial.json")
        var covered = 0
        for (case in cases) {
            val name = case.str("name")
            val input = case.obj("input")
            val expected = case.obj("expected")
            when (val kind = case.str("kind")) {
                "direction_for_anchor" -> {
                    val actual = directionForAnchor(
                        input.num("x"),
                        input.num("y"),
                        settings,
                    )
                    assertEquals("[$name] direction", expected.str("direction"), actual.name)
                    covered++
                }

                "bbox_path_overlap" -> {
                    val actual = bboxPathOverlap(
                        candidate(input.obj("detection")),
                        settings,
                    )
                    assertClose(
                        expected.num("path_overlap"),
                        actual,
                        "[$name] path_overlap",
                        GoldenSupport.TOLERANCE_OPENCV_F32,
                    )
                    covered++
                }

                "proximity" -> {
                    val actual = estimateRelativeProximity(
                        candidate(input.obj("detection")),
                        settings,
                    )
                    assertClose(expected.num("score"), actual.score, "[$name] score")
                    assertEquals("[$name] band", expected.str("band"), actual.band.name)
                    covered++
                }

                "corridor_polygons" -> {
                    val actual = corridorPolygons(settings)
                    for ((key, element) in expected) {
                        val choice = CorridorChoice.valueOf(key)
                        val want = element.jsonArray.map { point ->
                            val pair = point.jsonArray
                            Pair(
                                pair[0].jsonPrimitive.double,
                                pair[1].jsonPrimitive.double,
                            )
                        }
                        val got = actual.getValue(choice)
                        assertEquals("[$name] $key vertex count", want.size, got.size)
                        want.forEachIndexed { index, wantPoint ->
                            assertClose(wantPoint.first, got[index].first, "[$name] $key #$index x")
                            assertClose(
                                wantPoint.second, got[index].second, "[$name] $key #$index y",
                            )
                        }
                    }
                    covered++
                }

                else -> fail("[$name] unknown vector kind '$kind'")
            }
        }
        assertEquals("every spatial case must be exercised", cases.size, covered)
    }
}
