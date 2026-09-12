package com.drishti.dashboard.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.drishti.dashboard.data.MonitoredPerson
import com.drishti.dashboard.data.SafetyEvent
import com.drishti.dashboard.domain.Elapsed
import com.drishti.dashboard.domain.LiveStatus
import com.drishti.dashboard.domain.liveStatusOf
import com.drishti.dashboard.domain.mostRecentFirst
import com.drishti.dashboard.ui.CallIcon
import com.drishti.dashboard.ui.DashboardViewModel
import com.drishti.dashboard.ui.components.Avatar
import com.drishti.dashboard.ui.components.BigAction
import com.drishti.dashboard.ui.components.FactRow
import com.drishti.dashboard.ui.components.PaperCard
import com.drishti.dashboard.ui.components.QuietAction
import com.drishti.dashboard.ui.components.RailCard
import com.drishti.dashboard.ui.components.Rule
import com.drishti.dashboard.ui.components.SectionHeading
import com.drishti.dashboard.ui.components.TonePill
import com.drishti.dashboard.ui.explanation
import com.drishti.dashboard.ui.icon
import com.drishti.dashboard.ui.label
import com.drishti.dashboard.ui.rememberDialer
import com.drishti.dashboard.ui.theme.HelpTone
import com.drishti.dashboard.ui.theme.IndigoTone
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.Pill
import com.drishti.dashboard.ui.theme.SafeTone
import com.drishti.dashboard.ui.theme.SlateTone
import com.drishti.dashboard.ui.tone

/**
 * One person, in full: who they are, how to reach them, and everything their
 * phone has flagged this session.
 *
 * This is the screen somebody opens while a phone is ringing, and the screen
 * somebody opens a week later to write up what happened. Both readings want
 * the same thing — the events in order, with times, and with the wording that
 * distinguishes what was observed from what was inferred.
 */
@Composable
fun PersonScreen(
    viewModel: DashboardViewModel,
    person: MonitoredPerson,
    nowMs: Long,
) {
    val dial = rememberDialer()
    val status = liveStatusOf(person, nowMs)
    val tone = status.tone()
    val events = remember(person) { person.events.mostRecentFirst() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier
                    .clip(Pill)
                    .clickable { viewModel.goBack() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = IndigoTone.ink,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "All people",
                    style = MaterialTheme.typography.labelLarge,
                    color = IndigoTone.ink,
                )
            }
        }

        item {
            RailCard(tone = tone, modifier = Modifier.fillMaxWidth(), railWidth = 10.dp) {
                Column(Modifier.padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = person.name, id = person.id, size = 80.dp)
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = person.name,
                                style = MaterialTheme.typography.displaySmall,
                                color = InkStrong,
                            )
                            Spacer(Modifier.height(8.dp))
                            TonePill(label = status.label(), tone = tone, icon = status.icon())
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Rule()
                    Spacer(Modifier.height(20.dp))
                    FactRow(
                        label = "Doing",
                        value = person.activityDetail,
                        icon = person.activity.icon(),
                        iconTint = tone.strong,
                    )
                    Spacer(Modifier.height(16.dp))
                    FactRow(
                        label = "Area",
                        value = person.area,
                        icon = Icons.Rounded.LocationOn,
                        iconTint = tone.strong,
                    )
                    Spacer(Modifier.height(16.dp))
                    FactRow(
                        label = "Last update",
                        value = Elapsed.ago(person.lastUpdateMs, nowMs),
                        icon = status.icon(),
                        iconTint = tone.strong,
                        valueColor = if (status == LiveStatus.OFFLINE) tone.ink else InkStrong,
                    )
                    val battery = person.batteryPercent
                    if (battery != null) {
                        Spacer(Modifier.height(16.dp))
                        FactRow(label = "Battery", value = "$battery%")
                    }
                }
            }
        }

        val help = person.helpRequest
        if (help != null) {
            item {
                PaperCard(
                    Modifier.fillMaxWidth(),
                    border = HelpTone.edge,
                    background = HelpTone.fill,
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Warning,
                                contentDescription = null,
                                tint = HelpTone.strong,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = help.kind.label(),
                                style = MaterialTheme.typography.headlineSmall,
                                color = HelpTone.ink,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = help.kind.explanation(),
                            style = MaterialTheme.typography.bodyLarge,
                            color = HelpTone.ink,
                        )
                        Spacer(Modifier.height(16.dp))
                        FactRow(
                            label = "Where",
                            value = help.locationLabel,
                            valueColor = HelpTone.ink,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = Elapsed.coordinates(help.latitude, help.longitude),
                            style = MaterialTheme.typography.bodyMedium,
                            color = HelpTone.ink,
                        )
                        Spacer(Modifier.height(16.dp))
                        FactRow(
                            label = "Asked for help",
                            value = Elapsed.waiting(help.requestedAtMs, nowMs),
                            valueColor = HelpTone.ink,
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (help.acknowledgedBy == null) {
                                QuietAction(
                                    label = "I've got this",
                                    tone = SlateTone,
                                    onClick = { viewModel.acknowledge(person.id) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            QuietAction(
                                label = "Mark safe",
                                tone = SafeTone,
                                icon = Icons.Rounded.CheckCircle,
                                onClick = { viewModel.clearHelp(person.id) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }

        item {
            PaperCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp)) {
                    FactRow(
                        label = "Their phone",
                        value = person.phoneNumber,
                        icon = CallIcon,
                        iconTint = IndigoTone.strong,
                    )
                    Spacer(Modifier.height(14.dp))
                    BigAction(
                        label = "Call ${person.name.substringBefore(' ')}",
                        tone = IndigoTone,
                        icon = CallIcon,
                        onClick = { dial(person.phoneNumber) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(22.dp))
                    Rule()
                    Spacer(Modifier.height(22.dp))
                    FactRow(
                        label = "Emergency contact",
                        value = "${person.emergencyContactName}\n${person.emergencyContactNumber}",
                        icon = CallIcon,
                        iconTint = SlateTone.strong,
                    )
                    Spacer(Modifier.height(14.dp))
                    QuietAction(
                        label = "Call ${person.emergencyContactName.substringBefore(' ')}",
                        tone = SlateTone,
                        icon = CallIcon,
                        onClick = { dial(person.emergencyContactNumber) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (help == null && status == LiveStatus.OFFLINE) {
            item {
                PaperCard(
                    Modifier.fillMaxWidth(),
                    border = com.drishti.dashboard.ui.theme.OfflineTone.edge,
                    background = com.drishti.dashboard.ui.theme.OfflineTone.fill,
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Text(
                            text = "This phone has stopped reporting",
                            style = MaterialTheme.typography.headlineSmall,
                            color = com.drishti.dashboard.ui.theme.OfflineTone.ink,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Opening a check puts them at the top of the alerts queue so " +
                                "the next person on shift sees that somebody is looking into it. " +
                                "It does not mean they have asked for help.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = com.drishti.dashboard.ui.theme.OfflineTone.ink,
                        )
                        Spacer(Modifier.height(18.dp))
                        BigAction(
                            label = "Open a check",
                            tone = com.drishti.dashboard.ui.theme.OfflineTone,
                            onClick = { viewModel.raiseDeskCheck(person.id) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            SectionHeading(
                eyebrow = "This session",
                title = "Safety events",
                tone = IndigoTone,
            )
        }

        items(events, key = { it.id }) { event ->
            TimelineCard(event = event, nowMs = nowMs)
        }

        item {
            Text(
                text = "Events come from the phone and stay on it as well. DRISHTI records what " +
                    "the assistant did, never the camera view.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkMuted,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun TimelineCard(event: SafetyEvent, nowMs: Long) {
    val tone = event.kind.tone()
    PaperCard(Modifier.fillMaxWidth(), border = tone.edge) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(tone.strong),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    event.kind.icon(),
                    contentDescription = null,
                    tint = tone.strong,
                    modifier = Modifier
                        .padding(top = 2.dp, end = 14.dp)
                        .size(26.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = event.kind.label(),
                        style = MaterialTheme.typography.titleMedium,
                        color = InkStrong,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = event.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkMuted,
                    )
                    if (event.locationLabel != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.LocationOn,
                                contentDescription = null,
                                tint = InkMuted,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = event.locationLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkMuted,
                            )
                        }
                    }
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
}
