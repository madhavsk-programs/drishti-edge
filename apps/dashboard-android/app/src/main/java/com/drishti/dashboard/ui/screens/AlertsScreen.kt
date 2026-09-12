package com.drishti.dashboard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drishti.dashboard.data.DeskSnapshot
import com.drishti.dashboard.data.HelpRequest
import com.drishti.dashboard.data.MonitoredPerson
import com.drishti.dashboard.data.SafetyEvent
import com.drishti.dashboard.domain.Elapsed
import com.drishti.dashboard.domain.needsACheck
import com.drishti.dashboard.domain.openHelpRequests
import com.drishti.dashboard.ui.CallIcon
import com.drishti.dashboard.ui.DashboardViewModel
import com.drishti.dashboard.ui.Route
import com.drishti.dashboard.ui.components.Avatar
import com.drishti.dashboard.ui.components.BigAction
import com.drishti.dashboard.ui.components.BigTextField
import com.drishti.dashboard.ui.components.FactRow
import com.drishti.dashboard.ui.components.PaperCard
import com.drishti.dashboard.ui.components.QuietAction
import com.drishti.dashboard.ui.components.RailCard
import com.drishti.dashboard.ui.components.Rule
import com.drishti.dashboard.ui.components.SectionHeading
import com.drishti.dashboard.ui.components.SolidPill
import com.drishti.dashboard.ui.components.TonePill
import com.drishti.dashboard.ui.explanation
import com.drishti.dashboard.ui.icon
import com.drishti.dashboard.ui.label
import com.drishti.dashboard.ui.rememberDialer
import com.drishti.dashboard.ui.theme.HelpTone
import com.drishti.dashboard.ui.theme.IndigoTone
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.SafeTone
import com.drishti.dashboard.ui.theme.SlateTone
import com.drishti.dashboard.ui.tone

/**
 * The queue: who has asked for help, oldest first, and what the desk can do
 * about it in one tap.
 *
 * Acknowledging and clearing are kept apart on purpose. Acknowledging says
 * somebody has picked this up, so a second operator does not ring the same
 * person; only "Mark safe" ends it. Those two being one button is how a desk
 * loses somebody.
 */
@Composable
fun AlertsScreen(
    viewModel: DashboardViewModel,
    snapshot: DeskSnapshot,
    nowMs: Long,
) {
    val dial = rememberDialer()
    val requests = remember(snapshot) { snapshot.people.openHelpRequests() }
    val feed = remember(snapshot, nowMs / 10_000) { snapshot.recentConcerns(nowMs) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeading(
                eyebrow = if (requests.isEmpty()) "All quiet" else "Act now",
                title = if (requests.isEmpty()) "No help requests" else "Help requested",
                tone = if (requests.isEmpty()) SafeTone else HelpTone,
                trailing = {
                    if (requests.isNotEmpty()) {
                        SolidPill(
                            label = if (requests.size == 1) "1 waiting" else "${requests.size} waiting",
                            tone = HelpTone,
                        )
                    }
                },
            )
        }

        item {
            BigTextField(
                value = viewModel.operatorName,
                onValueChange = { viewModel.operatorName = it },
                label = "Who is at the desk",
                placeholder = "Your name",
                tone = IndigoTone,
                leadingIcon = Icons.Rounded.Person,
            )
        }

        if (requests.isEmpty()) {
            item { AllQuiet() }
        }

        items(requests, key = { (person, _) -> person.id }) { (person, request) ->
            HelpRequestCard(
                person = person,
                request = request,
                nowMs = nowMs,
                onCall = { dial(person.phoneNumber) },
                onCallContact = { dial(person.emergencyContactNumber) },
                onAcknowledge = { viewModel.acknowledge(person.id) },
                onClear = { viewModel.clearHelp(person.id) },
                onOpen = { viewModel.go(Route.Person(person.id)) },
            )
        }

        item {
            Spacer(Modifier.height(10.dp))
            SectionHeading(
                eyebrow = "Last hour",
                title = "Safety events",
                tone = IndigoTone,
            )
        }

        if (feed.isEmpty()) {
            item {
                PaperCard(Modifier.fillMaxWidth()) {
                    Text(
                        text = "Nothing has been flagged in the last hour.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = InkMuted,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }

        items(feed, key = { (_, event) -> event.id }) { (person, event) ->
            EventRow(
                person = person,
                event = event,
                nowMs = nowMs,
                onClick = { viewModel.go(Route.Person(person.id)) },
            )
        }
    }
}

/**
 * Everything the operator needs before they speak: who, where, how long, and
 * what the phone actually observed — never a conclusion the phone did not
 * reach.
 */
@Composable
private fun HelpRequestCard(
    person: MonitoredPerson,
    request: HelpRequest,
    nowMs: Long,
    onCall: () -> Unit,
    onCallContact: () -> Unit,
    onAcknowledge: () -> Unit,
    onClear: () -> Unit,
    onOpen: () -> Unit,
) {
    RailCard(tone = HelpTone, modifier = Modifier.fillMaxWidth(), railWidth = 10.dp) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = person.name, id = person.id, size = 68.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = InkStrong,
                    )
                    Spacer(Modifier.height(6.dp))
                    TonePill(label = request.kind.label(), tone = HelpTone)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = request.kind.explanation(),
                style = MaterialTheme.typography.bodyLarge,
                color = InkStrong,
            )

            Spacer(Modifier.height(18.dp))
            Rule()
            Spacer(Modifier.height(18.dp))

            FactRow(
                label = "Where",
                value = request.locationLabel,
                icon = Icons.Rounded.LocationOn,
                iconTint = HelpTone.strong,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Elapsed.coordinates(request.latitude, request.longitude),
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
                modifier = Modifier.padding(start = 36.dp),
            )

            Spacer(Modifier.height(16.dp))
            FactRow(
                label = "Asked for help",
                value = "${Elapsed.ago(request.requestedAtMs, nowMs)} · ${Elapsed.waiting(request.requestedAtMs, nowMs)}",
                icon = Icons.Rounded.Refresh,
                iconTint = HelpTone.strong,
            )

            Spacer(Modifier.height(16.dp))
            FactRow(
                label = "Phone",
                value = person.phoneNumber,
                icon = CallIcon,
                iconTint = HelpTone.strong,
            )

            if (request.acknowledgedBy != null) {
                Spacer(Modifier.height(18.dp))
                TonePill(
                    label = "Taken by ${request.acknowledgedBy}" +
                        (request.acknowledgedAtMs?.let { ", ${Elapsed.ago(it, nowMs)}" } ?: ""),
                    tone = IndigoTone,
                    icon = Icons.Rounded.CheckCircle,
                )
            }

            Spacer(Modifier.height(20.dp))

            BigAction(
                label = "Call ${person.name.substringBefore(' ')}",
                tone = HelpTone,
                icon = CallIcon,
                onClick = onCall,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            QuietAction(
                label = "Call ${person.emergencyContactName.substringBefore(' ')}",
                tone = IndigoTone,
                icon = CallIcon,
                onClick = onCallContact,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (request.acknowledgedBy == null) {
                    QuietAction(
                        label = "I've got this",
                        tone = SlateTone,
                        onClick = onAcknowledge,
                        modifier = Modifier.weight(1f),
                    )
                }
                QuietAction(
                    label = "Mark safe",
                    tone = SafeTone,
                    icon = Icons.Rounded.CheckCircle,
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            QuietAction(
                label = "See their history",
                tone = SlateTone,
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AllQuiet() {
    PaperCard(Modifier.fillMaxWidth(), border = SafeTone.edge, background = SafeTone.fill) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = SafeTone.strong,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    text = "Nobody has asked for help",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SafeTone.ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "An SOS or a possible fall appears here within a second of the phone sending it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SafeTone.ink,
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    person: MonitoredPerson,
    event: SafetyEvent,
    nowMs: Long,
    onClick: () -> Unit,
) {
    val tone = event.kind.tone()
    PaperCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .then(Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    event.kind.icon(),
                    contentDescription = null,
                    tint = tone.strong,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${person.name} · ${event.kind.label()}",
                    style = MaterialTheme.typography.titleSmall,
                    color = InkStrong,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = event.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkMuted,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = Elapsed.ago(event.atMs, nowMs),
                style = MaterialTheme.typography.labelMedium,
                color = InkMuted,
            )
        }
    }
}

/**
 * Attention-grade events from the last hour, newest first, capped.
 *
 * Capped at twenty because this is a feed to glance at, not a log; the
 * per-person screen holds the full history and is where anyone reconstructing
 * an incident should be looking.
 */
private fun DeskSnapshot.recentConcerns(nowMs: Long): List<Pair<MonitoredPerson, SafetyEvent>> =
    people
        .flatMap { person -> person.events.map { person to it } }
        .filter { (_, event) -> event.kind.needsACheck() && nowMs - event.atMs <= 3_600_000L }
        .sortedByDescending { (_, event) -> event.atMs }
        .take(20)
