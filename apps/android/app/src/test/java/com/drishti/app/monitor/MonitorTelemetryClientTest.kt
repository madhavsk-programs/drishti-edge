package com.drishti.app.monitor

import com.drishti.app.net.ApproachState
import com.drishti.app.net.DetectionResult
import com.drishti.app.net.Direction
import com.drishti.app.net.DisplayColor
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.GuidanceContract
import com.drishti.app.net.HapticPattern
import com.drishti.app.net.NormalizedBoundingBox
import com.drishti.app.net.NormalizedPoint
import com.drishti.app.net.ProximityBand
import com.drishti.app.net.RiskLevel
import com.drishti.app.walk.WalkMode
import com.drishti.app.walk.WalkUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MonitorTelemetryClientTest {
    private fun guidance(action: GuidanceAction) = GuidanceContract(
        level = if (action == GuidanceAction.CLEAR) RiskLevel.CLEAR else RiskLevel.HIGH,
        action = action,
        speech = "",
        hapticPattern = HapticPattern.NONE,
        speak = false,
        reasonCode = if (action == GuidanceAction.CLEAR) "PATH_CLEAR" else "OBSTACLE_NEARBY",
    )

    private val chair = DetectionResult(
        label = "chair",
        confidence = 0.88,
        bbox = NormalizedBoundingBox(0.3, 0.2, 0.7, 0.9),
        anchor = NormalizedPoint(0.5, 0.9),
        direction = Direction.CENTRE,
        proximity = ProximityBand.NEAR,
        approachState = ApproachState.STATIONARY,
        pathOverlap = 0.8,
        riskScore = 0.91,
        riskLevel = RiskLevel.HIGH,
        displayColor = DisplayColor.RED,
    )

    @Test
    fun `clear guidance never fabricates an obstacle event`() {
        assertNull(obstacleFactFor(guidance(GuidanceAction.CLEAR), listOf(chair)))
    }

    @Test
    fun `non-clear guidance carries the riskiest detected obstacle`() {
        val fact = obstacleFactFor(guidance(GuidanceAction.STOP), listOf(chair))!!
        assertEquals("STOP", fact.action)
        assertEquals("chair", fact.label)
        assertEquals("CENTRE", fact.direction)
    }

    @Test
    fun `stopped mode reports that the walk ended`() {
        assertEquals("NOT_WALKING", telemetryActivityFor(WalkUiState(mode = WalkMode.STOPPED)))
    }
}
