package com.drishti.app.inference

import kotlin.math.max
import kotlin.math.min

/**
 * Non-maximum suppression for the raw YOLO11 head (BUILD_PLAN.md task A6).
 *
 * The exported model applies no NMS — `output0` is 8400 raw anchors — so this
 * runs on every frame and is the CPU cost that actually matters once inference
 * is on the NPU.
 */

/** A detection in tensor-pixel space, before un-letterboxing. */
data class RawBox(
    val classId: Int,
    val score: Double,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

/**
 * Per-class greedy NMS, matching Ultralytics' default (`agnostic=False`).
 *
 * @param iouThreshold boxes overlapping the kept box by more than this are
 *   suppressed, but only within the same class.
 * @param maxDetections hard cap so a pathological frame cannot stall the loop.
 */
fun nonMaximumSuppression(
    boxes: List<RawBox>,
    iouThreshold: Double,
    maxDetections: Int = 300,
): List<RawBox> {
    if (boxes.isEmpty()) return emptyList()
    val kept = ArrayList<RawBox>(min(boxes.size, maxDetections))

    for ((_, group) in boxes.groupBy { it.classId }) {
        val candidates = group.sortedByDescending { it.score }
        val suppressed = BooleanArray(candidates.size)
        for (i in candidates.indices) {
            if (suppressed[i]) continue
            val winner = candidates[i]
            kept.add(winner)
            for (j in i + 1 until candidates.size) {
                if (suppressed[j]) continue
                if (iou(winner, candidates[j]) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
    }
    return kept.sortedByDescending { it.score }.take(maxDetections)
}

internal fun iou(a: RawBox, b: RawBox): Double {
    val interWidth = max(0.0, min(a.right, b.right) - max(a.left, b.left))
    val interHeight = max(0.0, min(a.bottom, b.bottom) - max(a.top, b.top))
    val intersection = interWidth * interHeight
    if (intersection <= 0.0) return 0.0
    val areaA = max(0.0, a.right - a.left) * max(0.0, a.bottom - a.top)
    val areaB = max(0.0, b.right - b.left) * max(0.0, b.bottom - b.top)
    val union = areaA + areaB - intersection
    return if (union > 0) intersection / union else 0.0
}

/**
 * Decode the raw `[1, 84, 8400]` head into boxes above [confidenceThreshold].
 *
 * The tensor is channel-major: element (channel, anchor) sits at
 * `channel * anchors + anchor`. Rows 0–3 are cx, cy, w, h in TENSOR pixels;
 * rows 4 onward are per-class scores, already activated by the export.
 */
fun decodeYoloHead(
    output: FloatArray,
    channels: Int,
    anchors: Int,
    confidenceThreshold: Double,
): List<RawBox> {
    val classCount = channels - 4
    require(classCount > 0) { "Expected >4 channels, got $channels" }
    val boxes = ArrayList<RawBox>(64)

    for (anchor in 0 until anchors) {
        var bestClass = -1
        var bestScore = confidenceThreshold
        for (c in 0 until classCount) {
            val score = output[(4 + c) * anchors + anchor].toDouble()
            if (score >= bestScore) {
                bestScore = score
                bestClass = c
            }
        }
        if (bestClass < 0) continue

        val cx = output[anchor].toDouble()
        val cy = output[anchors + anchor].toDouble()
        val w = output[2 * anchors + anchor].toDouble()
        val h = output[3 * anchors + anchor].toDouble()
        boxes.add(
            RawBox(
                classId = bestClass,
                score = bestScore,
                left = cx - w / 2,
                top = cy - h / 2,
                right = cx + w / 2,
                bottom = cy + h / 2,
            )
        )
    }
    return boxes
}
