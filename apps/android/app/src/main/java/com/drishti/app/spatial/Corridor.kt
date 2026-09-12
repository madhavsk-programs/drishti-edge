package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.Direction
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.TrackedDetection
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of `app/spatial/corridor.py` (BUILD_PLAN.md task A3).
 *
 * The Python source computes polygon intersection with
 * `cv2.intersectConvexConvex`. Here that is Sutherland–Hodgman convex clipping
 * followed by the shoelace formula, which is exact for the convex polygons this
 * module uses (axis-aligned boxes against corridor trapezoids) rather than an
 * approximation of OpenCV's result.
 */

typealias Point = Pair<Double, Double>
typealias Polygon = List<Point>

const val MIN_WALKABLE_RATIO = 0.25
const val MIN_BLOCKING_SURFACE_RATIO = 0.20

data class SpatialTrack(
    val tracked: TrackedDetection,
    val proximity: RelativeProximity,
    val direction: Direction,
    val pathOverlap: Double,
)

/** Left / centre / right triple, mirroring the Python `CorridorCosts`. */
data class CorridorCosts(
    val leftCost: Double,
    val centreCost: Double,
    val rightCost: Double,
) {
    fun valueFor(choice: CorridorChoice): Double = when (choice) {
        CorridorChoice.LEFT -> leftCost
        CorridorChoice.CENTRE -> centreCost
        CorridorChoice.RIGHT -> rightCost
        CorridorChoice.NONE -> 0.0
    }
}

data class CorridorAnalysis(
    val tracks: List<SpatialTrack>,
    val costs: CorridorCosts,
    val preferred: CorridorChoice,
    val walkableChoices: Set<CorridorChoice>,
    val uncertainChoices: Set<CorridorChoice>,
    val safePolygons: List<Polygon>,
    val blockedPolygons: List<Polygon>,
    val uncertainPolygons: List<Polygon>,
    val wallRatios: CorridorCosts,
    val floorExtents: CorridorCosts,
    val stairsRatios: CorridorCosts,
    val wallDeadEnd: Boolean,
)

/**
 * Surface evidence per corridor, supplied by segmentation when it is available.
 *
 * Kept as a separate input rather than a segmentation frame so the decision
 * cascade can be tested without a segmentation model — and so that "no
 * segmentation" is representable as `null` rather than as zeros, which would
 * read as "measured, and clear".
 */
data class SurfaceEvidence(
    val walkableRatio: Map<CorridorChoice, Double>,
    val roadRatio: Map<CorridorChoice, Double>,
    val nonWalkableRatio: Map<CorridorChoice, Double>,
    val unknownRatio: Map<CorridorChoice, Double>,
    val wallRatio: Map<CorridorChoice, Double>,
    val stairsRatio: Map<CorridorChoice, Double>,
    val floorExtent: Map<CorridorChoice, Double>,
)

private val CORRIDOR_ORDER = listOf(
    CorridorChoice.LEFT,
    CorridorChoice.CENTRE,
    CorridorChoice.RIGHT,
)

fun corridorPolygons(settings: PipelineSettings): Map<CorridorChoice, Polygon> {
    val topLeft = 0.5 - settings.corridorTopHalfWidth
    val topRight = 0.5 + settings.corridorTopHalfWidth
    val bottomLeft = 0.5 - settings.corridorBottomHalfWidth
    val bottomRight = 0.5 + settings.corridorBottomHalfWidth

    fun point(fraction: Double, top: Boolean): Point {
        val left = if (top) topLeft else bottomLeft
        val right = if (top) topRight else bottomRight
        val y = if (top) settings.corridorHorizonY else 1.0
        return Pair(left + (right - left) * fraction, y)
    }

    val third = 1.0 / 3.0
    val twoThirds = 2.0 / 3.0
    return linkedMapOf(
        CorridorChoice.LEFT to listOf(
            point(0.0, true), point(third, true), point(third, false), point(0.0, false),
        ),
        CorridorChoice.CENTRE to listOf(
            point(third, true), point(twoThirds, true), point(twoThirds, false), point(third, false),
        ),
        CorridorChoice.RIGHT to listOf(
            point(twoThirds, true), point(1.0, true), point(1.0, false), point(twoThirds, false),
        ),
    )
}

fun directionForAnchor(x: Double, y: Double, settings: PipelineSettings): Direction {
    if (y < settings.corridorHorizonY || y > 1.0) return Direction.UNKNOWN
    val progress = (y - settings.corridorHorizonY) / (1.0 - settings.corridorHorizonY)
    val halfWidth = settings.corridorTopHalfWidth +
        progress * (settings.corridorBottomHalfWidth - settings.corridorTopHalfWidth)
    val left = 0.5 - halfWidth
    val right = 0.5 + halfWidth
    if (x < left || x > right) return Direction.UNKNOWN
    val fraction = (x - left) / (right - left)
    return when {
        fraction < 1.0 / 3.0 -> Direction.LEFT
        fraction < 2.0 / 3.0 -> Direction.CENTRE
        else -> Direction.RIGHT
    }
}

fun bboxPathOverlap(detection: DetectionCandidate, settings: PipelineSettings): Double {
    val bbox = bboxPolygon(detection)
    val bboxArea = max(
        1e-6,
        (detection.x2 - detection.x1) * (detection.y2 - detection.y1),
    )
    val fullCorridor = listOf(
        Pair(0.5 - settings.corridorTopHalfWidth, settings.corridorHorizonY),
        Pair(0.5 + settings.corridorTopHalfWidth, settings.corridorHorizonY),
        Pair(0.5 + settings.corridorBottomHalfWidth, 1.0),
        Pair(0.5 - settings.corridorBottomHalfWidth, 1.0),
    )
    return min(1.0, max(0.0, intersectionArea(bbox, fullCorridor) / bboxArea))
}

fun analyzeCorridors(
    tracks: List<TrackedDetection>,
    settings: PipelineSettings,
    surfaces: SurfaceEvidence? = null,
): CorridorAnalysis {
    val polygons = corridorPolygons(settings)
    val spatialTracks = ArrayList<SpatialTrack>(tracks.size)
    val costs = LinkedHashMap<CorridorChoice, Double>().apply {
        CORRIDOR_ORDER.forEach { put(it, 0.0) }
    }

    for (tracked in tracks) {
        val detection = tracked.detection
        val proximity = estimateRelativeProximity(detection, settings)
        spatialTracks.add(
            SpatialTrack(
                tracked = tracked,
                proximity = proximity,
                direction = directionForAnchor(
                    (detection.x1 + detection.x2) / 2,
                    detection.y2,
                    settings,
                ),
                pathOverlap = bboxPathOverlap(detection, settings),
            )
        )
        val bboxArea = max(
            1e-6,
            (detection.x2 - detection.x1) * (detection.y2 - detection.y1),
        )
        for (choice in CORRIDOR_ORDER) {
            val polygon = polygons.getValue(choice)
            val overlap = intersectionArea(bboxPolygon(detection), polygon) / bboxArea
            val contribution = min(
                1.0,
                overlap * (0.35 + 0.65 * proximity.score) * detection.confidence,
            )
            costs[choice] = 1.0 - (1.0 - costs.getValue(choice)) * (1.0 - contribution)
        }
    }

    val wallRatios = LinkedHashMap<CorridorChoice, Double>().apply {
        CORRIDOR_ORDER.forEach { put(it, 0.0) }
    }
    val floorExtents = LinkedHashMap<CorridorChoice, Double>().apply {
        CORRIDOR_ORDER.forEach { put(it, 0.0) }
    }
    val stairsRatios = LinkedHashMap<CorridorChoice, Double>().apply {
        CORRIDOR_ORDER.forEach { put(it, 0.0) }
    }

    if (surfaces != null) {
        for (choice in CORRIDOR_ORDER) {
            val surfaceCost = min(
                1.0,
                (surfaces.nonWalkableRatio[choice] ?: 0.0) +
                    settings.surfaceCostRoadWeight * (surfaces.roadRatio[choice] ?: 0.0) +
                    settings.surfaceCostUnknownWeight * (surfaces.unknownRatio[choice] ?: 0.0),
            )
            costs[choice] = 1.0 - (1.0 - costs.getValue(choice)) * (1.0 - surfaceCost)
            wallRatios[choice] = surfaces.wallRatio[choice] ?: 0.0
            stairsRatios[choice] = surfaces.stairsRatio[choice] ?: 0.0
            floorExtents[choice] = surfaces.floorExtent[choice] ?: 0.0
        }
    }

    val corridorCosts = CorridorCosts(
        leftCost = costs.getValue(CorridorChoice.LEFT),
        centreCost = costs.getValue(CorridorChoice.CENTRE),
        rightCost = costs.getValue(CorridorChoice.RIGHT),
    )
    val wallCorridorRatios = CorridorCosts(
        leftCost = wallRatios.getValue(CorridorChoice.LEFT),
        centreCost = wallRatios.getValue(CorridorChoice.CENTRE),
        rightCost = wallRatios.getValue(CorridorChoice.RIGHT),
    )
    val floorCorridorExtents = CorridorCosts(
        leftCost = floorExtents.getValue(CorridorChoice.LEFT),
        centreCost = floorExtents.getValue(CorridorChoice.CENTRE),
        rightCost = floorExtents.getValue(CorridorChoice.RIGHT),
    )
    val stairsCorridorRatios = CorridorCosts(
        leftCost = stairsRatios.getValue(CorridorChoice.LEFT),
        centreCost = stairsRatios.getValue(CorridorChoice.CENTRE),
        rightCost = stairsRatios.getValue(CorridorChoice.RIGHT),
    )

    val wallDeadEnd = floorCorridorExtents.centreCost <= settings.freespaceDeadEndMax &&
        floorCorridorExtents.leftCost < settings.freespaceSideOpenMin &&
        floorCorridorExtents.rightCost < settings.freespaceSideOpenMin &&
        wallCorridorRatios.centreCost >= settings.wallCentreRatioThreshold

    val walkableChoices = if (surfaces == null) {
        emptySet()
    } else {
        CORRIDOR_ORDER.filter {
            (surfaces.walkableRatio[it] ?: 0.0) >= MIN_WALKABLE_RATIO
        }.toSet()
    }
    val uncertainChoices = CORRIDOR_ORDER.filter { choice ->
        surfaces == null || (
            (surfaces.walkableRatio[choice] ?: 0.0) < MIN_WALKABLE_RATIO &&
                (surfaces.nonWalkableRatio[choice] ?: 0.0) < MIN_BLOCKING_SURFACE_RATIO
            )
    }.toSet()

    var preferred = preferredCorridor(costs, settings.corridorClearMargin)
    if (preferred == CorridorChoice.NONE &&
        CorridorChoice.CENTRE in walkableChoices &&
        costs.getValue(CorridorChoice.CENTRE) < settings.riskCentreBlockThreshold
    ) {
        preferred = CorridorChoice.CENTRE
    }

    fun blockThreshold(choice: CorridorChoice): Double =
        if (choice == CorridorChoice.CENTRE) {
            settings.riskCentreBlockThreshold
        } else {
            settings.riskSideBlockThreshold
        }

    val safe = if (
        preferred in polygons &&
        preferred in walkableChoices &&
        costs.getValue(preferred) < blockThreshold(preferred)
    ) {
        listOf(polygons.getValue(preferred))
    } else {
        emptyList()
    }
    val blocked = CORRIDOR_ORDER
        .filter { costs.getValue(it) >= blockThreshold(it) }
        .map { polygons.getValue(it) }
    val uncertain = CORRIDOR_ORDER
        .filter { it in uncertainChoices }
        .map { polygons.getValue(it) }

    return CorridorAnalysis(
        tracks = spatialTracks,
        costs = corridorCosts,
        preferred = preferred,
        walkableChoices = walkableChoices,
        uncertainChoices = uncertainChoices,
        safePolygons = safe,
        blockedPolygons = blocked,
        uncertainPolygons = uncertain,
        wallRatios = wallCorridorRatios,
        floorExtents = floorCorridorExtents,
        stairsRatios = stairsCorridorRatios,
        wallDeadEnd = wallDeadEnd,
    )
}

private fun preferredCorridor(
    costs: Map<CorridorChoice, Double>,
    margin: Double,
): CorridorChoice {
    val ranked = CORRIDOR_ORDER
        .map { it to costs.getValue(it) }
        .sortedBy { it.second }
    return if (ranked[1].second - ranked[0].second < margin) {
        CorridorChoice.NONE
    } else {
        ranked[0].first
    }
}

internal fun bboxPolygon(detection: DetectionCandidate): Polygon = listOf(
    Pair(detection.x1, detection.y1),
    Pair(detection.x2, detection.y1),
    Pair(detection.x2, detection.y2),
    Pair(detection.x1, detection.y2),
)

/**
 * Area of the intersection of two CONVEX polygons.
 *
 * Sutherland–Hodgman clipping is only valid when the clip polygon is convex,
 * which holds for every caller here. Both inputs are treated as counter-
 * clockwise-or-clockwise agnostic by taking the absolute shoelace area.
 */
internal fun intersectionArea(subject: Polygon, clip: Polygon): Double {
    var output: List<Point> = subject
    if (output.isEmpty()) return 0.0

    val clipOriented = orientCounterClockwise(clip)
    for (i in clipOriented.indices) {
        if (output.isEmpty()) return 0.0
        val a = clipOriented[i]
        val b = clipOriented[(i + 1) % clipOriented.size]
        val input = output
        val next = ArrayList<Point>(input.size + 1)
        for (j in input.indices) {
            val current = input[j]
            val previous = input[(j + input.size - 1) % input.size]
            val currentInside = isInside(current, a, b)
            val previousInside = isInside(previous, a, b)
            if (currentInside) {
                if (!previousInside) {
                    lineIntersection(previous, current, a, b)?.let { next.add(it) }
                }
                next.add(current)
            } else if (previousInside) {
                lineIntersection(previous, current, a, b)?.let { next.add(it) }
            }
        }
        output = next
    }
    return shoelaceArea(output)
}

private fun orientCounterClockwise(polygon: Polygon): Polygon =
    if (signedArea(polygon) < 0) polygon.reversed() else polygon

private fun signedArea(polygon: Polygon): Double {
    if (polygon.size < 3) return 0.0
    var total = 0.0
    for (i in polygon.indices) {
        val (x1, y1) = polygon[i]
        val (x2, y2) = polygon[(i + 1) % polygon.size]
        total += x1 * y2 - x2 * y1
    }
    return total / 2.0
}

internal fun shoelaceArea(polygon: Polygon): Double = abs(signedArea(polygon))

/** Left of, or on, the directed edge a→b. */
private fun isInside(point: Point, a: Point, b: Point): Boolean =
    (b.first - a.first) * (point.second - a.second) -
        (b.second - a.second) * (point.first - a.first) >= 0.0

private fun lineIntersection(p1: Point, p2: Point, a: Point, b: Point): Point? {
    val r1 = p2.first - p1.first
    val r2 = p2.second - p1.second
    val s1 = b.first - a.first
    val s2 = b.second - a.second
    val denominator = r1 * s2 - r2 * s1
    if (abs(denominator) < 1e-12) return null
    val t = ((a.first - p1.first) * s2 - (a.second - p1.second) * s1) / denominator
    return Pair(p1.first + t * r1, p1.second + t * r2)
}
