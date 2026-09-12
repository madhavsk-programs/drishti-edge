package com.drishti.dashboard.domain

import com.drishti.dashboard.data.AggregateHazard
import com.drishti.dashboard.data.HazardStatus

/**
 * A hazard is *corroborated* once a second person has independently walked into
 * it.
 *
 * This is the line the whole hazards screen is built around. One report is a
 * single person's reading of a kerb in bad light and may be a false positive
 * from the detector; two people at the same place is a work order. Sending a
 * council a list that mixes the two teaches them to ignore the list.
 */
fun AggregateHazard.isCorroborated(): Boolean = reporterCount >= CORROBORATION_THRESHOLD

/**
 * Works-first order: open before closed, corroborated before single reports,
 * then severity, then how many people it caught.
 *
 * Resolved hazards sort last rather than disappearing — somebody has to be able
 * to check that the thing they reported last week actually got fixed.
 */
fun List<AggregateHazard>.sortedForWorks(): List<AggregateHazard> =
    sortedWith(
        compareBy<AggregateHazard> { if (it.status == HazardStatus.RESOLVED) 1 else 0 }
            .thenBy { if (it.isCorroborated()) 0 else 1 }
            .thenByDescending { it.severity.ordinal }
            .thenByDescending { it.reporterCount }
            .thenByDescending { it.lastReportedMs },
    )

/** The counts across the top of the hazards screen. */
data class HazardTally(
    val corroborated: Int,
    val singleReport: Int,
    val resolved: Int,
    val peopleAffected: Int,
)

/**
 * [peopleAffected] sums reporters across *open* hazards. It double-counts a
 * person who reported two different hazards, and that is the intended reading:
 * the tile says "reports from people", not "distinct people", because this
 * feed carries no identity that would let it say otherwise.
 */
fun List<AggregateHazard>.tally(): HazardTally {
    val open = filter { it.status != HazardStatus.RESOLVED }
    return HazardTally(
        corroborated = open.count { it.isCorroborated() },
        singleReport = open.count { !it.isCorroborated() },
        resolved = count { it.status == HazardStatus.RESOLVED },
        peopleAffected = open.sumOf { it.reporterCount },
    )
}

/** Two independent people. Not two reports — one person can trip twice. */
const val CORROBORATION_THRESHOLD: Int = 2
