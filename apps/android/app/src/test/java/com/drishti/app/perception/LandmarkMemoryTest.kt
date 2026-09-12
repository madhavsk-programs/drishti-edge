package com.drishti.app.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BUILD_PLAN.md A5 acceptance: TTL, entry cap, sighting count, label matching. */
class LandmarkMemoryTest {

    private fun det(label: String, conf: Double = 0.9, x1: Double = 0.4, x2: Double = 0.6) =
        DetectionCandidate(label, conf, x1, 0.3, x2, 0.7)

    @Test
    fun `a landmark needs two sightings before it resolves`() {
        val memory = LandmarkMemory()
        memory.observe(1_000, null, listOf(det("bottle")))
        assertNull("one sighting must not be a destination", memory.resolve("bottle", 1_100))
        memory.observe(1_200, null, listOf(det("bottle")))
        val hit = memory.resolve("bottle", 1_300)
        assertNotNull(hit)
        assertEquals(2, hit!!.sightings)
        assertEquals(1_000, hit.firstSeenMs)
        assertEquals(1_200, hit.lastSeenMs)
    }

    @Test
    fun `entries expire after the ttl`() {
        val memory = LandmarkMemory(ttlSeconds = 45)
        memory.observe(0, null, listOf(det("cup")))
        memory.observe(500, null, listOf(det("cup")))
        assertNotNull(memory.resolve("cup", 45_000))
        assertNull(memory.resolve("cup", 45_501))
        assertEquals(0, memory.count(45_501))
    }

    @Test
    fun `the entry cap keeps the most recent`() {
        val memory = LandmarkMemory(maxEntries = 3, minSightings = 1)
        val labels = listOf("cup", "bottle", "book", "laptop", "clock")
        labels.forEachIndexed { i, label -> memory.observe(1_000L * (i + 1), null, listOf(det(label))) }
        assertEquals(3, memory.count(6_000))
        assertNull(memory.resolve("cup", 6_000))
        assertNull(memory.resolve("bottle", 6_000))
        assertNotNull(memory.resolve("clock", 6_000))
    }

    @Test
    fun `person is never remembered and low confidence is dropped`() {
        val memory = LandmarkMemory(minSightings = 1, minConfidence = 0.45)
        memory.observe(0, null, listOf(det("person"), det("bottle", conf = 0.3)))
        assertEquals(0, memory.count(0))
        assertNull(memory.resolve("person", 0))
    }

    @Test
    fun `the same object seen at the same bearing is one landmark`() {
        val memory = LandmarkMemory(minSightings = 1)
        memory.observe(0, headingDegrees = 90.0, listOf(det("chair", x1 = 0.45, x2 = 0.55)))
        memory.observe(100, headingDegrees = 92.0, listOf(det("chair", x1 = 0.44, x2 = 0.56)))
        // 100 degrees away is a different chair.
        memory.observe(200, headingDegrees = 190.0, listOf(det("chair", x1 = 0.45, x2 = 0.55)))
        assertEquals(2, memory.count(200))
        // The most recent match is the second chair, seen once.
        assertEquals(1, memory.resolve("chair", 200)!!.sightings)
    }

    @Test
    fun `resolve returns the most recently seen match`() {
        val memory = LandmarkMemory(minSightings = 1)
        memory.observe(0, 0.0, listOf(det("bottle", x1 = 0.1, x2 = 0.2)))
        memory.observe(5_000, 180.0, listOf(det("bottle", x1 = 0.8, x2 = 0.9)))
        val hit = memory.resolve("the bottle", 5_000)!!
        assertEquals(5_000, hit.lastSeenMs)
        assertEquals(0.85, hit.lastCenterX, 1e-9)
    }

    @Test
    fun `labels match across synonyms colours and articles`() {
        assertTrue(labelsMatch("my blue backpack", "bag"))
        assertTrue(labelsMatch("the sofa", "couch"))
        assertTrue(labelsMatch("phone", "cell phone"))
        assertTrue(labelsMatch("mug", "cup"))
        assertTrue(labelsMatch("table", "dining table"))
        assertTrue(labelsMatch("plant", "potted plant"))
        assertFalse(labelsMatch("bottle", "cup"))
        assertFalse(labelsMatch("chair", "person"))
    }

    @Test
    fun `normalize keeps a colour that is also a noun`() {
        assertEquals("orange", normalizeLabel("the orange"))
        assertEquals("orange", normalizeLabel("orange"))
        assertEquals("cell phone", normalizeLabel("  My  Cell   Phone "))
    }

    @Test
    fun `wrap180 matches the python modulo`() {
        assertEquals(-170.0, wrap180(190.0), 1e-9)
        assertEquals(170.0, wrap180(-190.0), 1e-9)
        assertEquals(0.0, wrap180(360.0), 1e-9)
        assertEquals(-180.0, wrap180(180.0), 1e-9)
    }
}
