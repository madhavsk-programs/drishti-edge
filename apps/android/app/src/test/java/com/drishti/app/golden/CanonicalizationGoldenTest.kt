package com.drishti.app.golden

import com.drishti.app.golden.GoldenSupport.arr
import com.drishti.app.golden.GoldenSupport.assertClose
import com.drishti.app.golden.GoldenSupport.bool
import com.drishti.app.golden.GoldenSupport.candidate
import com.drishti.app.golden.GoldenSupport.int
import com.drishti.app.golden.GoldenSupport.num
import com.drishti.app.golden.GoldenSupport.obj
import com.drishti.app.golden.GoldenSupport.rawDetection
import com.drishti.app.golden.GoldenSupport.str
import com.drishti.app.perception.CANONICAL_LABELS
import com.drishti.app.perception.canonicalizeDetections
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** BUILD_PLAN.md A1 acceptance: `canonicalization.json` passes. */
class CanonicalizationGoldenTest {

    @Test
    fun matchesPythonCanonicalization() {
        val cases = GoldenSupport.cases("canonicalization.json")
        for (case in cases) {
            val name = case.str("name")
            val input = case.obj("input")
            val allowed = if (input.str("allowed_labels") == "CANONICAL") CANONICAL_LABELS else null

            val actual = canonicalizeDetections(
                detections = input.arr("detections").map { rawDetection(it.jsonObject) },
                width = input.int("width"),
                height = input.int("height"),
                confidenceThreshold = input.num("confidence_threshold"),
                allowedLabels = allowed,
                applyAliases = input.bool("apply_aliases"),
            )
            val expected = case.arr("expected").map { candidate(it.jsonObject) }

            assertEquals("[$name] detection count", expected.size, actual.size)
            expected.forEachIndexed { index, want ->
                val got = actual[index]
                assertEquals("[$name] #$index label", want.label, got.label)
                assertClose(want.confidence, got.confidence, "[$name] #$index confidence")
                assertClose(want.x1, got.x1, "[$name] #$index x1")
                assertClose(want.y1, got.y1, "[$name] #$index y1")
                assertClose(want.x2, got.x2, "[$name] #$index x2")
                assertClose(want.y2, got.y2, "[$name] #$index y2")
            }
        }
    }
}
