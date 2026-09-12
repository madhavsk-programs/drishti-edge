package com.drishti.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenLanguageTest {

    @Test
    fun `legacy and regional tags resolve to the same language`() {
        assertEquals(SpokenLanguage.HINDI, SpokenLanguage.fromTag("hi"))
        assertEquals(SpokenLanguage.HINDI, SpokenLanguage.fromTag("hi-IN"))
        assertEquals(SpokenLanguage.TAMIL, SpokenLanguage.fromTag("ta-IN"))
    }

    @Test
    fun `Hindi scene instruction explicitly requires Devanagari`() {
        assertTrue(SpokenLanguage.HINDI.sceneAnswerInstruction.contains("Devanagari"))
    }
}
