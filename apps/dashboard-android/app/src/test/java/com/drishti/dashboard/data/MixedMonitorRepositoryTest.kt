package com.drishti.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedMonitorRepositoryTest {
    private val demo = DeskSnapshot(emptyList(), emptyList(), DataSource.DEMO)

    @Test
    fun `a live person changes the source label and stays first`() {
        val live = MonitoredPerson(
            id = "live-arun",
            name = "Arun Kumar",
            phoneNumber = "+91 00000 00000",
            emergencyContactName = "Not provided",
            emergencyContactNumber = "",
            area = "The Hive, OMR, Chennai",
            activity = Activity.WALKING,
            activityDetail = "Path is clear",
            lastUpdateMs = 123,
            batteryPercent = 80,
            helpRequest = null,
            events = emptyList(),
            isLive = true,
        )

        val result = mergeMonitorSnapshot(demo, live)
        assertEquals(DataSource.MIXED, result.source)
        assertEquals("live-arun", result.people.first().id)
        assertTrue(result.people.first().isLive)
    }

    @Test
    fun `without the coordinator the honest sample label remains`() {
        assertEquals(DataSource.DEMO, mergeMonitorSnapshot(demo, null).source)
    }
}
