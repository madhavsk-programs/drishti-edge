package com.drishti.dashboard.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens are allowed to know about where data comes from.
 *
 * [DemoMonitorRepository] owns hardcoded programme data and
 * [MixedMonitorRepository] adds the one real walking phone without changing a
 * screen — the reason this interface existed before the feed did.
 *
 * Writes are fire-and-forget and update the flow. A networked implementation
 * will need failure back, so when that lands these return a result rather than
 * Unit; leaving that generality in now would be a guess at its shape.
 */
interface MonitorRepository {

    val snapshot: StateFlow<DeskSnapshot>

    /** Somebody at the desk has taken this request. Does not close it. */
    fun acknowledgeHelp(personId: String, operatorName: String)

    /** The person is safe and the request is over. */
    fun clearHelp(personId: String)

    /** Open a request the desk raised themselves, e.g. after a silent phone. */
    fun raiseDeskCheck(personId: String, operatorName: String)

    fun setHazardStatus(hazardId: String, status: HazardStatus, assignedTo: String? = null)
}
