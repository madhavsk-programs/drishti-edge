package com.drishti.app.ui.gestures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WalkGesturesDeviceTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun twoFingerSwipeRightRoutesToExplore() {
        var exploreRequests = 0
        compose.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag("walk-surface")
                    .walkGestures(
                        onDoubleTap = {},
                        onSingleTap = {},
                        onLongPress = {},
                        onTripleTap = {},
                        onTwoFingerTap = {},
                        onThreeFingerTap = {},
                        onTwoFingerSwipeUp = {},
                        onTwoFingerSwipeRight = { exploreRequests++ },
                        onTwoFingerSwipeDown = {},
                    ),
            )
        }

        compose.onNodeWithTag("walk-surface").performTouchInput {
            val y = center.y
            down(0, Offset(width * 0.15f, y - 80f))
            down(1, Offset(width * 0.15f, y + 80f))
            moveTo(0, Offset(width * 0.80f, y - 80f))
            moveTo(1, Offset(width * 0.80f, y + 80f))
            up(0)
            up(1)
        }

        compose.runOnIdle { assertEquals(1, exploreRequests) }
    }
}
