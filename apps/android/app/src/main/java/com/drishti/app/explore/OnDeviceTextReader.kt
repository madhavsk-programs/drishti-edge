package com.drishti.app.explore

import android.graphics.BitmapFactory
import android.os.SystemClock
import com.drishti.app.feedback.SpokenLanguage
import com.drishti.app.net.ReadTextResponse
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** One-shot, bundled ML Kit OCR. No image or recognition result leaves the device. */
class OnDeviceTextReader {

    suspend fun read(
        jpeg: ByteArray,
        language: SpokenLanguage = SpokenLanguage.ENGLISH,
    ): ReadTextResponse {
        val decodeStarted = SystemClock.elapsedRealtimeNanos()
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: throw IllegalArgumentException("Captured image is not a decodable JPEG")
        val decodeMs = elapsedMs(decodeStarted)
        // The Devanagari model also recognizes Latin, so Hindi mode can read a
        // mixed Hindi/English road sign without running two recognizers.
        val recognizer = when (language) {
            SpokenLanguage.HINDI -> TextRecognition.getClient(
                DevanagariTextRecognizerOptions.Builder().build(),
            )
            SpokenLanguage.ENGLISH, SpokenLanguage.TAMIL ->
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        try {
            val ocrStarted = SystemClock.elapsedRealtimeNanos()
            val result = recognizer.processAwait(InputImage.fromBitmap(bitmap, 0))
            val ocrMs = elapsedMs(ocrStarted)
            val samples = result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .map { element ->
                    OcrConfidenceSample(element.text.length, element.confidence.toDouble())
                }
            return buildReadTextResponse(
                rawText = result.text,
                confidenceSamples = samples,
                decodeMs = decodeMs,
                ocrMs = ocrMs,
                language = language.tag,
            )
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun elapsedMs(startedNanos: Long): Double =
        (SystemClock.elapsedRealtimeNanos() - startedNanos) / 1_000_000.0
}

private suspend fun TextRecognizer.processAwait(image: InputImage): Text =
    suspendCancellableCoroutine { continuation ->
        process(image)
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result)
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) continuation.resumeWithException(error)
            }
            .addOnCanceledListener { continuation.cancel() }
    }
