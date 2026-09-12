package com.drishti.app.walk

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraFramePipelineTest {

    @Test
    fun centerCropRemovesSidesForNarrowViewport() {
        assertEquals(
            CenterCrop(left = 1_156, top = 0, width = 1_688, height = 3_000),
            centerCrop(width = 4_000, height = 3_000, targetAspect = 9f / 16f),
        )
    }

    @Test
    fun centerCropRemovesTopAndBottomForWideViewport() {
        assertEquals(
            CenterCrop(left = 0, top = 375, width = 4_000, height = 2_250),
            centerCrop(width = 4_000, height = 3_000, targetAspect = 16f / 9f),
        )
    }

    @Test
    fun centerCropKeepsFullFrameWithoutAViewport() {
        assertEquals(
            CenterCrop(left = 0, top = 0, width = 640, height = 480),
            centerCrop(width = 640, height = 480, targetAspect = null),
        )
    }
}
