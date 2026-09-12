@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.drishti.dashboard.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

internal val MonitorJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    namingStrategy = JsonNamingStrategy.SnakeCase
}

@Serializable
internal data class LivePersonWire(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val emergencyContactName: String,
    val emergencyContactNumber: String,
    val area: String,
    val activity: String,
    val activityDetail: String,
    val lastUpdateMs: Long,
    val batteryPercent: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracyM: Double? = null,
    val locationObservedAtMs: Long? = null,
    val events: List<SafetyEventWire> = emptyList(),
)

@Serializable
internal data class SafetyEventWire(
    val id: String,
    val kind: String,
    val atMs: Long,
    val detail: String,
    val locationLabel: String? = null,
)

internal fun LivePersonWire.toDomain(): MonitoredPerson = MonitoredPerson(
    id = id,
    name = name,
    phoneNumber = phoneNumber,
    emergencyContactName = emergencyContactName,
    emergencyContactNumber = emergencyContactNumber,
    area = area,
    activity = runCatching { Activity.valueOf(activity) }.getOrDefault(Activity.NOT_WALKING),
    activityDetail = activityDetail,
    lastUpdateMs = lastUpdateMs,
    batteryPercent = batteryPercent,
    helpRequest = null,
    events = events.mapNotNull { event ->
        runCatching {
            SafetyEvent(
                id = event.id,
                kind = SafetyEventKind.valueOf(event.kind),
                atMs = event.atMs,
                detail = event.detail,
                locationLabel = event.locationLabel,
            )
        }.getOrNull()
    },
    isLive = true,
    latitude = latitude,
    longitude = longitude,
    locationAccuracyM = locationAccuracyM,
    locationObservedAtMs = locationObservedAtMs,
)
