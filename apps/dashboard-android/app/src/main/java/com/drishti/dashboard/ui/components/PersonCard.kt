package com.drishti.dashboard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drishti.dashboard.data.MonitoredPerson
import com.drishti.dashboard.domain.Elapsed
import com.drishti.dashboard.domain.LiveStatus
import com.drishti.dashboard.domain.liveStatusOf
import com.drishti.dashboard.ui.CallIcon
import com.drishti.dashboard.ui.icon
import com.drishti.dashboard.ui.label
import com.drishti.dashboard.ui.theme.AttentionTone
import com.drishti.dashboard.ui.theme.HelpTone
import com.drishti.dashboard.ui.theme.InkFaint
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.SlateTone
import com.drishti.dashboard.ui.tone

/**
 * One person on the wall.
 *
 * Reading order is the order a coordinator asks the questions in: who, what
 * are they doing, how fresh is that, and — only if it applies — what do I do
 * about it. The status word and the colour rail say the same thing twice on
 * purpose: colour is faster, but colour alone is not accessible.
 */
@Composable
fun PersonCard(
    person: MonitoredPerson,
    nowMs: Long,
    onOpen: () -> Unit,
    onCall: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val status = liveStatusOf(person, nowMs)
    val tone = status.tone()
    val urgent = status == LiveStatus.HELP_REQUIRED

    RailCard(
        tone = tone,
        modifier = modifier.fillMaxWidth(),
        background = if (urgent) tone.fill else com.drishti.dashboard.ui.theme.Paper,
        onClick = onOpen,
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = person.name, id = person.id, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = InkStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            person.activity.icon(),
                            contentDescription = null,
                            tint = InkMuted,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = person.activity.label(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkMuted,
                            maxLines = 1,
                        )
                        Text(
                            text = "  ·  ${person.area}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = InkFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Icon(
                    Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = null,
                    tint = InkFaint,
                    modifier = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = person.activityDetail,
                style = MaterialTheme.typography.bodyLarge,
                color = InkStrong,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TonePill(label = status.label(), tone = tone, icon = status.icon())
                Spacer(Modifier.weight(1f))
                Text(
                    text = Elapsed.ago(person.lastUpdateMs, nowMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (status == LiveStatus.OFFLINE) tone.ink else InkMuted,
                )
            }

            val battery = person.batteryPercent
            if (battery != null && battery <= 20) {
                Spacer(Modifier.height(10.dp))
                TonePill(
                    label = "Battery $battery%",
                    tone = if (battery <= 15) AttentionTone else SlateTone,
                    showDot = true,
                )
            }

            val help = person.helpRequest
            if (help != null) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(HelpTone.strong, MaterialTheme.shapes.medium)
                        .padding(16.dp),
                ) {
                    Column {
                        Text(
                            text = help.kind.label(),
                            style = MaterialTheme.typography.titleMedium,
                            color = HelpTone.onStrong,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = "${help.locationLabel} · ${Elapsed.waiting(help.requestedAtMs, nowMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = HelpTone.onStrong.copy(alpha = 0.92f),
                        )
                        if (help.acknowledgedBy != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "Taken by ${help.acknowledgedBy}",
                                style = MaterialTheme.typography.labelMedium,
                                color = HelpTone.onStrong.copy(alpha = 0.92f),
                            )
                        }
                        if (onCall != null) {
                            Spacer(Modifier.height(14.dp))
                            BigAction(
                                label = "Call ${person.name.substringBefore(' ')}",
                                tone = HelpTone.copy(strong = HelpTone.onStrong, onStrong = HelpTone.ink),
                                icon = CallIcon,
                                onClick = onCall,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}
