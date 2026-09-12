package com.drishti.dashboard.domain

import com.drishti.dashboard.data.HelpRequest
import com.drishti.dashboard.data.MonitoredPerson
import com.drishti.dashboard.data.SafetyEvent
import com.drishti.dashboard.data.SafetyEventKind

/**
 * The four words on the wall.
 *
 * Ordered by how fast someone has to move: the ordinal is the sort key, so
 * adding a state means deciding where it sits in that queue rather than
 * patching a comparator.
 */
enum class LiveStatus {
    HELP_REQUIRED,
    NEEDS_ATTENTION,
    OFFLINE,
    SAFE,
}

/**
 * Derive [LiveStatus] from reported facts.
 *
 * Deliberately pure and deliberately pessimistic. "Safe" is only ever the
 * answer when every reason to worry has been ruled out — silence is never read
 * as safety, which is why [OFFLINE_AFTER_MS] outranks a cheerful last frame.
 *
 * Order matters and is the whole of the policy:
 *  1. an unacknowledged help request beats everything;
 *  2. a phone that stopped reporting is OFFLINE, whatever it last said;
 *  3. an attention-grade event inside [ATTENTION_WINDOW_MS], or a battery that
 *     will not last the walk home, is NEEDS_ATTENTION;
 *  4. otherwise SAFE.
 *
 * An *acknowledged* help request stays HELP_REQUIRED. Acknowledging means
 * somebody is on it, not that it is over; only clearing the request ends it.
 */
fun liveStatusOf(person: MonitoredPerson, nowMs: Long): LiveStatus {
    if (person.helpRequest != null) return LiveStatus.HELP_REQUIRED
    if (nowMs - person.lastUpdateMs >= OFFLINE_AFTER_MS) return LiveStatus.OFFLINE
    if (person.batteryPercent != null && person.batteryPercent <= LOW_BATTERY_PERCENT) {
        return LiveStatus.NEEDS_ATTENTION
    }
    val recentConcern = person.events.any { event ->
        event.kind.needsACheck() && nowMs - event.atMs <= ATTENTION_WINDOW_MS
    }
    return if (recentConcern) LiveStatus.NEEDS_ATTENTION else LiveStatus.SAFE
}

/**
 * Whether this kind of event, on its own and while it is fresh, is a reason to
 * look at someone. Route and session notes are history, not alarms.
 */
fun SafetyEventKind.needsACheck(): Boolean = when (this) {
    SafetyEventKind.SOS_RAISED,
    SafetyEventKind.FALL_SUSPECTED,
    SafetyEventKind.REPEATED_STOP,
    SafetyEventKind.UNSIGNALLED_CROSSING,
    SafetyEventKind.SIGNAL_LOST,
    SafetyEventKind.LOW_BATTERY,
    SafetyEventKind.OBSTACLE_DETECTED,
    -> true

    SafetyEventKind.OFF_USUAL_ROUTE,
    SafetyEventKind.HAZARD_REPORTED,
    SafetyEventKind.WALK_STARTED,
    SafetyEventKind.WALK_ENDED,
    -> false
}

/**
 * The wall order: worst state first, and inside a state the person who has
 * been waiting longest.
 *
 * Longest-waiting first is the point. Sorting help requests newest-first would
 * push the person who has been on the ground for six minutes underneath the
 * one who pressed the button just now.
 */
fun List<MonitoredPerson>.sortedForTheWall(nowMs: Long): List<MonitoredPerson> =
    sortedWith(
        compareBy<MonitoredPerson> { if (it.isLive) 0 else 1 }
            .thenBy { liveStatusOf(it, nowMs).ordinal }
            .thenBy { person ->
                person.helpRequest?.requestedAtMs ?: person.lastUpdateMs
            }
            .thenBy { it.name },
    )

/** Open help requests, oldest first — the queue the alerts screen works down. */
fun List<MonitoredPerson>.openHelpRequests(): List<Pair<MonitoredPerson, HelpRequest>> =
    mapNotNull { person -> person.helpRequest?.let { person to it } }
        .sortedBy { (_, request) -> request.requestedAtMs }

/** Per-person history, most recent first. */
fun List<SafetyEvent>.mostRecentFirst(): List<SafetyEvent> = sortedByDescending { it.atMs }

/** How many people are in each state right now. Drives the summary tiles. */
fun List<MonitoredPerson>.countByStatus(nowMs: Long): Map<LiveStatus, Int> {
    val counts = LiveStatus.entries.associateWith { 0 }.toMutableMap()
    forEach { person -> counts.merge(liveStatusOf(person, nowMs), 1, Int::plus) }
    return counts
}

/**
 * A phone reports every couple of seconds while Walk Mode runs. Ninety seconds
 * of silence is a dead battery, a pocket, or a person who stopped walking
 * without ending the session — all three want a human to notice.
 */
const val OFFLINE_AFTER_MS: Long = 90_000

/** Events older than this are history; the person is no longer flagged for them. */
const val ATTENTION_WINDOW_MS: Long = 5 * 60_000

/** Below this, the walk home is not guaranteed to have an assistant. */
const val LOW_BATTERY_PERCENT: Int = 15
