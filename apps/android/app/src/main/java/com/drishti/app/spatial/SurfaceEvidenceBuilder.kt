package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.ProximityBand
import com.drishti.app.net.SurfaceKind
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.SURFACE_WITNESS_LABELS
import com.drishti.app.perception.SURFACE_WITNESS_MIN_CONFIDENCE

/**
 * Turn a segmentation frame into per-corridor surface evidence
 * (port of the `_surface_ratios` / `_floor_extent` half of `corridor.py`).
 *
 * The segmentation map lives in LETTERBOXED tensor space; corridor polygons
 * live in ORIENTED_CAPTURE_NORMALIZED. Mapping between them is the whole job,
 * and getting it wrong is silent — the corridors would sample the padding band
 * and report a confidently wrong floor.
 */
object SurfaceEvidenceBuilder {

    private val ORDER = listOf(CorridorChoice.LEFT, CorridorChoice.CENTRE, CorridorChoice.RIGHT)

    /**
     * @param occluders detections from THIS frame. Pixels inside one are not
     *   counted as walkable floor no matter what the segmenter called them —
     *   see [occlusionMask].
     */
    fun build(
        segmentation: SegFormerSegmenter.SegmentationFrame,
        letterbox: Letterbox,
        settings: PipelineSettings,
        occluders: List<DetectionCandidate> = emptyList(),
    ): SurfaceEvidence {
        val polygons = corridorPolygons(settings)
        // The output map is a fixed fraction of the input tensor (128 for 512),
        // so tensor pixels scale straight down to map pixels.
        val scaleX = segmentation.width.toDouble() / letterbox.targetWidth
        val scaleY = segmentation.height.toDouble() / letterbox.targetHeight
        val occluded = occlusionMask(occluders, segmentation, letterbox, scaleX, scaleY, settings)

        val walkable = HashMap<CorridorChoice, Double>()
        val road = HashMap<CorridorChoice, Double>()
        val nonWalkable = HashMap<CorridorChoice, Double>()
        val unknown = HashMap<CorridorChoice, Double>()
        val wall = HashMap<CorridorChoice, Double>()
        val stairs = HashMap<CorridorChoice, Double>()
        val floorExtent = HashMap<CorridorChoice, Double>()

        val masks = corridorMasks(polygons, letterbox, segmentation, scaleX, scaleY)
        for (choice in ORDER) {
            val mask = masks.getValue(choice)

            var total = 0
            var walkableCount = 0
            var roadCount = 0
            var nonWalkableCount = 0
            var unknownCount = 0
            var wallCount = 0
            var stairsCount = 0
            for (p in mask.indices) {
                if (!mask[p]) continue
                total++
                when (kindAt(segmentation, occluded, p)) {
                    SurfaceKind.WALKABLE.ordinal -> walkableCount++
                    SurfaceKind.ROAD.ordinal -> roadCount++
                    SurfaceKind.NON_WALKABLE.ordinal -> nonWalkableCount++
                    else -> unknownCount++
                }
                if (segmentation.wall[p]) wallCount++
                if (segmentation.hazard[p]) stairsCount++
            }
            val denominator = maxOf(1, total).toDouble()
            walkable[choice] = walkableCount / denominator
            road[choice] = roadCount / denominator
            nonWalkable[choice] = nonWalkableCount / denominator
            unknown[choice] = unknownCount / denominator
            wall[choice] = wallCount / denominator
            stairs[choice] = stairsCount / denominator
            floorExtent[choice] = floorExtent(mask, segmentation, occluded)
        }

        return SurfaceEvidence(
            walkableRatio = walkable,
            roadRatio = road,
            nonWalkableRatio = nonWalkable,
            unknownRatio = unknown,
            wallRatio = wall,
            stairsRatio = stairs,
            floorExtent = floorExtent,
        )
    }

    /**
     * Median contiguous visible-floor run measured UPWARD from each corridor
     * column's base, normalised by that column's height.
     *
     * Measuring from the base is the point: a floor that is visible near the
     * user's feet but interrupted further out is a short extent, and that is
     * exactly the "wall or dead end ahead" evidence the cascade needs.
     *
     * Only columns that span at least [MIN_COLUMN_SPAN_RATIO] of the corridor's
     * tallest column are counted. A corridor is a perspective TRAPEZOID: its
     * outer columns are clipped to a sliver near the bottom of the frame, where
     * the ground at the user's feet is almost always floor, so each of them
     * reports an extent near 1.0. Those slivers outnumber the full-height
     * columns roughly five to one at the corridor's proportions, so an unfiltered
     * median read ~1.0 — "clear all the way ahead" — with a chair pressed
     * against the lens. A column has to run most of the corridor's depth before
     * it can say anything about how far ahead the floor continues.
     */
    private fun floorExtent(
        mask: BooleanArray,
        segmentation: SegFormerSegmenter.SegmentationFrame,
        occluded: BooleanArray?,
    ): Double {
        val width = segmentation.width
        val height = segmentation.height
        val spans = IntArray(width)
        val bottoms = IntArray(width) { -1 }
        val tops = IntArray(width) { -1 }
        for (x in 0 until width) {
            var top = -1
            var bottom = -1
            for (y in 0 until height) {
                if (mask[y * width + x]) {
                    if (top < 0) top = y
                    bottom = y
                }
            }
            if (top < 0) continue
            tops[x] = top
            bottoms[x] = bottom
            spans[x] = bottom - top + 1
        }
        val tallest = spans.maxOrNull() ?: 0
        if (tallest <= 0) return 0.0
        val minimumSpan = (tallest * MIN_COLUMN_SPAN_RATIO).toInt().coerceAtLeast(1)

        val extents = ArrayList<Double>(width)
        for (x in 0 until width) {
            if (spans[x] < minimumSpan) continue
            val bottom = bottoms[x]
            val top = tops[x]
            var run = 0
            for (y in bottom downTo top) {
                val index = y * width + x
                if (!mask[index] ||
                    kindAt(segmentation, occluded, index) != SurfaceKind.WALKABLE.ordinal
                ) {
                    break
                }
                run++
            }
            extents.add(run.toDouble() / spans[x])
        }
        if (extents.isEmpty()) return 0.0
        extents.sort()
        val middle = extents.size / 2
        return if (extents.size % 2 == 0) {
            (extents[middle - 1] + extents[middle]) / 2.0
        } else {
            extents[middle]
        }
    }

    /** Share of the corridor's depth a column must span to be measured. */
    internal const val MIN_COLUMN_SPAN_RATIO = 0.80

    /**
     * A detection whose BASE proves the plane under it is not a floor.
     *
     * Gated on PROXIMITY rather than on the horizon line. A laptop on a desk
     * across the room is standing on a different surface, with floor in between,
     * and shadowing everything below it would condemn that floor; the same
     * laptop at arm's length is standing on the worktop the user is about to
     * walk into. Where the base falls relative to the horizon turns out to be a
     * knife-edge — measured at 0.39 against a horizon of 0.38 on one frame and
     * above it on the next — whereas apparent size and height together are the
     * calibrated distance signal the rest of the pipeline already trusts.
     */
    private fun isSurfaceWitness(box: DetectionCandidate, settings: PipelineSettings): Boolean =
        box.label in SURFACE_WITNESS_LABELS &&
            box.confidence >= SURFACE_WITNESS_MIN_CONFIDENCE &&
            estimateRelativeProximity(box, settings).band != ProximityBand.FAR

    /** The segmenter's answer, downgraded where a detection stands in the way. */
    private fun kindAt(
        segmentation: SegFormerSegmenter.SegmentationFrame,
        occluded: BooleanArray?,
        index: Int,
    ): Int {
        val kind = segmentation.kind[index]
        if (occluded == null || !occluded[index]) return kind
        // Only the walkable claim is withdrawn. A detection is evidence that
        // something is THERE, not evidence about what the surface behind it is,
        // so the honest downgrade is to UNKNOWN rather than to NON_WALKABLE.
        return if (kind == SurfaceKind.WALKABLE.ordinal) SurfaceKind.UNKNOWN.ordinal else kind
    }

    /**
     * Map pixels covered by a detection box.
     *
     * Detection and segmentation disagree about surfaces, and when they do the
     * detector is the one holding positive evidence. A SegFormer-B0 argmax calls
     * a wooden desktop viewed along its length `floor` — measured on a real Walk
     * Mode frame, 99.7% of the centre corridor came back WALKABLE with a desk
     * filling it, floor extent 1.00, and the cascade answered PATH_CLEAR. YOLO
     * saw the same desk as `dining table`. Nothing in the audited risk-label set
     * is a surface a person can walk on, so a box from it withdraws the walkable
     * claim underneath.
     *
     * Boxes are rectangles and a standing person's box contains the floor around
     * their legs, which is why this downgrades rather than blocks: the floor
     * ahead simply stops being CLAIMED from the obstacle onward, which is what
     * the free-space extent is supposed to measure in the first place.
     */
    private fun occlusionMask(
        occluders: List<DetectionCandidate>,
        segmentation: SegFormerSegmenter.SegmentationFrame,
        letterbox: Letterbox,
        scaleX: Double,
        scaleY: Double,
        settings: PipelineSettings,
    ): BooleanArray? {
        if (occluders.isEmpty()) return null
        val width = segmentation.width
        val height = segmentation.height
        val mask = BooleanArray(width * height)
        for (box in occluders) {
            val (tx1, ty1) = letterbox.fromOrientedNormalized(box.x1, box.y1)
            val (tx2, ty2) = letterbox.fromOrientedNormalized(box.x2, box.y2)
            val x1 = (tx1 * scaleX).toInt().coerceIn(0, width - 1)
            val x2 = (tx2 * scaleX).toInt().coerceIn(0, width - 1)
            val y1 = (ty1 * scaleY).toInt().coerceIn(0, height - 1)
            // A surface witness casts its shadow all the way to the bottom of
            // the frame: everything nearer than its base, in its own columns, is
            // the worktop it is standing on. Its own x-range only — the desk
            // certainly extends further, but by how far is not measured.
            val bottom = if (isSurfaceWitness(box, settings)) height - 1 else {
                (ty2 * scaleY).toInt().coerceIn(0, height - 1)
            }
            for (y in y1..bottom) {
                val row = y * width
                for (x in x1..x2) mask[row + x] = true
            }
        }
        return mask
    }

    /**
     * Corridor masks for one map geometry, rasterized once and reused.
     *
     * The corridor polygons are fixed by [PipelineSettings] and the map size is
     * fixed by the model, so these masks are the same every frame. Surface
     * evidence is now rebuilt on EVERY frame — the detections that veto a
     * walkable claim change faster than the segmentation stride — and
     * re-rasterizing three quads over a 128x128 map each time would have made
     * that rebuild cost more than the thing it is correcting.
     */
    @Volatile
    private var cachedMasks: Pair<MaskKey, Map<CorridorChoice, BooleanArray>>? = null

    private data class MaskKey(
        val width: Int,
        val height: Int,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val padX: Int,
        val padY: Int,
        val horizonY: Double,
        val topHalfWidth: Double,
        val bottomHalfWidth: Double,
    )

    private fun corridorMasks(
        polygons: Map<CorridorChoice, List<Pair<Double, Double>>>,
        letterbox: Letterbox,
        segmentation: SegFormerSegmenter.SegmentationFrame,
        scaleX: Double,
        scaleY: Double,
    ): Map<CorridorChoice, BooleanArray> {
        val first = polygons.getValue(CorridorChoice.CENTRE).first()
        val key = MaskKey(
            segmentation.width, segmentation.height,
            letterbox.scaledWidth, letterbox.scaledHeight, letterbox.padX, letterbox.padY,
            first.second, polygons.getValue(CorridorChoice.LEFT).first().first, first.first,
        )
        cachedMasks?.let { (cachedKey, masks) -> if (cachedKey == key) return masks }
        val built = ORDER.associateWith { choice ->
            val mapPolygon = polygons.getValue(choice).map { (nx, ny) ->
                val (tx, ty) = letterbox.fromOrientedNormalized(nx, ny)
                Pair(tx * scaleX, ty * scaleY)
            }
            rasterize(mapPolygon, segmentation.width, segmentation.height)
        }
        cachedMasks = key to built
        return built
    }

    /** Convex polygon fill; the corridor shapes are always convex quads. */
    private fun rasterize(polygon: List<Pair<Double, Double>>, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        if (polygon.size < 3) return mask

        val minY = polygon.minOf { it.second }.toInt().coerceIn(0, height - 1)
        val maxY = polygon.maxOf { it.second }.toInt().coerceIn(0, height - 1)
        val minX = polygon.minOf { it.first }.toInt().coerceIn(0, width - 1)
        val maxX = polygon.maxOf { it.first }.toInt().coerceIn(0, width - 1)

        val oriented = if (signedArea(polygon) < 0) polygon.reversed() else polygon
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                if (contains(oriented, x + 0.5, y + 0.5)) mask[y * width + x] = true
            }
        }
        return mask
    }

    private fun contains(polygon: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val cross = (b.first - a.first) * (y - a.second) - (b.second - a.second) * (x - a.first)
            if (cross < 0) return false
        }
        return true
    }

    private fun signedArea(polygon: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in polygon.indices) {
            val (x1, y1) = polygon[i]
            val (x2, y2) = polygon[(i + 1) % polygon.size]
            total += x1 * y2 - x2 * y1
        }
        return total / 2.0
    }
}
