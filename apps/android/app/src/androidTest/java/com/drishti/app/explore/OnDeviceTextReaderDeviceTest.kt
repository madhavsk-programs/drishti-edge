package com.drishti.app.explore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.drishti.app.feedback.SpokenLanguage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceTextReaderDeviceTest {

    @Test
    fun bundledRecognizerReadsRouteSignWithoutAService() = runBlocking {
        val bitmap = Bitmap.createBitmap(1_400, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 220f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("BUS 42A", 70f, 330f, paint)
        val jpeg = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val result = OnDeviceTextReader().read(jpeg)

        assertTrue("recognized text was '${result.text}'", result.text.uppercase().contains("BUS"))
        assertTrue("routes were ${result.routeNumbers}", "42A" in result.routeNumbers)
    }

    @Test
    fun bundledHindiRecognizerReadsDevanagariWithoutAService() = runBlocking {
        val bitmap = Bitmap.createBitmap(1_400, 500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 220f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("बस ४२", 70f, 330f, paint)
        val jpeg = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output)
            output.toByteArray()
        }
        bitmap.recycle()

        val result = OnDeviceTextReader().read(jpeg, SpokenLanguage.HINDI)

        assertTrue("recognized text was '${result.text}'", result.text.contains("बस"))
        assertTrue("routes were ${result.routeNumbers}", "42" in result.routeNumbers)
        assertTrue("language was ${result.language}", result.language == "hi-IN")
    }
}
