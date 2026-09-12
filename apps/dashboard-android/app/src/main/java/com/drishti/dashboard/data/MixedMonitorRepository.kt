package com.drishti.dashboard.data

import android.util.Log
import com.drishti.dashboard.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One live participant over HTTP, merged with the existing hardcoded programme. */
class MixedMonitorRepository(
    scope: CoroutineScope,
    coordinatorUrl: String,
    tickMillis: Long = 2_000,
) : MonitorRepository {
    private val demo = DemoMonitorRepository(scope)
    private val endpoint = coordinatorUrl.trimEnd('/') + "/api/v1/monitor/live-person"
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    @Volatile private var demoSnapshot = demo.snapshot.value
    @Volatile private var livePerson: MonitoredPerson? = null
    private val _snapshot = MutableStateFlow(mergeMonitorSnapshot(demoSnapshot, livePerson))
    override val snapshot: StateFlow<DeskSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            demo.snapshot.collectLatest {
                demoSnapshot = it
                publish()
            }
        }
        scope.launch {
            while (isActive) {
                fetchLive()?.let {
                    livePerson = it
                    publish()
                }
                delay(tickMillis)
            }
        }
    }

    override fun acknowledgeHelp(personId: String, operatorName: String) =
        demo.acknowledgeHelp(personId, operatorName)

    override fun clearHelp(personId: String) = demo.clearHelp(personId)

    override fun raiseDeskCheck(personId: String, operatorName: String) =
        demo.raiseDeskCheck(personId, operatorName)

    override fun setHazardStatus(hazardId: String, status: HazardStatus, assignedTo: String?) =
        demo.setHazardStatus(hazardId, status, assignedTo)

    private fun publish() {
        _snapshot.value = mergeMonitorSnapshot(demoSnapshot, livePerson)
    }

    private suspend fun fetchLive(): MonitoredPerson? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(
                Request.Builder()
                    .url(endpoint)
                    .header("X-Drishti-Key", BuildConfig.MONITOR_TOKEN)
                    .get()
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string() ?: error("empty response")
                MonitorJson.decodeFromString<LivePersonWire>(body).toDomain()
            }
        }.onFailure { Log.d(TAG, "live feed unavailable: ${it.message}") }.getOrNull()
    }

    private companion object { const val TAG = "MixedMonitorRepository" }
}

internal fun mergeMonitorSnapshot(
    demo: DeskSnapshot,
    live: MonitoredPerson?,
): DeskSnapshot = DeskSnapshot(
    people = if (live == null) {
        demo.people
    } else {
        listOf(live) + demo.people.filterNot { it.id == live.id }
    },
    hazards = demo.hazards,
    source = if (live == null) DataSource.DEMO else DataSource.MIXED,
)
