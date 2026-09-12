package com.drishti.app.inference

import androidx.camera.core.ImageProxy

/**
 * YUV_420_888 → upright, tightly-packed RGB888 (BUILD_PLAN.md task A6).
 *
 * Allocation-free after the first frame: the output array is reused and only
 * regrown when the camera resolution or rotation changes. No `Bitmap`, no
 * `YuvImage`, no JPEG round-trip — the walking path never encodes.
 */
class YuvToRgb {

    private var rgb: ByteArray = ByteArray(0)
    private var yPlane: ByteArray = ByteArray(0)
    private var uPlane: ByteArray = ByteArray(0)
    private var vPlane: ByteArray = ByteArray(0)

    /** Oriented dimensions of the most recent [convert]. */
    var width: Int = 0
        private set
    var height: Int = 0
        private set

    /**
     * @return the reused RGB buffer, valid until the next call. Rotation from
     *   [ImageProxy.getImageInfo] is applied so the result is upright and
     *   detections can be normalised against the oriented capture.
     */
    fun convert(image: ImageProxy): ByteArray {
        val rotation = ((image.imageInfo.rotationDegrees % 360) + 360) % 360
        val sourceWidth = image.width
        val sourceHeight = image.height
        val swap = rotation == 90 || rotation == 270
        width = if (swap) sourceHeight else sourceWidth
        height = if (swap) sourceWidth else sourceHeight

        val needed = width * height * 3
        if (rgb.size != needed) rgb = ByteArray(needed)

        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer
        if (yPlane.size != yBuffer.remaining()) yPlane = ByteArray(yBuffer.remaining())
        if (uPlane.size != uBuffer.remaining()) uPlane = ByteArray(uBuffer.remaining())
        if (vPlane.size != vBuffer.remaining()) vPlane = ByteArray(vBuffer.remaining())
        yBuffer.get(yPlane)
        uBuffer.get(uPlane)
        vBuffer.get(vPlane)

        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixelStride = planes[1].pixelStride

        for (sy in 0 until sourceHeight) {
            val uvRow = (sy / 2) * uvRowStride
            val yRow = sy * yRowStride
            for (sx in 0 until sourceWidth) {
                val y = yPlane[yRow + sx].toInt() and 0xFF
                val uvIndex = uvRow + (sx / 2) * uvPixelStride
                val u = (uPlane.getOrZero(uvIndex) and 0xFF) - 128
                val v = (vPlane.getOrZero(uvIndex) and 0xFF) - 128

                // BT.601 full-range, the conversion CameraX documents for
                // YUV_420_888 on this path.
                val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (y - 0.344136f * u - 0.714136f * v).toInt().coerceIn(0, 255)
                val b = (y + 1.772f * u).toInt().coerceIn(0, 255)

                val (dx, dy) = when (rotation) {
                    90 -> Pair(sourceHeight - 1 - sy, sx)
                    180 -> Pair(sourceWidth - 1 - sx, sourceHeight - 1 - sy)
                    270 -> Pair(sy, sourceWidth - 1 - sx)
                    else -> Pair(sx, sy)
                }
                val out = (dy * width + dx) * 3
                rgb[out] = r.toByte()
                rgb[out + 1] = g.toByte()
                rgb[out + 2] = b.toByte()
            }
        }
        return rgb
    }
}

private fun ByteArray.getOrZero(index: Int): Int =
    if (index in indices) this[index].toInt() else 0
