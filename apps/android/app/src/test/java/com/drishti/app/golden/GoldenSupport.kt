package com.drishti.app.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.RawDetection
import kotlin.math.abs
import org.junit.Assert.assertTrue

/**
 * Loader and comparison helpers for the cross-language golden vectors exported
 * by `entire-old-codebase/backend/scripts/export_golden.py` (BUILD_PLAN.md A0).
 *
 * A failure here means the Kotlin port and the Python original disagree. That
 * is a parity bug, not a flaky test — do not widen [TOLERANCE] to make one pass.
 */
object GoldenSupport {

    /** Python float64 vs Kotlin Double: differences beyond this are real. */
    const val TOLERANCE = 1e-9

    /**
     * Tolerance for quantities that reach the vectors through OpenCV.
     *
     * `corridor.py` computes polygon intersection with
     * `cv2.intersectConvexConvex(np.asarray(..., dtype=np.float32), ...)` — the
     * reference value is therefore computed in **float32**, while the Kotlin
     * port clips in float64 and is the more exact of the two. Agreement closer
     * than float32 epsilon is not available and would not mean anything:
     * demanding it would force the port to reproduce a precision loss rather
     * than a behaviour. Every threshold these values feed (0.25 path-overlap
     * gate, 0.40 corridor block) sits far from this margin.
     */
    const val TOLERANCE_OPENCV_F32 = 1e-6

    private val json = Json { ignoreUnknownKeys = true }

    fun cases(fileName: String): List<JsonObject> {
        val stream = GoldenSupport::class.java.classLoader
            ?.getResourceAsStream("golden/$fileName")
            ?: error(
                "Missing golden/$fileName. Regenerate with: " +
                    "PYTHONPATH=. python scripts/export_golden.py"
            )
        val text = stream.bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        val cases = root.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue("$fileName must carry at least 8 cases, has ${cases.size}", cases.size >= 8)
        return cases
    }

    fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    fun JsonObject.num(key: String): Double = getValue(key).jsonPrimitive.double

    fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

    fun JsonObject.numOrNull(key: String): Double? {
        val element: JsonElement = get(key) ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.double
    }

    fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject

    fun JsonObject.arr(key: String): JsonArray = getValue(key).jsonArray

    fun rawDetection(element: JsonObject): RawDetection = RawDetection(
        label = element.str("label"),
        confidence = element.num("confidence"),
        x1 = element.num("x1"),
        y1 = element.num("y1"),
        x2 = element.num("x2"),
        y2 = element.num("y2"),
    )

    fun candidate(element: JsonObject): DetectionCandidate = DetectionCandidate(
        label = element.str("label"),
        confidence = element.num("confidence"),
        x1 = element.num("x1"),
        y1 = element.num("y1"),
        x2 = element.num("x2"),
        y2 = element.num("y2"),
    )

    fun assertClose(
        expected: Double,
        actual: Double,
        what: String,
        tolerance: Double = TOLERANCE,
    ) {
        assertTrue(
            "$what: expected $expected, got $actual (delta ${abs(expected - actual)})",
            abs(expected - actual) <= tolerance,
        )
    }

    fun assertCloseOrNull(expected: Double?, actual: Double?, what: String) {
        if (expected == null) {
            assertTrue("$what: expected null, got $actual", actual == null)
            return
        }
        assertTrue("$what: expected $expected, got null", actual != null)
        assertClose(expected, actual!!, what)
    }
}
