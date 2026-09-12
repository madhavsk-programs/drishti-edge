package com.drishti.dashboard.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ElapsedTest {

    @Test
    fun `spans read the way a person would say them`() {
        assertEquals("just now", Elapsed.span(0))
        assertEquals("just now", Elapsed.span(4_999))
        assertEquals("5 sec", Elapsed.span(5_000))
        assertEquals("59 sec", Elapsed.span(59_999))
        assertEquals("1 min", Elapsed.span(60_000))
        assertEquals("59 min", Elapsed.span(59 * 60_000L))
        assertEquals("1 hr", Elapsed.span(60 * 60_000L))
        assertEquals("1 hr 20 min", Elapsed.span(80 * 60_000L))
        assertEquals("2 hr", Elapsed.span(120 * 60_000L))
        assertEquals("1 day", Elapsed.span(24 * 3_600_000L))
        assertEquals("3 days", Elapsed.span(76 * 3_600_000L))
    }

    @Test
    fun `a clock that has run backwards reads as just now rather than a negative`() {
        // Device clocks disagree. Showing "-4 sec ago" would make the reader
        // distrust every other number on the screen.
        assertEquals("just now", Elapsed.span(-10_000))
        assertEquals("just now", Elapsed.ago(sinceMs = 1_000, nowMs = 0))
    }

    @Test
    fun `ago appends only when there is a span to append it to`() {
        assertEquals("just now", Elapsed.ago(sinceMs = 100, nowMs = 1_100))
        assertEquals("12 sec ago", Elapsed.ago(sinceMs = 0, nowMs = 12_000))
    }

    @Test
    fun `waiting always names the span`() {
        assertEquals("waiting just now", Elapsed.waiting(sinceMs = 0, nowMs = 1_000))
        assertEquals("waiting 6 min", Elapsed.waiting(sinceMs = 0, nowMs = 6 * 60_000L))
    }

    @Test
    fun `coordinates stop at a tenth of a metre`() {
        assertEquals("13.060402, 80.242137", Elapsed.coordinates(13.0604019, 80.2421374))
        // Locale-independent: a device set to a comma-decimal locale must not
        // turn a coordinate pair into four numbers.
        assertEquals("-0.000001, 0.000000", Elapsed.coordinates(-0.0000009, 0.0))
    }
}
