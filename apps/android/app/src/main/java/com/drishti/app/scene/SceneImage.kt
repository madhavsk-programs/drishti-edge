package com.drishti.app.scene

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.drishti.app.inference.OrientedFrame

/**
 * Scale a camera frame down to the bound the Scene VLM is measured at.
 *
 * The bound is not a guess and not a memory limit. `docs/SCENE_MODE_VLM.md` §4.2
 * measured vision-encoder cost as superlinear in pixels with a hard cliff — a
 * 512 px image cost about ten times a 320 px one — and measured the floor
 * underneath it: at 256 px the model stopped reading a sign and started
 * inventing text. 320 px is the point between those two facts.
 *
 * Aspect ratio is preserved. A stretched frame would move objects relative to
 * each other, and the answer describes where things are.
 */
object SceneImage {

    /**
     * Nearest-neighbour, matching the detector's [com.drishti.app.inference.Letterbox]
     * rather than introducing a second sampling rule. There is no letterbox
     * padding here: the VLM has no fixed input geometry, so padding would only
     * add grey pixels for it to describe.
     *
     * @return RGB888 of [outWidth] * [outHeight] * 3 bytes, or null if the frame
     *   is empty or malformed.
     */
    fun scale(frame: OrientedFrame, longEdge: Int = SceneVlm.IMAGE_LONG_EDGE): Scaled? {
        if (frame.width <= 0 || frame.height <= 0) return null
        if (frame.rgb.size < frame.width * frame.height * 3) return null

        val scale = longEdge.toDouble() / maxOf(frame.width, frame.height)
        // Already small enough: copy rather than upscale. Upscaling would cost
        // encode time for detail that is not there.
        val outWidth = if (scale >= 1.0) frame.width else maxOf(1, (frame.width * scale).toInt())
        val outHeight = if (scale >= 1.0) frame.height else maxOf(1, (frame.height * scale).toInt())

        val out = ByteArray(outWidth * outHeight * 3)
        val xMap = IntArray(outWidth) { x ->
            ((x.toLong() * frame.width) / outWidth).toInt().coerceAtMost(frame.width - 1)
        }
        for (y in 0 until outHeight) {
            val sy = ((y.toLong() * frame.height) / outHeight).toInt().coerceAtMost(frame.height - 1)
            val srcRow = sy * frame.width * 3
            val dstRow = y * outWidth * 3
            for (x in 0 until outWidth) {
                val si = srcRow + xMap[x] * 3
                val di = dstRow + x * 3
                out[di] = frame.rgb[si]
                out[di + 1] = frame.rgb[si + 1]
                out[di + 2] = frame.rgb[si + 2]
            }
        }
        return Scaled(out, outWidth, outHeight)
    }

    /**
     * Decode a captured still to the same bounded RGB the VLM expects.
     *
     * This exists so Scene Mode can reuse the existing single-shot capture
     * rather than tapping the walking loop's frame stream. The walking loop is
     * the safety path; a Scene question must not change its timing.
     *
     * `inSampleSize` does the bulk of the downscale inside the JPEG decoder, so
     * a full-size still never lands in memory at full resolution.
     */
    fun fromJpeg(jpeg: ByteArray, longEdge: Int = SceneVlm.IMAGE_LONG_EDGE): Scaled? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longEdge) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null

        val scale = longEdge.toDouble() / maxOf(decoded.width, decoded.height)
        val width = if (scale >= 1.0) decoded.width else maxOf(1, (decoded.width * scale).toInt())
        val height = if (scale >= 1.0) decoded.height else maxOf(1, (decoded.height * scale).toInt())

        val bitmap = if (width == decoded.width && height == decoded.height) decoded
        else Bitmap.createScaledBitmap(decoded, width, height, true)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        if (bitmap !== decoded) bitmap.recycle()
        decoded.recycle()

        val rgb = ByteArray(width * height * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            val o = i * 3
            rgb[o] = ((p shr 16) and 0xFF).toByte()
            rgb[o + 1] = ((p shr 8) and 0xFF).toByte()
            rgb[o + 2] = (p and 0xFF).toByte()
        }
        return Scaled(rgb, width, height)
    }

    class Scaled(val rgb: ByteArray, val width: Int, val height: Int)
}
