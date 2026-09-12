package com.drishti.app.spatial

import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.SurfaceKind

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

    fun build(
        segmentation: SegFormerSegmenter.SegmentationFrame,
        letterbox: Letterbox,
        settings: PipelineSettings,
    ): SurfaceEvidence {
        val polygons = corridorPolygons(settings)
        // The output map is a fixed fraction of the input tensor (128 for 512),
        // so tensor pixels scale straight down to map pixels.
        val scaleX = segmentation.width.toDouble() / letterbox.targetWidth
        val scaleY = segmentation.height.toDouble() / letterbox.targetHeight

        val walkable = HashMap<CorridorChoice, Double>()
        val road = HashMap<CorridorChoice, Double>()
        val nonWalkable = HashMap<CorridorChoice, Double>()
        val unknown = HashMap<CorridorChoice, Double>()
        val wall = HashMap<CorridorChoice, Double>()
        val stairs = HashMap<CorridorChoice, Double>()
        val floorExtent = HashMap<CorridorChoice, Double>()

        for (choice in ORDER) {
            val mapPolygon = polygons.getValue(choice).map { (nx, ny) ->
                val (tx, ty) = letterbox.fromOrientedNormalized(nx, ny)
                Pair(tx * scaleX, ty * scaleY)
            }
            val mask = rasterize(mapPolygon, segmentation.width, segmentation.height)

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
                when (segmentation.kind[p]) {
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
            floorExtent[choice] = floorExtent(mask, segmentation)
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
     */
    private fun floorExtent(
        mask: BooleanArray,
        segmentation: SegFormerSegmenter.SegmentationFrame,
    ): Double {
        val width = segmentation.width
        val height = segmentation.height
        val extents = ArrayList<Double>(width)
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
            var run = 0
            for (y in bottom downTo top) {
                val index = y * width + x
                if (!mask[index] || segmentation.kind[index] != SurfaceKind.WALKABLE.ordinal) break
                run++
            }
            extents.add(run.toDouble() / maxOf(1, bottom - top + 1))
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
