package com.drishti.app.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the real llama.cpp bridge on the real phone.
 *
 * This is a device test rather than a button in the app on purpose. The
 * inference path is native, it allocates hundreds of megabytes, and a mistake in
 * it takes the whole process down — so it must be provable without asking a
 * person to perform a gesture and hope.
 *
 * It is skipped, not failed, when the GGUF files are not staged: a developer
 * without a 330 MB model on their device should still be able to run the suite.
 * Stage them with:
 *
 *   adb push models/staging/vlm/lfm25-vl-450m-q4km.gguf \
 *     //sdcard/Android/data/com.drishti.app.debug/files/
 *   adb push models/staging/vlm/lfm25-vl-450m-mmproj-q8.gguf \
 *     //sdcard/Android/data/com.drishti.app.debug/files/
 */
@RunWith(AndroidJUnit4::class)
class SceneVlmDeviceTest {

    private fun sign(text: String): ByteArray {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(215, 215, 210))
        canvas.drawRect(40f, 90f, 600f, 390f, Paint().apply { color = Color.rgb(25, 60, 120) })
        canvas.drawText(
            text, 90f, 280f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 96f
                typeface = Typeface.DEFAULT_BOLD
            },
        )
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Test
    fun answersOneQuestionAboutOneImageAndReleasesTheModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vlm = SceneVlm.create(context)
        assumeTrue("Scene VLM models are not staged on this device", vlm != null)

        val image = SceneImage.fromJpeg(sign("EXIT"))
        assertTrue("could not decode the fixture", image != null)
        image!!

        // The measured bound from docs/SCENE_MODE_VLM.md §4.2. If this drifts,
        // latency and text legibility both move with it.
        assertEquals(SceneVlm.IMAGE_LONG_EDGE, maxOf(image.width, image.height))
        assertEquals(image.width * image.height * 3, image.rgb.size)

        val result = vlm!!.ask(
            image.rgb, image.width, image.height,
            "What does the sign say? Answer with the text only.",
        )

        assertTrue(
            "expected an answer, got $result",
            result is SceneVlm.Result.Answer || result is SceneVlm.Result.NotEnoughMemory,
        )
        // A device genuinely short of memory must refuse rather than risk the
        // walking loop, so that outcome is a pass for this test's purpose.
        assumeTrue(
            "device declined on memory pressure",
            result is SceneVlm.Result.Answer,
        )

        val answer = (result as SceneVlm.Result.Answer)
        assertTrue("answer was blank", answer.text.isNotBlank())
        assertTrue(
            "expected the sign text, got '${answer.text}' in ${answer.millis} ms",
            answer.text.uppercase().contains("EXIT"),
        )

        // A second call proves the first one really did release everything.
        // If it leaked, this is where the process dies.
        val again = vlm.ask(
            image.rgb, image.width, image.height,
            "Is there a sign in this image? Answer yes or no.",
        )
        assertTrue("second call failed: $again", again is SceneVlm.Result.Answer)
    }

    /**
     * Cancellation must be real, not a coroutine giving up on a thread that
     * keeps running with the model resident (docs/SCENE_MODE_VLM.md §5). The
     * cancel lands mid-prefill; the call has to come back promptly, as
     * [SceneVlm.Result.Cancelled], and the model has to be gone — proved by a
     * full answer immediately afterwards.
     */
    @Test
    fun cancelMidInferenceReturnsPromptlyAndReleasesTheModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val vlm = SceneVlm.create(context)
        assumeTrue("Scene VLM models are not staged on this device", vlm != null)
        val image = SceneImage.fromJpeg(sign("STOP"))!!

        var result: SceneVlm.Result? = null
        val worker = Thread {
            result = vlm!!.ask(image.rgb, image.width, image.height, "Describe the scene in detail.")
        }
        worker.start()
        // Load takes ~200 ms and the image chunk ~900 ms; land inside prefill.
        Thread.sleep(500)
        val cancelledAt = System.nanoTime()
        vlm!!.cancel()
        worker.join(5_000)
        val returnedInMs = (System.nanoTime() - cancelledAt) / 1_000_000

        assertTrue("native call did not return after cancel", !worker.isAlive)
        assertTrue("expected Cancelled, got $result", result is SceneVlm.Result.Cancelled)
        assertTrue("cancel took $returnedInMs ms", returnedInMs < 2_000)

        val after = vlm.ask(image.rgb, image.width, image.height, "What does the sign say?")
        assertTrue("call after cancel failed: $after", after is SceneVlm.Result.Answer)
    }
}
