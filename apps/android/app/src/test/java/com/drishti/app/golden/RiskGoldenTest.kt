package com.drishti.app.golden

import com.drishti.app.config.PipelineSettings
import com.drishti.app.golden.GoldenSupport.arr
import com.drishti.app.golden.GoldenSupport.assertClose
import com.drishti.app.golden.GoldenSupport.bool
import com.drishti.app.golden.GoldenSupport.int
import com.drishti.app.golden.GoldenSupport.num
import com.drishti.app.golden.GoldenSupport.numOrNull
import com.drishti.app.golden.GoldenSupport.obj
import com.drishti.app.golden.GoldenSupport.str
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.Direction
import com.drishti.app.net.ProximityBand
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.TrackedDetection
import com.drishti.app.risk.RiskAssessment
import com.drishti.app.risk.riskLevelForScore
import com.drishti.app.risk.scoreTracks
import com.drishti.app.risk.selectAction
import com.drishti.app.spatial.CorridorAnalysis
import com.drishti.app.spatial.CorridorCosts
import com.drishti.app.spatial.RelativeProximity
import com.drishti.app.spatial.SpatialTrack
import com.drishti.app.spatial.classifyApproach
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** BUILD_PLAN.md A4 acceptance: `risk.json` passes. */
class RiskGoldenTest {

    private val settings = PipelineSettings()

    /**
     * Rebuild a [SpatialTrack] from the vector's explicit fields. The decision
     * cascade reads only these, so pinning them directly keeps this test
     * independent of the corridor geometry vectors.
     */
    private fun spatialTrack(spec: JsonObject): SpatialTrack {
        val detection = DetectionCandidate(
            label = spec.str("label"),
            confidence = spec.num("confidence"),
            x1 = 0.4,
            y1 = 0.4,
            x2 = 0.6,
            y2 = 0.9,
        )
        return SpatialTrack(
            tracked = TrackedDetection(
                detection = detection,
                trackId = spec.int("track_id"),
                approachRate = spec.numOrNull("approach_rate"),
                areaChange = spec.numOrNull("area_change"),
                motionDx = null,
                motionDy = null,
            ),
            proximity = RelativeProximity(
                score = spec.num("proximity_score"),
                band = ProximityBand.valueOf(spec.str("proximity_band")),
            ),
            direction = Direction.valueOf(spec.str("direction")),
            pathOverlap = spec.num("path_overlap"),
        )
    }

    /** The exporter builds assessments at sensitivity 0.5, where the factor is 1.0. */
    private fun assessment(spec: JsonObject): RiskAssessment {
        val spatial = spatialTrack(spec)
        val detection = spatial.tracked.detection
        val classSeverity = settings.riskClassSeverities[detection.label] ?: 0.5
        val approach = min(1.0, max(0.0, spatial.tracked.approachRate ?: 0.0))
        val score = min(
            1.0,
            max(
                0.0,
                settings.riskWeightPathOverlap * spatial.pathOverlap +
                    settings.riskWeightProximity * spatial.proximity.score +
                    settings.riskWeightApproach * approach +
                    settings.riskWeightClassSeverity * classSeverity +
                    settings.riskWeightConfidence * detection.confidence,
            ),
        )
        return RiskAssessment(
            spatial = spatial,
            score = score,
            level = riskLevelForScore(score, settings),
            classSeverity = classSeverity,
            approachState = classifyApproach(
                spatial.tracked.areaChange,
                threshold = settings.approachChangeThreshold,
            ),
        )
    }

    private fun corridorCosts(values: List<Double>) = CorridorCosts(values[0], values[1], values[2])

    @Test
    fun matchesPythonRiskCascade() {
        val cases = GoldenSupport.cases("risk.json")
        var covered = 0
        for (case in cases) {
            val name = case.str("name")
            val input = case.obj("input")
            val expected = case.obj("expected")

            when (val kind = case.str("kind")) {
                "score_tracks" -> {
                    val spatial = spatialTrack(input.obj("assessment"))
                    val actual = scoreTracks(
                        listOf(spatial),
                        settings,
                        riskSensitivity = input.num("risk_sensitivity"),
                    ).single()
                    assertClose(expected.num("score"), actual.score, "[$name] score")
                    assertEquals("[$name] level", expected.str("level"), actual.level.name)
                    assertClose(
                        expected.num("class_severity"),
                        actual.classSeverity,
                        "[$name] class_severity",
                    )
                    assertEquals(
                        "[$name] approach_state",
                        expected.str("approach_state"),
                        actual.approachState.name,
                    )
                    covered++
                }

                "select_action" -> {
                    val assessments = input.arr("assessments").map { assessment(it.jsonObject) }
                    val corridorSpec = input.obj("corridor")
                    val corridor = CorridorAnalysis(
                        tracks = assessments.map { it.spatial },
                        costs = CorridorCosts(
                            leftCost = corridorSpec.num("left_cost"),
                            centreCost = corridorSpec.num("centre_cost"),
                            rightCost = corridorSpec.num("right_cost"),
                        ),
                        preferred = CorridorChoice.NONE,
                        walkableChoices = corridorSpec.arr("walkable_choices")
                            .map { CorridorChoice.valueOf(it.jsonPrimitive.content) }.toSet(),
                        uncertainChoices = corridorSpec.arr("uncertain_choices")
                            .map { CorridorChoice.valueOf(it.jsonPrimitive.content) }.toSet(),
                        safePolygons = emptyList(),
                        blockedPolygons = emptyList(),
                        uncertainPolygons = emptyList(),
                        wallRatios = corridorCosts(
                            corridorSpec.arr("wall_ratios").map { it.jsonPrimitive.content.toDouble() },
                        ),
                        floorExtents = corridorCosts(
                            corridorSpec.arr("floor_extents").map { it.jsonPrimitive.content.toDouble() },
                        ),
                        stairsRatios = corridorCosts(
                            corridorSpec.arr("stairs_ratios").map { it.jsonPrimitive.content.toDouble() },
                        ),
                        wallDeadEnd = corridorSpec.bool("wall_dead_end"),
                        // Every vector carries measured floor extents, so the
                        // Python they were exported from had segmentation.
                        hasSurfaces = true,
                    )

                    val actual = selectAction(assessments, corridor, settings)
                    assertEquals("[$name] action", expected.str("action"), actual.action.name)
                    assertEquals("[$name] level", expected.str("level"), actual.level.name)
                    assertEquals(
                        "[$name] reason_code",
                        expected.str("reason_code"),
                        actual.reasonCode,
                    )
                    assertEquals(
                        "[$name] preferred_corridor",
                        expected.str("preferred_corridor"),
                        actual.preferredCorridor.name,
                    )
                    assertClose(
                        expected.num("evidence_score"),
                        actual.evidenceScore,
                        "[$name] evidence_score",
                    )
                    assertEquals(
                        "[$name] critical_track_ids",
                        expected.arr("critical_track_ids").map { it.jsonPrimitive.content.toInt() },
                        actual.criticalTrackIds.sorted(),
                    )
                    covered++
                }

                else -> fail("[$name] unknown vector kind '$kind'")
            }
        }
        assertEquals("every risk case must be exercised", cases.size, covered)
    }
}
