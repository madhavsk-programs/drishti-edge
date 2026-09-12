package com.drishti.app.walk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.drishti.app.BuildConfig
import com.drishti.app.inference.OrientedFrame
import java.io.File

/**
 * Writes the EXACT frames the detector sees to disk, for offline evaluation
 * with `tools/pipeline_eval.py`.
 *
 * Screenshots are not a substitute. The preview is a `FILL_CENTER` crop of the
 * analysis frame with the app's own overlay painted on top, so running a model
 * over one measures the overlay as much as the scene, and the field of view is
 * not the field of view the pipeline reasoned about. Tuning a detector
 * threshold against that is measuring the wrong image.
 *
 * Off unless someone deliberately turns it on, and only in a debug build:
 *
 *     adb shell touch /sdcard/Android/data/com.drishti.app.debug/files/capture-frames
 *     # walk around, then
 *     adb pull /sdcard/Android/data/com.drishti.app.debug/files/frames ./frames
 *     adb shell rm /sdcard/Android/data/com.drishti.app.debug/files/capture-frames
 *
 * The sentinel is re-read every [SENTINEL_POLL_MS] so it can be created and
 * removed mid-session without restarting Walk Mode.
 */
class FrameRecorder(context: Context) {

    private val filesDir: File? = context.getExternalFilesDir(null)
    private val sentinel = filesDir?.let { File(it, SENTINEL) }
    private val outputDir = filesDir?.let { File(it, "frames") }

    private var enabled = false
    private var lastSentinelCheckMs = 0L
    private var written = 0
    private var reusableRow: IntArray = IntArray(0)

    /** Call once per analysed frame; cheap and silent when recording is off. */
    fun record(frame: OrientedFrame) {
        val flag = sentinel
        val dir = outputDir
        if (!BuildConfig.DEBUG || flag == null || dir == null) return
        val now = System.currentTimeMillis()
        if (now - lastSentinelCheckMs >= SENTINEL_POLL_MS) {
            lastSentinelCheckMs = now
            val present = flag.exists()
            if (present != enabled) {
                enabled = present
                written = 0
                Log.i(TAG, if (present) "frame capture ON -> $dir" else "frame capture OFF")
            }
        }
        if (!enabled || written >= MAX_FRAMES) return
        if (frame.frameId % EVERY_NTH != 0) return

        runCatching {
            dir.mkdirs()
            val file = File(dir, "frame-%05d-%d.jpg".format(frame.frameId, frame.capturedAtMillis))
            toBitmap(frame).use { bitmap ->
                file.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
            }
            written++
            if (written == MAX_FRAMES) Log.i(TAG, "frame capture reached $MAX_FRAMES; stopping")
        }.onFailure { Log.w(TAG, "could not write frame ${frame.frameId}", it) }
    }

    /** RGB888 -> ARGB_8888, one row at a time so no full int buffer is held. */
    private fun toBitmap(frame: OrientedFrame): Bitmap {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        if (reusableRow.size != frame.width) reusableRow = IntArray(frame.width)
        val row = reusableRow
        val rgb = frame.rgb
        for (y in 0 until frame.height) {
            var index = y * frame.width * 3
            for (x in 0 until frame.width) {
                row[x] = Color.rgb(
                    rgb[index].toInt() and 0xFF,
                    rgb[index + 1].toInt() and 0xFF,
                    rgb[index + 2].toInt() and 0xFF,
                )
                index += 3
            }
            bitmap.setPixels(row, 0, frame.width, 0, y, frame.width, 1)
        }
        return bitmap
    }

    private inline fun <R> Bitmap.use(block: (Bitmap) -> R): R =
        try { block(this) } finally { recycle() }

    private companion object {
        const val TAG = "FrameRecorder"
        const val SENTINEL = "capture-frames"
        const val SENTINEL_POLL_MS = 1_000L
        /** Every 5th frame: enough variety to be representative, few enough to pull. */
        const val EVERY_NTH = 5
        const val MAX_FRAMES = 300
        const val JPEG_QUALITY = 92
    }
}
