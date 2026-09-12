package com.drishti.dashboard.domain

import com.drishti.dashboard.data.Activity
import com.drishti.dashboard.data.HelpKind
import com.drishti.dashboard.data.HelpRequest
import com.drishti.dashboard.data.MonitoredPerson
import com.drishti.dashboard.data.SafetyEvent
import com.drishti.dashboard.data.SafetyEventKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The status rules are the only place this app makes a judgement, so they are
 * the place worth testing. Everything else is layout.
 */
class StatusTest {

    private val now = 1_700_000_000_000L

    private fun person(
        id: String = "p1",
        name: String = "Test Person",
        lastUpdateMs: Long = now - 2_000,
        battery: Int? = 80,
        help: HelpRequest? = null,
        events: List<SafetyEvent> = emptyList(),
    ) = MonitoredPerson(
        id = id,
        name = name,
        phoneNumber = "+91 5550 000 000",
        emergencyContactName = "Contact",
        emergencyContactNumber = "+91 5550 000 001",
        area = "Area",
        activity = Activity.WALKING,
        activityDetail = "Walking",
        lastUpdateMs = lastUpdateMs,
        batteryPercent = battery,
        helpRequest = help,
        events = events,
    )

    private fun event(kind: SafetyEventKind, agoMs: Long) = SafetyEvent(
        id = "e-${kind.name}-$agoMs",
        kind = kind,
        atMs = now - agoMs,
        detail = "detail",
        locationLabel = null,
    )

    private fun help(agoMs: Long, acknowledgedBy: String? = null) = HelpRequest(
        kind = HelpKind.SOS_HELD,
        requestedAtMs = now - agoMs,
        locationLabel = "Somewhere",
        latitude = 13.0,
        longitude = 80.0,
        acknowledgedBy = acknowledgedBy,
        acknowledgedAtMs = acknowledgedBy?.let { now - agoMs / 2 },
    )

    @Test
    fun `a reporting phone with nothing wrong is safe`() {
        assertEquals(LiveStatus.SAFE, liveStatusOf(person(), now))
    }

    @Test
    fun `an unacknowledged help request outranks everything`() {
        val subject = person(
            lastUpdateMs = now - 10 * 60_000,
            battery = 4,
            help = help(agoMs = 60_000),
        )
        assertEquals(LiveStatus.HELP_REQUIRED, liveStatusOf(subject, now))
    }

    @Test
    fun `acknowledging does not end a help request`() {
        val subject = person(help = help(agoMs = 60_000, acknowledgedBy = "Priya"))
        assertEquals(
            "somebody being on it is not the same as it being over",
            LiveStatus.HELP_REQUIRED,
            liveStatusOf(subject, now),
        )
    }

    @Test
    fun `silence is not safety`() {
        // One millisecond short of the threshold is still live; at it, offline.
        val nearlyQuiet = person(lastUpdateMs = now - OFFLINE_AFTER_MS + 1)
        assertEquals(LiveStatus.SAFE, liveStatusOf(nearlyQuiet, now))
        val quiet = person(lastUpdateMs = now - OFFLINE_AFTER_MS)
        assertEquals(LiveStatus.OFFLINE, liveStatusOf(quiet, now))
    }

    @Test
    fun `offline beats a stale concern but never a help request`() {
        val subject = person(
            lastUpdateMs = now - 5 * 60_000,
            events = listOf(event(SafetyEventKind.REPEATED_STOP, 10_000)),
        )
        assertEquals(LiveStatus.OFFLINE, liveStatusOf(subject, now))
    }

    @Test
    fun `a fresh concern needs a check and a stale one does not`() {
        val fresh = person(events = listOf(event(SafetyEventKind.REPEATED_STOP, 60_000)))
        assertEquals(LiveStatus.NEEDS_ATTENTION, liveStatusOf(fresh, now))

        val stale = person(events = listOf(event(SafetyEventKind.REPEATED_STOP, ATTENTION_WINDOW_MS + 1)))
        assertEquals(LiveStatus.SAFE, liveStatusOf(stale, now))
    }

    @Test
    fun `session notes are history not alarms`() {
        val subject = person(
            events = listOf(
                event(SafetyEventKind.WALK_STARTED, 1_000),
                event(SafetyEventKind.HAZARD_REPORTED, 1_000),
                event(SafetyEventKind.OFF_USUAL_ROUTE, 1_000),
            ),
        )
        assertEquals(LiveStatus.SAFE, liveStatusOf(subject, now))
    }

    @Test
    fun `a battery that will not last the walk home needs a check`() {
        assertEquals(LiveStatus.NEEDS_ATTENTION, liveStatusOf(person(battery = LOW_BATTERY_PERCENT), now))
        assertEquals(LiveStatus.SAFE, liveStatusOf(person(battery = LOW_BATTERY_PERCENT + 1), now))
        assertEquals(
            "a phone that does not report battery is not thereby in trouble",
            LiveStatus.SAFE,
            liveStatusOf(person(battery = null), now),
        )
    }

    @Test
    fun `the wall puts the longest wait first inside each state`() {
        val waitingLonger = person(id = "old", name = "Older Wait", help = help(agoMs = 6 * 60_000))
        val waitingBriefly = person(id = "new", name = "Newer Wait", help = help(agoMs = 20_000))
        val needsCheck = person(id = "check", name = "Check Me", events = listOf(event(SafetyEventKind.REPEATED_STOP, 10_000)))
        val quiet = person(id = "quiet", name = "Quiet", lastUpdateMs = now - 10 * 60_000)
        val fine = person(id = "fine", name = "Fine")

        val order = listOf(fine, quiet, needsCheck, waitingBriefly, waitingLonger)
            .sortedForTheWall(now)
            .map { it.id }

        assertEquals(listOf("old", "new", "check", "quiet", "fine"), order)
    }

    @Test
    fun `counts cover every state including the empty ones`() {
        val counts = listOf(
            person(id = "a", help = help(agoMs = 1_000)),
            person(id = "b", lastUpdateMs = now - 10 * 60_000),
            person(id = "c"),
            person(id = "d"),
        ).countByStatus(now)

        assertEquals(1, counts[LiveStatus.HELP_REQUIRED])
        assertEquals(0, counts[LiveStatus.NEEDS_ATTENTION])
        assertEquals(1, counts[LiveStatus.OFFLINE])
        assertEquals(2, counts[LiveStatus.SAFE])
    }

    @Test
    fun `the alert queue is ordered oldest first`() {
        val people = listOf(
            person(id = "b", help = help(agoMs = 30_000)),
            person(id = "a", help = help(agoMs = 5 * 60_000)),
            person(id = "c"),
        )
        assertEquals(listOf("a", "b"), people.openHelpRequests().map { it.first.id })
    }
}
