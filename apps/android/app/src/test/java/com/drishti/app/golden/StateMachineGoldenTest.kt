package com.drishti.app.golden

import com.drishti.app.config.PipelineSettings
import com.drishti.app.golden.GoldenSupport.arr
import com.drishti.app.golden.GoldenSupport.num
import com.drishti.app.golden.GoldenSupport.obj
import com.drishti.app.golden.GoldenSupport.str
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.RiskLevel
import com.drishti.app.risk.AlertStateMachine
import com.drishti.app.risk.ProposedDecision
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

/** BUILD_PLAN.md A4 acceptance: `state_machine.json` passes. */
class StateMachineGoldenTest {

    private val settings = PipelineSettings()

    @Test
    fun matchesPythonStateMachine() {
        val cases = GoldenSupport.cases("state_machine.json")
        for (case in cases) {
            val name = case.str("name")
            val machine = AlertStateMachine(settings)
            val steps = case.obj("input").arr("steps").map { it.jsonObject }
            val expected = case.arr("expected").map { it.jsonObject }

            assertEquals("[$name] step count", expected.size, steps.size)

            steps.forEachIndexed { index, step ->
                val proposalSpec = step.obj("proposal")
                val proposal = ProposedDecision(
                    action = GuidanceAction.valueOf(proposalSpec.str("action")),
                    level = RiskLevel.valueOf(proposalSpec.str("level")),
                    reasonCode = proposalSpec.str("reason_code"),
                    preferredCorridor = CorridorChoice.valueOf(
                        proposalSpec.str("preferred_corridor"),
                    ),
                    evidenceScore = proposalSpec.num("evidence_score"),
                    criticalTrackIds = proposalSpec.arr("critical_track_ids")
                        .map { it.jsonPrimitive.content.toInt() }.toSet(),
                )
                val actual = machine.apply(
                    proposal,
                    nowMillis = (step.num("at_seconds") * 1000.0).roundToLong(),
                )
                val want = expected[index]
                val where = "[$name] step $index"
                assertEquals("$where action", want.str("action"), actual.action.name)
                assertEquals("$where level", want.str("level"), actual.level.name)
                assertEquals("$where reason_code", want.str("reason_code"), actual.reasonCode)
                assertEquals(
                    "$where preferred_corridor",
                    want.str("preferred_corridor"),
                    actual.preferredCorridor.name,
                )
                assertEquals(
                    "$where critical_track_ids",
                    want.arr("critical_track_ids").map { it.jsonPrimitive.content.toInt() },
                    actual.criticalTrackIds.sorted(),
                )
                assertEquals(
                    "$where speak",
                    want.getValue("speak").jsonPrimitive.content.toBoolean(),
                    actual.speak,
                )
            }
        }
    }
}
