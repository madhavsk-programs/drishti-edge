package com.drishti.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
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
import com.drishti.dashboard.data.AggregateHazard
import com.drishti.dashboard.data.DeskSnapshot
import com.drishti.dashboard.data.HazardStatus
import com.drishti.dashboard.domain.Elapsed
import com.drishti.dashboard.domain.isCorroborated
import com.drishti.dashboard.domain.sortedForWorks
import com.drishti.dashboard.domain.tally
import com.drishti.dashboard.ui.DashboardViewModel
import com.drishti.dashboard.ui.components.BigAction
import com.drishti.dashboard.ui.components.BigTextField
import com.drishti.dashboard.ui.components.CountTile
import com.drishti.dashboard.ui.components.QuietAction
import com.drishti.dashboard.ui.components.RailCard
import com.drishti.dashboard.ui.components.Rule
import com.drishti.dashboard.ui.components.SectionHeading
import com.drishti.dashboard.ui.components.SolidPill
import com.drishti.dashboard.ui.components.TonePill
import com.drishti.dashboard.ui.icon
import com.drishti.dashboard.ui.label
import com.drishti.dashboard.ui.theme.ClayTone
import com.drishti.dashboard.ui.theme.HelpTone
import com.drishti.dashboard.ui.theme.IndigoTone
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.SafeTone
import com.drishti.dashboard.ui.theme.SlateTone
import com.drishti.dashboard.ui.theme.Sunken
import com.drishti.dashboard.ui.tone

/**
 * What is broken in the street, ranked by how many different people have
 * walked into it.
 *
 * The screen's whole job is to keep one person's report and four people's
 * reports visually distinct. A works department that receives a list mixing
 * the two learns to discount the list, and then the corroborated ones stop
 * getting fixed either.
 */
@Composable
fun HazardsScreen(
    viewModel: DashboardViewModel,
    snapshot: DeskSnapshot,
    nowMs: Long,
) {
    val ordered = remember(snapshot) { snapshot.hazards.sortedForWorks() }
    val tally = remember(snapshot) { snapshot.hazards.tally() }
    // Survives rotation so a half-typed department name is not lost, and is
    // shared by every card on the screen: assigning fifteen hazards to the
    // same team should be fifteen taps, not fifteen retypings.
    var team by rememberSaveable { mutableStateOf("Corporation Zone 13") }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeading(
                eyebrow = "Reported from the street",
                title = "Hazards",
                tone = ClayTone,
            )
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CountTile(
                    count = tally.corroborated,
                    label = "Confirmed by 2+ people",
                    tone = HelpTone,
                    modifier = Modifier.width(168.dp),
                )
                CountTile(
                    count = tally.singleReport,
                    label = "One report only",
                    tone = SlateTone,
                    modifier = Modifier.width(168.dp),
                )
                CountTile(
                    count = tally.peopleAffected,
                    label = "People caught out",
                    tone = ClayTone,
                    modifier = Modifier.width(168.dp),
                )
                CountTile(
                    count = tally.resolved,
                    label = "Fixed",
                    tone = SafeTone,
                    modifier = Modifier.width(168.dp),
                )
            }
        }

        item {
            BigTextField(
                value = team,
                onValueChange = { team = it },
                label = "Send hazards to",
                placeholder = "Which team or department",
                tone = IndigoTone,
                leadingIcon = Icons.Rounded.Build,
            )
        }

        items(ordered, key = { it.id }) { hazard ->
            HazardCard(
                hazard = hazard,
                nowMs = nowMs,
                team = team,
                onAssign = { viewModel.assignHazard(hazard.id, team.ifBlank { "the council" }) },
                onResolve = { viewModel.resolveHazard(hazard.id) },
                onReopen = { viewModel.reopenHazard(hazard.id) },
            )
        }

        item {
            Text(
                text = "A hazard is counted once per person, not once per report. Somebody who " +
                    "walks the same street twice a day does not make a pothole more real.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun HazardCard(
    hazard: AggregateHazard,
    nowMs: Long,
    team: String,
    onAssign: () -> Unit,
    onResolve: () -> Unit,
    onReopen: () -> Unit,
) {
    val confirmed = hazard.isCorroborated()
    val tone = hazard.category.tone()

    RailCard(
        tone = if (hazard.status == HazardStatus.RESOLVED) SafeTone else tone,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonePill(label = hazard.category.label(), tone = tone, showDot = false)
                TonePill(label = "${hazard.severity.label()} risk", tone = hazard.severity.tone())
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = hazard.locationLabel,
                style = MaterialTheme.typography.headlineSmall,
                color = InkStrong,
            )

            Spacer(Modifier.height(14.dp))
            // Two pills rather than one sentence: at 384dp a single pill
            // truncates, and "Confirmed by 5 people · 9" is a worse thing to
            // show than two facts side by side.
            if (confirmed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SolidPill(
                        label = "Confirmed by ${hazard.reporterCount}",
                        tone = HelpTone,
                        icon = Icons.Rounded.CheckCircle,
                    )
                    TonePill(
                        label = "${hazard.reportCount} reports",
                        tone = SlateTone,
                        showDot = false,
                    )
                }
            } else {
                TonePill(
                    label = "One person only — not confirmed",
                    tone = SlateTone,
                    icon = Icons.Rounded.Refresh,
                )
            }

            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Sunken, MaterialTheme.shapes.medium)
                    .padding(16.dp),
            ) {
                Text(
                    text = "“${hazard.note}”",
                    style = MaterialTheme.typography.bodyLarge,
                    color = InkStrong,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "FIRST REPORTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkMuted,
                    )
                    Text(
                        text = Elapsed.ago(hazard.firstReportedMs, nowMs),
                        style = MaterialTheme.typography.titleSmall,
                        color = InkStrong,
                    )
                }
                Column {
                    Text(
                        text = "LAST REPORTED",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkMuted,
                    )
                    Text(
                        text = Elapsed.ago(hazard.lastReportedMs, nowMs),
                        style = MaterialTheme.typography.titleSmall,
                        color = InkStrong,
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Rule()
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TonePill(
                    label = hazard.status.label(),
                    tone = hazard.status.tone(),
                    icon = hazard.status.icon(),
                )
            }
            if (hazard.assignedTo != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "With ${hazard.assignedTo}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted,
                )
            }

            Spacer(Modifier.height(16.dp))
            when (hazard.status) {
                HazardStatus.NEW -> {
                    BigAction(
                        label = "Send to ${team.ifBlank { "the council" }}",
                        tone = IndigoTone,
                        icon = Icons.Rounded.Build,
                        onClick = onAssign,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HazardStatus.ASSIGNED -> {
                    QuietAction(
                        label = "Mark as fixed",
                        tone = SafeTone,
                        icon = Icons.Rounded.CheckCircle,
                        onClick = onResolve,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HazardStatus.RESOLVED -> {
                    QuietAction(
                        label = "It is back — reopen",
                        tone = ClayTone,
                        icon = Icons.Rounded.Refresh,
                        onClick = onReopen,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
