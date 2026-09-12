package com.drishti.dashboard.data

import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * A desk with nobody behind it.
 *
 * This is the hardcoded programme half of [MixedMonitorRepository]; its sample
 * people and hazards stand in memory beside the one live participant. It never
 * fabricates a derived status. It fabricates the underlying facts a phone would
 * report — last frame at, battery, an SOS press — and
 * [com.drishti.dashboard.domain.liveStatusOf] derives the rest exactly as it
 * will when the facts arrive over a wire.
 *
 * Times are stored as offsets from "now" and re-anchored on every tick, so a
 * dashboard left open on a table keeps reading "8 sec ago" instead of quietly
 * marking the whole programme offline halfway through a demo. The one person
 * who *is* offline is offline because their offset is large, not because the
 * clock ran out.
 */
class DemoMonitorRepository(
    scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    tickMillis: Long = 1_000,
) : MonitorRepository {

    /** Offsets in seconds before "now", per person id. */
    private val liveness: MutableMap<String, Int> = DemoScript.lastUpdateSecondsAgo.toMutableMap()
    private val helpRequests: MutableMap<String, HelpRequest> = DemoScript.helpRequests(clock()).toMutableMap()
    private var hazards: List<AggregateHazard> = DemoScript.hazards(clock())

    private val _snapshot = MutableStateFlow(build())
    override val snapshot: StateFlow<DeskSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            val jitter = Random(7)
            while (isActive) {
                delay(tickMillis)
                // Phones that are reporting jitter their last-frame age the way
                // a real 2 Hz feed does; the silent one keeps ageing.
                liveness.keys.forEach { id ->
                    if (DemoScript.lastUpdateSecondsAgo.getValue(id) < 60) {
                        liveness[id] = jitter.nextInt(0, 9)
                    }
                }
                _snapshot.value = build()
            }
        }
    }

    override fun acknowledgeHelp(personId: String, operatorName: String) {
        val existing = helpRequests[personId] ?: return
        if (existing.acknowledgedBy != null) return
        helpRequests[personId] = existing.copy(
            acknowledgedBy = operatorName,
            acknowledgedAtMs = clock(),
        )
        _snapshot.value = build()
    }

    override fun clearHelp(personId: String) {
        if (helpRequests.remove(personId) == null) return
        _snapshot.value = build()
    }

    override fun raiseDeskCheck(personId: String, operatorName: String) {
        if (helpRequests.containsKey(personId)) return
        val person = DemoScript.people.firstOrNull { it.id == personId } ?: return
        helpRequests[personId] = HelpRequest(
            kind = HelpKind.NO_RESPONSE,
            requestedAtMs = clock(),
            locationLabel = "Last known: ${person.lastKnownPlace}",
            latitude = person.latitude,
            longitude = person.longitude,
            acknowledgedBy = operatorName,
            acknowledgedAtMs = clock(),
        )
        _snapshot.value = build()
    }

    override fun setHazardStatus(hazardId: String, status: HazardStatus, assignedTo: String?) {
        hazards = hazards.map { hazard ->
            if (hazard.id != hazardId) {
                hazard
            } else {
                hazard.copy(
                    status = status,
                    assignedTo = if (status == HazardStatus.ASSIGNED) assignedTo else null,
                )
            }
        }
        _snapshot.value = build()
    }

    private fun build(): DeskSnapshot {
        val now = clock()
        val people = DemoScript.people.map { seed ->
            val secondsAgo = liveness.getValue(seed.id)
            MonitoredPerson(
                id = seed.id,
                name = seed.name,
                phoneNumber = seed.phoneNumber,
                emergencyContactName = seed.emergencyContactName,
                emergencyContactNumber = seed.emergencyContactNumber,
                area = seed.area,
                activity = seed.activity,
                activityDetail = seed.activityDetail,
                lastUpdateMs = now - secondsAgo * 1_000L,
                batteryPercent = seed.batteryPercent,
                helpRequest = helpRequests[seed.id],
                events = seed.events.map { event ->
                    SafetyEvent(
                        id = event.id,
                        kind = event.kind,
                        atMs = now - event.secondsAgo * 1_000L,
                        detail = event.detail,
                        locationLabel = event.locationLabel,
                    )
                },
            )
        }
        return DeskSnapshot(people = people, hazards = hazards, source = DataSource.DEMO)
    }
}
