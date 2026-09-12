package com.drishti.app.explore

import com.drishti.app.net.ExploreTimings
import com.drishti.app.net.OcrConfidenceQualification
import com.drishti.app.net.ReadTextResponse
import java.time.Instant

private const val OCR_CONFIDENCE_THRESHOLD = 0.65
private val ROUTE_TOKEN = Regex("(?<![A-Z0-9])[A-Z]{0,2}\\d{1,4}[A-Z]?(?![A-Z0-9])", RegexOption.IGNORE_CASE)

internal data class OcrConfidenceSample(
    val characterCount: Int,
    val confidence: Double,
)

internal fun extractRouteNumbers(text: String): List<String> {
    val seen = linkedSetOf<String>()
    // Normalize Unicode decimal digits (including Devanagari ०–९) solely for
    // route parsing. The displayed and spoken OCR text remains exactly as read.
    val routeText = buildString(text.length) {
        text.forEach { ch ->
            val digit = Character.digit(ch, 10)
            append(if (digit >= 0) ('0'.code + digit).toChar() else ch)
        }
    }
    ROUTE_TOKEN.findAll(routeText.uppercase()).forEach { seen += it.value }
    return seen.toList()
}

internal fun buildReadTextResponse(
    rawText: String,
    confidenceSamples: List<OcrConfidenceSample>,
    decodeMs: Double,
    ocrMs: Double,
    language: String = "en",
    now: Instant = Instant.now(),
): ReadTextResponse {
    val text = rawText.trim().split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
    val weightedSamples = confidenceSamples.filter { it.characterCount > 0 && it.confidence.isFinite() }
    val confidence = if (text.isEmpty() || weightedSamples.isEmpty()) {
        0.0
    } else {
        val weight = weightedSamples.sumOf { it.characterCount }
        weightedSamples.sumOf { it.confidence.coerceIn(0.0, 1.0) * it.characterCount } / weight
    }
    val qualification = when {
        text.isEmpty() -> OcrConfidenceQualification.NONE
        confidence < OCR_CONFIDENCE_THRESHOLD -> OcrConfidenceQualification.LOW
        else -> OcrConfidenceQualification.HIGH
    }
    val message = when (qualification) {
        OcrConfidenceQualification.NONE -> "No text found."
        OcrConfidenceQualification.LOW -> "Possible text: $text"
        OcrConfidenceQualification.HIGH -> text
    }
    return ReadTextResponse(
        schemaVersion = "1.0.0",
        serverTime = now.toString(),
        mode = "READ_TEXT",
        language = language,
        text = text,
        routeNumbers = extractRouteNumbers(text),
        confidence = confidence,
        confidenceQualification = qualification,
        message = message,
        noTextFound = text.isEmpty(),
        timings = ExploreTimings(
            decodeMs = decodeMs,
            ocrMs = ocrMs,
            totalMs = decodeMs + ocrMs,
        ),
    )
}
