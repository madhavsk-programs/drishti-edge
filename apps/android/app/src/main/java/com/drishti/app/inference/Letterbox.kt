package com.drishti.app.inference

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Letterbox geometry for the 640×640 detector input (BUILD_PLAN.md task A6).
 *
 * Pure arithmetic, deliberately separated from any image buffer so the
 * round-trip can be unit-tested without a device. Getting the inverse wrong is
 * silent: boxes land slightly off in a way that reads as camera jitter rather
 * than as a bug (ARCHITECTURE.md §9.3).
 */
data class Letterbox(
    /** Width of the oriented capture the boxes must be expressed against. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
) {
    val scale: Double = min(
        targetWidth.toDouble() / sourceWidth,
        targetHeight.toDouble() / sourceHeight,
    )

    /** Size of the image content inside the letterboxed tensor, in pixels. */
    val scaledWidth: Int = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight: Int = (sourceHeight * scale).roundToInt().coerceAtLeast(1)

    val padX: Int = (targetWidth - scaledWidth) / 2
    val padY: Int = (targetHeight - scaledHeight) / 2

    /**
     * Map a point from tensor pixels back to ORIENTED_CAPTURE_NORMALIZED — the
     * unit square of the full oriented capture, NOT of the letterboxed tensor.
     */
    fun toOrientedNormalized(tensorX: Double, tensorY: Double): Pair<Double, Double> {
        val x = (tensorX - padX) / scaledWidth
        val y = (tensorY - padY) / scaledHeight
        return Pair(x.coerceIn(0.0, 1.0), y.coerceIn(0.0, 1.0))
    }

    /** Forward map, for tests and for drawing debug overlays. */
    fun fromOrientedNormalized(normX: Double, normY: Double): Pair<Double, Double> =
        Pair(normX * scaledWidth + padX, normY * scaledHeight + padY)

    /**
     * Nearest-neighbour source pixel for a tensor pixel, or `null` when the
     * tensor pixel lies in the padding band.
     */
    fun sourcePixelFor(tensorX: Int, tensorY: Int): Pair<Int, Int>? {
        val insideX = tensorX - padX
        val insideY = tensorY - padY
        if (insideX < 0 || insideY < 0 || insideX >= scaledWidth || insideY >= scaledHeight) {
            return null
        }
        val sx = (insideX / scale).toInt().coerceIn(0, sourceWidth - 1)
        val sy = (insideY / scale).toInt().coerceIn(0, sourceHeight - 1)
        return Pair(sx, sy)
    }
}
