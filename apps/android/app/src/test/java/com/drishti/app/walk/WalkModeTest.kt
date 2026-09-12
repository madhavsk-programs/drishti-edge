package com.drishti.app.walk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkModeTest {
    @Test
    fun `reading keeps safety inference active`() {
        assertTrue(WalkMode.WALKING.keepsWalkInferenceActive())
        assertTrue(WalkMode.READING.keepsWalkInferenceActive())
    }

    @Test
    fun `other explicit modes suppress walking inference`() {
        listOf(
            WalkMode.STARTING,
            WalkMode.PAUSED,
            WalkMode.DESCRIBING,
            WalkMode.SOS,
            WalkMode.STOPPED,
            WalkMode.ERROR,
        ).forEach { assertFalse(it.keepsWalkInferenceActive()) }
    }
}
