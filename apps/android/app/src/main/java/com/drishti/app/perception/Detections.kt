package com.drishti.app.perception

/**
 * Kotlin port of `app/perception/detector.py` (BUILD_PLAN.md task A1).
 *
 * Values are [Double], not [Float], so that arithmetic here matches the Python
 * float64 source bit-for-bit closely enough to pass the golden vectors. Parity
 * is the point of the port; a narrower type would spend that parity to save
 * bytes the pipeline does not need.
 */

/**
 * The audited labels that are allowed to reach the safety engine.
 *
 * The list is an allowlist because a COCO label reaching the risk cascade is a
 * claim that the system understands how that object behaves in a walking path.
 * It began at 19 and was missing obstacles a walker genuinely has to be warned
 * about: `truck` and `train` (both also absent from `VEHICLE_LABELS`, so an
 * approaching lorry could not raise `APPROACHING_VEHICLE_CENTRE` at all), `dog`,
 * and the fixed street furniture people walk into.
 */
val CANONICAL_LABELS: Set<String> = setOf(
    "person",
    "chair",
    "bag",
    "desk",
    "bicycle",
    "motorcycle",
    "car",
    "bus",
    "truck",
    "train",
    "dog",
    "fire hydrant",
    "stop sign",
    "parking meter",
    "traffic light",
    "bench",
    // `door` is NOT a COCO class. It is kept for parity with the Python source;
    // the deployed detector cannot produce it and the system does not claim
    // door detection (docs/SAFETY_RULES.md).
    "door",
    "suitcase",
    "umbrella",
    // Things that sit ON surfaces and on floors. Measured over five consecutive
    // Walk Mode frames of one desk, YOLO11n reported `laptop` at 0.70-0.80 on
    // EVERY frame while `dining table` appeared on one — the clutter is the most
    // reliable evidence in the scene and all of it was being discarded. A laptop,
    // bottle or bowl underfoot is something a walker kicks over; in the corridor
    // it is also the plainest sign the plane ahead is a worktop, not a floor.
    "laptop",
    "bottle",
    "cup",
    "bowl",
    "vase",
    "book",
    "keyboard",
    "skateboard",
    "sports ball",
    "microwave",
    "oven",
    "toaster",
    "potted plant",
    "couch",
    "bed",
    "tv",
    "refrigerator",
    "sink",
    "toilet",
)

/**
 * Labels that only ever rest on a RAISED horizontal surface.
 *
 * None of these is ever found sitting on a walking floor, so the base of one is
 * a measurement: the plane it stands on is a worktop, and everything nearer than
 * it in the same column is that same worktop.
 *
 * This exists because the desk itself is not reliably detectable. Measured over
 * sixteen CONSECUTIVE Walk Mode frames of a phone sitting on a desk, YOLO11n
 * reported `dining table` on ONE of them — a 6% hit rate, which no amount of
 * tracking or coasting can turn into a stable obstacle. It reported `laptop` on
 * all sixteen, at 0.76-0.92. The furniture is invisible to the detector; the
 * things on top of it are the most confident detections in the frame.
 *
 * Deliberately the strong cases only. A bottle, cup, book or bag genuinely does
 * end up on floors, so none of those is a witness — they are ordinary obstacles.
 * `tv` is excluded for the opposite reason: it is the one member of this family
 * that is routinely mounted on a wall, where there is no surface underneath it
 * at all.
 */
val SURFACE_WITNESS_LABELS: Set<String> = setOf(
    "laptop", "keyboard", "microwave", "oven", "toaster",
)

/** Below this, a witness is not confident enough to redefine a surface. */
const val SURFACE_WITNESS_MIN_CONFIDENCE = 0.50

val LABEL_ALIASES: Map<String, String> = mapOf(
    "backpack" to "bag",
    "handbag" to "bag",
    "dining table" to "desk",
    "table" to "desk",
)

/** A detector output before normalization, in pixel coordinates. */
data class RawDetection(
    val label: String,
    val confidence: Double,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)

/** A detection normalized to the unit square. */
data class DetectionCandidate(
    val label: String,
    val confidence: Double,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
)

/**
 * Two label filterings of ONE detector invocation (ARCHITECTURE.md §9.3).
 *
 * [risk] is the audited allowlist that feeds tracking, spatial reasoning and
 * the risk engine. [all] is the full native COCO output and feeds landmark
 * memory only — a COCO label says an object is present, it does not establish
 * that the object obstructs the walking corridor.
 */
data class DetectionSet(
    val risk: List<DetectionCandidate>,
    val all: List<DetectionCandidate>,
)

/**
 * Normalize raw boxes to the unit square.
 *
 * @param allowedLabels `null` keeps every above-threshold class.
 * @param applyAliases MUST be `false` for the full set: the aliases collapse
 *   `backpack` and `handbag` into `bag`, which discards the exact word a user
 *   says when asking to be guided to one.
 */
fun canonicalizeDetections(
    detections: List<RawDetection>,
    width: Int,
    height: Int,
    confidenceThreshold: Double,
    allowedLabels: Set<String>? = CANONICAL_LABELS,
    applyAliases: Boolean = true,
): List<DetectionCandidate> {
    require(width > 0 && height > 0) { "Detection image dimensions must be positive." }

    val canonical = ArrayList<DetectionCandidate>(detections.size)
    for (item in detections) {
        if (!item.confidence.isFinite() ||
            !item.x1.isFinite() || !item.y1.isFinite() ||
            !item.x2.isFinite() || !item.y2.isFinite()
        ) {
            continue
        }
        var label = item.label.lowercase()
        if (applyAliases) {
            label = LABEL_ALIASES[label] ?: label
        }
        if (allowedLabels != null && label !in allowedLabels) continue
        if (item.confidence < confidenceThreshold) continue

        val x1 = clampUnit(item.x1 / width)
        val y1 = clampUnit(item.y1 / height)
        val x2 = clampUnit(item.x2 / width)
        val y2 = clampUnit(item.y2 / height)
        if (x1 >= x2 || y1 >= y2) continue

        canonical.add(
            DetectionCandidate(
                label = label,
                confidence = clampUnit(item.confidence),
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
            )
        )
    }
    return canonical
}

internal fun clampUnit(value: Double): Double = minOf(1.0, maxOf(0.0, value))
