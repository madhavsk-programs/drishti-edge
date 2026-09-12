package com.drishti.dashboard.domain

import com.drishti.dashboard.data.AggregateHazard
import com.drishti.dashboard.data.HazardCategory
import com.drishti.dashboard.data.HazardSeverity
import com.drishti.dashboard.data.HazardStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HazardsTest {

    private val now = 1_700_000_000_000L

    private fun hazard(
        id: String,
        reporters: Int = 1,
        reports: Int = reporters,
        severity: HazardSeverity = HazardSeverity.MEDIUM,
        status: HazardStatus = HazardStatus.NEW,
        lastReportedMs: Long = now - 60_000,
    ) = AggregateHazard(
        id = id,
        category = HazardCategory.BROKEN_SURFACE,
        locationLabel = "Somewhere",
        severity = severity,
        reportCount = reports,
        reporterCount = reporters,
        firstReportedMs = now - 3_600_000,
        lastReportedMs = lastReportedMs,
        status = status,
        assignedTo = null,
        note = "note",
    )

    @Test
    fun `corroboration counts people not reports`() {
        assertFalse(
            "one person reporting five times is still one person",
            hazard("a", reporters = 1, reports = 5).isCorroborated(),
        )
        assertTrue(hazard("b", reporters = 2, reports = 2).isCorroborated())
    }

    @Test
    fun `works order puts confirmed hazards above unconfirmed ones whatever the severity`() {
        val confirmedButMild = hazard("confirmed", reporters = 3, severity = HazardSeverity.LOW)
        val loneButSevere = hazard("lone", reporters = 1, severity = HazardSeverity.HIGH)

        assertEquals(
            listOf("confirmed", "lone"),
            listOf(loneButSevere, confirmedButMild).sortedForWorks().map { it.id },
        )
    }

    @Test
    fun `severity breaks ties inside the confirmed group`() {
        val high = hazard("high", reporters = 2, severity = HazardSeverity.HIGH)
        val low = hazard("low", reporters = 2, severity = HazardSeverity.LOW)
        val medium = hazard("medium", reporters = 2, severity = HazardSeverity.MEDIUM)

        assertEquals(
            listOf("high", "medium", "low"),
            listOf(low, medium, high).sortedForWorks().map { it.id },
        )
    }

    @Test
    fun `resolved hazards sort last but are not dropped`() {
        val fixed = hazard("fixed", reporters = 9, severity = HazardSeverity.HIGH, status = HazardStatus.RESOLVED)
        val open = hazard("open", reporters = 1, severity = HazardSeverity.LOW)

        val order = listOf(fixed, open).sortedForWorks().map { it.id }
        assertEquals(listOf("open", "fixed"), order)
    }

    @Test
    fun `the tally counts only open hazards except for the fixed column`() {
        val hazards = listOf(
            hazard("a", reporters = 4),
            hazard("b", reporters = 2),
            hazard("c", reporters = 1),
            hazard("d", reporters = 3, status = HazardStatus.RESOLVED),
            hazard("e", reporters = 5, status = HazardStatus.ASSIGNED),
        )

        val tally = hazards.tally()
        assertEquals(3, tally.corroborated)
        assertEquals(1, tally.singleReport)
        assertEquals(1, tally.resolved)
        // 4 + 2 + 1 + 5, with the resolved one left out.
        assertEquals(12, tally.peopleAffected)
    }
}
