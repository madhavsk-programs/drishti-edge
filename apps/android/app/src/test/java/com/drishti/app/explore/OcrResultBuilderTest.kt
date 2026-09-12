package com.drishti.app.explore

import com.drishti.app.net.OcrConfidenceQualification
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrResultBuilderTest {

    @Test
    fun `route tokens match the original backend and stay ordered unique`() {
        assertEquals(
            listOf("42A", "17"),
            extractRouteNumbers("Bus 42A to Central. Route 17, then 42a."),
        )
    }

    @Test
    fun `Devanagari digits are normalized for route extraction`() {
        assertEquals(listOf("42", "17A"), extractRouteNumbers("बस ४२ और १७A"))
    }

    @Test
    fun `high confidence text is normalized without inventing content`() {
        val result = buildReadTextResponse(
            rawText = "  BUS  42A\nCENTRAL ",
            confidenceSamples = listOf(
                OcrConfidenceSample(3, 0.9),
                OcrConfidenceSample(3, 0.8),
                OcrConfidenceSample(7, 0.7),
            ),
            decodeMs = 2.0,
            ocrMs = 20.0,
            now = Instant.EPOCH,
        )

        assertEquals("BUS 42A CENTRAL", result.text)
        assertEquals(listOf("42A"), result.routeNumbers)
        assertEquals(OcrConfidenceQualification.HIGH, result.confidenceQualification)
        assertEquals(result.text, result.message)
        assertFalse(result.noTextFound)
        assertEquals(22.0, result.timings.totalMs, 0.0)
        assertEquals("1970-01-01T00:00:00Z", result.serverTime)
        assertEquals("en", result.language)
    }

    @Test
    fun `selected response language is carried in the result`() {
        val result = buildReadTextResponse(
            rawText = "बस ४२",
            confidenceSamples = listOf(OcrConfidenceSample(5, 0.9)),
            decodeMs = 1.0,
            ocrMs = 2.0,
            language = "hi-IN",
        )

        assertEquals("hi-IN", result.language)
        assertEquals(listOf("42"), result.routeNumbers)
    }

    @Test
    fun `weighted low confidence is announced honestly`() {
        val result = buildReadTextResponse(
            rawText = "BUS 17",
            confidenceSamples = listOf(
                OcrConfidenceSample(3, 0.9),
                OcrConfidenceSample(2, 0.2),
            ),
            decodeMs = 0.0,
            ocrMs = 1.0,
        )

        assertEquals(0.62, result.confidence, 0.00001)
        assertEquals(OcrConfidenceQualification.LOW, result.confidenceQualification)
        assertTrue(result.message.startsWith("Possible text:"))
    }

    @Test
    fun `text without model confidence is low rather than falsely certain`() {
        val result = buildReadTextResponse("EXIT", emptyList(), 0.0, 1.0)

        assertEquals(0.0, result.confidence, 0.0)
        assertEquals(OcrConfidenceQualification.LOW, result.confidenceQualification)
    }

    @Test
    fun `blank recognition produces none`() {
        val result = buildReadTextResponse(" \n ", emptyList(), 1.0, 5.0)

        assertEquals("", result.text)
        assertEquals(emptyList<String>(), result.routeNumbers)
        assertEquals(OcrConfidenceQualification.NONE, result.confidenceQualification)
        assertEquals("No text found.", result.message)
        assertTrue(result.noTextFound)
    }
}
