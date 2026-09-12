package com.drishti.dashboard.data

import com.drishti.dashboard.domain.LiveStatus
import com.drishti.dashboard.domain.liveStatusOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The demo repository is sample data, but the transitions it performs are the
 * ones a real one will perform, so they are worth pinning down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoMonitorRepositoryTest {

    private val fixedNow = 1_700_000_000_000L

    // backgroundScope, not the test scope: the repository's ticker runs
    // forever by design, and only backgroundScope cancels it when the test
    // body returns instead of hanging waiting for it.
    private fun repository(scope: CoroutineScope) =
        DemoMonitorRepository(scope = scope, clock = { fixedNow })

    private fun DeskSnapshot.person(id: String) = people.first { it.id == id }

    @Test
    fun `the sample programme opens with every state represented`() = runTest {
        val snapshot = repository(backgroundScope).snapshot.value
        val statuses = snapshot.people.map { liveStatusOf(it, fixedNow) }.toSet()

        assertEquals(
            "a wall that has only ever been seen all-green has not been seen",
            LiveStatus.entries.toSet(),
            statuses,
        )
        assertEquals(DataSource.DEMO, snapshot.source)
    }

    @Test
    fun `acknowledging records who took it and does not close the request`() = runTest {
        val repo = repository(backgroundScope)
        repo.acknowledgeHelp("p-arun", "Priya R.")

        val request = repo.snapshot.value.person("p-arun").helpRequest
        assertNotNull(request)
        assertEquals("Priya R.", request!!.acknowledgedBy)
        assertEquals(fixedNow, request.acknowledgedAtMs)
        assertEquals(
            LiveStatus.HELP_REQUIRED,
            liveStatusOf(repo.snapshot.value.person("p-arun"), fixedNow),
        )
    }

    @Test
    fun `a second acknowledgement does not steal the first operator's name`() = runTest {
        val repo = repository(backgroundScope)
        repo.acknowledgeHelp("p-arun", "Priya R.")
        repo.acknowledgeHelp("p-arun", "Somebody Else")

        assertEquals("Priya R.", repo.snapshot.value.person("p-arun").helpRequest?.acknowledgedBy)
    }

    @Test
    fun `marking safe ends the request and the person stops being red`() = runTest {
        val repo = repository(backgroundScope)
        repo.clearHelp("p-arun")

        val person = repo.snapshot.value.person("p-arun")
        assertNull(person.helpRequest)
        assertTrue(liveStatusOf(person, fixedNow) != LiveStatus.HELP_REQUIRED)
    }

    @Test
    fun `a desk check is marked as opened by the desk, not by the person`() = runTest {
        val repo = repository(backgroundScope)
        repo.raiseDeskCheck("p-deepak", "Night desk")

        val request = repo.snapshot.value.person("p-deepak").helpRequest
        assertNotNull(request)
        assertEquals(HelpKind.NO_RESPONSE, request!!.kind)
        assertEquals("Night desk", request.acknowledgedBy)
    }

    @Test
    fun `a desk check never overwrites a request the person raised themselves`() = runTest {
        val repo = repository(backgroundScope)
        repo.raiseDeskCheck("p-arun", "Night desk")

        assertEquals(HelpKind.SOS_HELD, repo.snapshot.value.person("p-arun").helpRequest?.kind)
    }

    @Test
    fun `assigning a hazard records the team and resolving clears it`() = runTest {
        val repo = repository(backgroundScope)
        repo.setHazardStatus("h-1", HazardStatus.ASSIGNED, "Corporation Zone 13")

        var hazard = repo.snapshot.value.hazards.first { it.id == "h-1" }
        assertEquals(HazardStatus.ASSIGNED, hazard.status)
        assertEquals("Corporation Zone 13", hazard.assignedTo)

        repo.setHazardStatus("h-1", HazardStatus.RESOLVED)
        hazard = repo.snapshot.value.hazards.first { it.id == "h-1" }
        assertEquals(HazardStatus.RESOLVED, hazard.status)
        assertNull("a fixed hazard is nobody's open work", hazard.assignedTo)
    }
}
