package com.drishti.dashboard.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.drishti.dashboard.data.DemoMonitorRepository
import com.drishti.dashboard.BuildConfig
import com.drishti.dashboard.data.MixedMonitorRepository
import com.drishti.dashboard.data.HazardStatus
import com.drishti.dashboard.data.MonitorRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Where the app is. Deliberately a sealed type rather than a string route. */
sealed interface Route {
    data object Wall : Route
    data object Alerts : Route
    data object Hazards : Route
    data class Person(val id: String) : Route
}

/**
 * One view model for the whole app.
 *
 * Three screens sharing one snapshot and one clock is simpler than three that
 * each poll, and it means the count on the Alerts tab can never disagree with
 * the number of red cards on the wall.
 *
 * [now] ticks once a second on its own. Every relative time on screen is
 * derived from it rather than stored, so "waiting 6 min" becomes "waiting 7
 * min" without anything being refetched — and, more importantly, a phone that
 * goes quiet crosses into "No signal" on its own instead of waiting for the
 * next poll to notice.
 */
class DashboardViewModel(
    private val repository: MonitorRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    val snapshot = repository.snapshot

    private val _now = MutableStateFlow(clock())
    val now: StateFlow<Long> = _now.asStateFlow()

    var route: Route by mutableStateOf(Route.Wall)
        private set

    /**
     * Who is at the desk. It is written into the acknowledgement so the next
     * person on shift can see who picked a call up; it is not a login, and
     * nothing here is access control.
     */
    var operatorName: String by mutableStateOf("")

    /** Free-text filter on the wall. Empty means everyone. */
    var personFilter: String by mutableStateOf("")

    init {
        viewModelScope.launch {
            while (isActive) {
                _now.value = clock()
                delay(1_000)
            }
        }
    }

    fun go(destination: Route) {
        route = destination
    }

    /** True when the back press was consumed by leaving a detail screen. */
    fun goBack(): Boolean {
        val current = route
        return if (current is Route.Person) {
            route = Route.Wall
            true
        } else {
            false
        }
    }

    fun acknowledge(personId: String) =
        repository.acknowledgeHelp(personId, operatorName.ifBlank { "the desk" })

    fun clearHelp(personId: String) = repository.clearHelp(personId)

    fun raiseDeskCheck(personId: String) =
        repository.raiseDeskCheck(personId, operatorName.ifBlank { "the desk" })

    fun assignHazard(hazardId: String, team: String) =
        repository.setHazardStatus(hazardId, HazardStatus.ASSIGNED, team)

    fun resolveHazard(hazardId: String) =
        repository.setHazardStatus(hazardId, HazardStatus.RESOLVED)

    fun reopenHazard(hazardId: String) =
        repository.setHazardStatus(hazardId, HazardStatus.NEW)

    companion object {
        /**
         * The composition root: one mixed repository for the app process.
         */
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val viewModel = DashboardViewModel(RepositoryHolder.instance)
                return viewModel as T
            }
        }
    }
}

/**
 * The mixed repository outlives any one view model so polling, its demo clock
 * and acknowledgements survive a rotation.
 */
internal object RepositoryHolder {
    lateinit var instance: MonitorRepository
        private set

    fun install(repository: MonitorRepository) {
        if (!::instance.isInitialized) instance = repository
    }

    fun installDemo(scope: kotlinx.coroutines.CoroutineScope) {
        install(DemoMonitorRepository(scope))
    }

    fun installMixed(scope: kotlinx.coroutines.CoroutineScope) {
        install(MixedMonitorRepository(scope, BuildConfig.COORDINATOR_URL))
    }
}
