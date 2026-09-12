package com.drishti.app.monitor

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import com.drishti.app.BuildConfig
import com.drishti.app.net.DetectionResult
import com.drishti.app.net.DrishtiApi
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.GuidanceContract
import com.drishti.app.net.MonitorObstacle
import com.drishti.app.net.MonitorTelemetryRequest
import com.drishti.app.net.TargetTrackingState
import com.drishti.app.net.apiCall
import com.drishti.app.walk.WalkMode
import com.drishti.app.walk.WalkUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Best-effort monitor output. A one-item drop-oldest channel makes submission
 * impossible to back up or delay the camera/inference path when Wi-Fi is down.
 */
class MonitorTelemetryClient(
    context: Context,
    private val api: DrishtiApi,
    private val location: WalkLocationTracker,
) {
    private val battery = context.applicationContext.getSystemService(BatteryManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Channel<MonitorTelemetryRequest>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch {
            for (request in pending) {
                when (val result = apiCall {
                    api.sendMonitorTelemetry(BuildConfig.MONITOR_TOKEN, request)
                }) {
                    is com.drishti.app.net.ApiResult.Ok -> Unit
                    else -> Log.d(TAG, "monitor telemetry unavailable: $result")
                }
            }
        }
    }

    fun offer(sessionId: String, state: WalkUiState) {
        pending.trySend(
            MonitorTelemetryRequest(
                participantId = LIVE_PARTICIPANT_ID,
                sessionId = sessionId,
                sentAtMs = System.currentTimeMillis(),
                activity = telemetryActivityFor(state),
                batteryPercent = battery
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    .takeIf { it in 0..100 },
                location = location.snapshot(),
                obstacle = obstacleFactFor(state.guidance, state.detections),
            ),
        )
    }

    fun shutdown() {
        pending.close()
    }

    companion object {
        const val LIVE_PARTICIPANT_ID = "live-arun"
        private const val TAG = "MonitorTelemetry"
    }
}

internal fun telemetryActivityFor(state: WalkUiState): String = when (state.mode) {
    WalkMode.WALKING -> if (
        state.target?.trackingState != null &&
        state.target.trackingState != TargetTrackingState.IDLE
    ) {
        "FINDING_OBJECT"
    } else {
        "WALKING"
    }
    WalkMode.READING -> "READING_TEXT"
    WalkMode.DESCRIBING -> "ASKING_SCENE"
    WalkMode.STOPPED -> "NOT_WALKING"
    WalkMode.STARTING, WalkMode.PAUSED, WalkMode.SOS, WalkMode.ERROR -> "RESTING"
}

internal fun obstacleFactFor(
    guidance: GuidanceContract?,
    detections: List<DetectionResult>,
): MonitorObstacle? {
    if (guidance == null || guidance.action == GuidanceAction.CLEAR) return null
    val obstacle = detections.maxByOrNull { it.riskScore }
    return MonitorObstacle(
        action = guidance.action.name,
        riskLevel = guidance.level.name,
        reasonCode = guidance.reasonCode,
        label = obstacle?.label,
        direction = obstacle?.direction?.name,
    )
}
