package com.drishti.app.scene

import com.drishti.app.net.TargetGuidanceStep
import com.drishti.app.net.TargetHapticPattern
import com.drishti.app.net.TargetRangeHint
import com.drishti.app.net.TargetTrackingState
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.LandmarkMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUILD_PLAN.md A10 acceptance: lock from memory, tracking, loss, rescan and
 * safety preemption, with no VLM invoked.
 */
class TargetGuidanceTest {

    private fun det(label: String, cx: Double, h: Double = 0.3, conf: Double = 0.9): DetectionCandidate {
        val y2 = 0.5 + h / 2
        return DetectionCandidate(label, conf, cx - 0.05, y2 - h, cx + 0.05, y2)
    }

    private fun lockFromMemory(cx: Double = 0.5, heading: Double? = null): TargetGuidance {
        val memory = LandmarkMemory()
        memory.observe(0, heading, listOf(det("bottle", cx)))
        memory.observe(100, heading, listOf(det("bottle", cx)))
        val landmark = memory.resolve("bottle", 100)
        assertNotNull("memory must resolve after two sightings", landmark)
        val guidance = TargetGuidance()
        guidance.startGuidance("bottle", landmark!!, heading, visible = true)
        return guidance
    }

    @Test
    fun `lock from memory seeds guiding with a camera-relative bearing`() {
        val guidance = lockFromMemory(cx = 0.75)
        assertEquals(TargetTrackingState.GUIDING, guidance.state)
        val tt = guidance.step(200, null, listOf(det("bottle", 0.75)), isSafetyOverridden = false, hapticsEnabled = true)
        // (0.75 - 0.5) * 67 = 16.75 degrees to the right: past face tolerance, under the turn threshold.
        assertEquals(16.75, tt.bearingDegrees!!, 1e-9)
        assertEquals(TargetGuidanceStep.KEEP_TURNING, tt.guidanceStep)
        assertTrue(tt.speak)
        assertEquals(TargetHapticPattern.TARGET_RIGHT_PULSE, tt.hapticPattern)
    }

    @Test
    fun `tracking follows the live box and arrives after the dwell`() {
        val guidance = lockFromMemory(cx = 0.5)
        var t = 200L
        var tt = guidance.step(t, null, listOf(det("bottle", 0.5, h = 0.2)), false, true)
        assertEquals(TargetGuidanceStep.FACE_AND_WALK, tt.guidanceStep)
        assertEquals(TargetRangeHint.MID, tt.rangeHint)

        // The box grows: NEAR, but not yet arrived.
        t += 500
        tt = guidance.step(t, null, listOf(det("bottle", 0.5, h = 0.6)), false, true)
        assertEquals(TargetRangeHint.NEAR, tt.rangeHint)
        assertEquals(TargetGuidanceStep.WALKING, tt.guidanceStep)
        assertEquals(TargetTrackingState.GUIDING, tt.trackingState)

        // Two seconds of dwell in front of it.
        t += 2_100
        tt = guidance.step(t, null, listOf(det("bottle", 0.5, h = 0.6)), false, true)
        assertEquals(TargetGuidanceStep.ARRIVED, tt.guidanceStep)
        assertEquals(TargetTrackingState.ARRIVED, tt.trackingState)
        assertTrue(tt.speak)

        // Arrival clears the session on the next frame.
        tt = guidance.step(t + 100, null, emptyList(), false, true)
        assertEquals(TargetTrackingState.IDLE, tt.trackingState)
        assertNull(tt.targetName)
    }

    @Test
    fun `loss waits for the reacquire timeout then asks for a rescan once`() {
        // A heading is provided so the stored world bearing keeps guidance
        // alive across the out-of-view window; the LOST transition is what the
        // timeout produces, not the first missed frame.
        val guidance = lockFromMemory(cx = 0.5, heading = 0.0)
        guidance.step(200, 0.0, listOf(det("bottle", 0.5)), false, true)

        // Out of view for less than the timeout: still guiding on world bearing.
        var tt = guidance.step(4_000, 0.0, emptyList(), false, true)
        assertEquals(TargetTrackingState.GUIDING, tt.trackingState)
        assertNotNull(tt.bearingDegrees)

        // Past the timeout: LOST, and the rescan line is spoken exactly once.
        tt = guidance.step(9_000, 0.0, emptyList(), false, true)
        assertEquals(TargetTrackingState.LOST, tt.trackingState)
        assertEquals(TargetGuidanceStep.REACQUIRE, tt.guidanceStep)
        assertTrue(tt.speak)
        tt = guidance.step(9_500, 0.0, emptyList(), false, true)
        assertEquals(TargetTrackingState.LOST, tt.trackingState)
        assertFalse("the lost line must not repeat every frame", tt.speak)

        // It comes back into view: guidance resumes.
        tt = guidance.step(10_000, 0.0, listOf(det("bottle", 0.5)), false, true)
        assertEquals(TargetTrackingState.GUIDING, tt.trackingState)
        assertEquals(TargetGuidanceStep.FACE_AND_WALK, tt.guidanceStep)
        assertTrue(tt.speak)
    }

    @Test
    fun `a safety action overrides the cue and it is dropped not queued`() {
        val guidance = lockFromMemory(cx = 0.9)
        val overridden = guidance.step(200, null, listOf(det("bottle", 0.9)), isSafetyOverridden = true, hapticsEnabled = true)
        assertTrue(overridden.isSafetyOverridden)
        assertFalse(overridden.speak)
        assertEquals("", overridden.speech)
        assertEquals(TargetHapticPattern.NONE, overridden.hapticPattern)

        // When safety clears, the next frame speaks the *current* step, not a
        // backlog: exactly one line, for the state now.
        val resumed = guidance.step(300, null, listOf(det("bottle", 0.9)), false, true)
        assertFalse(resumed.isSafetyOverridden)
        assertEquals(TargetGuidanceStep.TURN_RIGHT, resumed.guidanceStep)
        assertTrue(resumed.speak)
    }

    @Test
    fun `world bearing guides an out-of-view landmark when a heading exists`() {
        // Seen at heading 90 with the object dead centre: world bearing 90.
        val guidance = lockFromMemory(cx = 0.5, heading = 90.0)
        // The user has turned to face 180: the bottle is 90 degrees to the left.
        val tt = guidance.step(200, 180.0, emptyList(), false, true)
        assertEquals(-90.0, tt.bearingDegrees!!, 1e-9)
        assertEquals(TargetGuidanceStep.TURN_LEFT, tt.guidanceStep)
        assertEquals(TargetHapticPattern.TARGET_LEFT_PULSE, tt.hapticPattern)
    }

    @Test
    fun `a weak false positive cannot pull guidance off the target`() {
        // Heading 0, target dead centre: world bearing 0.
        val guidance = lockFromMemory(cx = 0.5, heading = 0.0)
        guidance.step(200, 0.0, listOf(det("bottle", 0.5)), false, true)
        // A weak box far to the left would swing the bearing to -26.8 if it
        // were believed; below the confidence floor it is ignored.
        val tt = guidance.step(300, 0.0, listOf(det("bottle", 0.1, conf = 0.2)), false, true)
        assertNull(tt.targetCenter)
        assertEquals(0.0, tt.bearingDegrees!!, 1e-9)
    }

    @Test
    fun `speech repeats on the interval while the step is unchanged`() {
        val guidance = lockFromMemory(cx = 0.5)
        val first = guidance.step(200, null, listOf(det("bottle", 0.5)), false, true)
        assertTrue(first.speak)
        val soon = guidance.step(2_000, null, listOf(det("bottle", 0.5)), false, true)
        assertFalse(soon.speak)
        val later = guidance.step(4_300, null, listOf(det("bottle", 0.5)), false, true)
        assertTrue(later.speak)
    }
}
