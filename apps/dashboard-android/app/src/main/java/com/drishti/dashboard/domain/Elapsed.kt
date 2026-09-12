package com.drishti.dashboard.domain

import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Elapsed time in the words a coordinator would use out loud.
 *
 * Everything on this dashboard is relative ("4 min ago"), never absolute
 * ("14:06"), because the only question being asked is *how stale is this*.
 * Clock times force the reader to do the subtraction themselves, and they do it
 * wrong under pressure.
 */
object Elapsed {

    /** "just now", "12 sec", "4 min", "1 hr 20 min", "2 days". */
    fun span(millis: Long): String {
        val ms = millis.coerceAtLeast(0)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms)
        if (seconds < 5) return "just now"
        if (seconds < 60) return "$seconds sec"
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        if (minutes < 60) return "$minutes min"
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        if (hours < 24) {
            val restMinutes = minutes - hours * 60
            return if (restMinutes == 0L) "$hours hr" else "$hours hr $restMinutes min"
        }
        val days = TimeUnit.MILLISECONDS.toDays(ms)
        return if (days == 1L) "1 day" else "$days days"
    }

    /** "just now" or "4 min ago". */
    fun ago(sinceMs: Long, nowMs: Long): String {
        val text = span(nowMs - sinceMs)
        return if (text == "just now") text else "$text ago"
    }

    /** "waiting 6 min" — for a help request nobody has picked up yet. */
    fun waiting(sinceMs: Long, nowMs: Long): String = "waiting ${span(nowMs - sinceMs)}"

    /** Six decimal places is roughly a tenth of a metre; more is false precision. */
    fun coordinates(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
}
