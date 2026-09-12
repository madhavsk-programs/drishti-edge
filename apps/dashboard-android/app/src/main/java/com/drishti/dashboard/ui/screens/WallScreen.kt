package com.drishti.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drishti.dashboard.data.DeskSnapshot
import com.drishti.dashboard.domain.LiveStatus
import com.drishti.dashboard.domain.countByStatus
import com.drishti.dashboard.domain.liveStatusOf
import com.drishti.dashboard.domain.sortedForTheWall
import com.drishti.dashboard.ui.DashboardViewModel
import com.drishti.dashboard.ui.Route
import com.drishti.dashboard.ui.components.CountTile
import com.drishti.dashboard.ui.components.PaperCard
import com.drishti.dashboard.ui.components.PersonCard
import com.drishti.dashboard.ui.components.SectionHeading
import com.drishti.dashboard.ui.components.TileGrid
import com.drishti.dashboard.ui.SourceBadge
import com.drishti.dashboard.ui.components.BigTextField
import com.drishti.dashboard.ui.rememberDialer
import com.drishti.dashboard.ui.theme.IndigoTone
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.tileLabel
import com.drishti.dashboard.ui.tone

/**
 * The wall: everybody, worst first.
 *
 * The four counts across the top double as filters. Tapping "Help required"
 * does not hide the others permanently — the chip shows what is being filtered
 * and a second tap clears it — because a filter that survives a glance is a
 * filter that hides an emergency from the next person to pick up the phone.
 */
@Composable
fun WallScreen(
    viewModel: DashboardViewModel,
    snapshot: DeskSnapshot,
    nowMs: Long,
) {
    var filter: LiveStatus? by rememberSaveable { mutableStateOf(null) }
    val dial = rememberDialer()

    val counts = remember(snapshot, nowMs / 1_000) { snapshot.people.countByStatus(nowMs) }
    val ordered = remember(snapshot, nowMs / 1_000, filter, viewModel.personFilter) {
        snapshot.people
            .sortedForTheWall(nowMs)
            .filter { filter == null || liveStatusOf(it, nowMs) == filter }
            .filter { person ->
                val query = viewModel.personFilter.trim()
                query.isEmpty() ||
                    person.name.contains(query, ignoreCase = true) ||
                    person.area.contains(query, ignoreCase = true)
            }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeading(
                eyebrow = "Right now",
                title = "Who is out",
                tone = IndigoTone,
                trailing = { SourceBadge(snapshot.source) },
            )
        }

        item {
            TileGrid(
                tiles = LiveStatus.entries.map { status ->
                    { modifier ->
                        CountTile(
                            count = counts[status] ?: 0,
                            label = status.tileLabel(),
                            tone = status.tone(),
                            selected = filter == status,
                            modifier = modifier,
                            onClick = { filter = if (filter == status) null else status },
                        )
                    }
                },
            )
        }

        item {
            BigTextField(
                value = viewModel.personFilter,
                onValueChange = { viewModel.personFilter = it },
                label = "Find a person",
                placeholder = "Name or area",
                tone = IndigoTone,
                leadingIcon = Icons.Rounded.Search,
            )
        }

        if (ordered.isEmpty()) {
            item { EmptyWall(hasFilter = filter != null || viewModel.personFilter.isNotBlank()) }
        }

        items(ordered, key = { it.id }) { person ->
            PersonCard(
                person = person,
                nowMs = nowMs,
                onOpen = { viewModel.go(Route.Person(person.id)) },
                onCall = { dial(person.phoneNumber) },
            )
        }

        item {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "A phone that stops reporting for 90 seconds is shown as No signal. " +
                    "Silence is never read as safe.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun EmptyWall(hasFilter: Boolean) {
    PaperCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (hasFilter) "Nobody matches that" else "Nobody is out right now",
                style = MaterialTheme.typography.headlineSmall,
                color = InkStrong,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (hasFilter) {
                    "Clear the filter to see the whole programme."
                } else {
                    "People appear here the moment they start a walk on their phone."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = InkMuted,
            )
        }
    }
}
