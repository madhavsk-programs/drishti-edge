package com.drishti.dashboard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drishti.dashboard.data.DataSource
import com.drishti.dashboard.domain.LiveStatus
import com.drishti.dashboard.domain.liveStatusOf
import com.drishti.dashboard.domain.openHelpRequests
import com.drishti.dashboard.ui.screens.AlertsScreen
import com.drishti.dashboard.ui.screens.HazardsScreen
import com.drishti.dashboard.ui.screens.PersonScreen
import com.drishti.dashboard.ui.screens.WallScreen
import com.drishti.dashboard.ui.theme.Canvas
import com.drishti.dashboard.ui.theme.ClayTone
import com.drishti.dashboard.ui.theme.Hairline
import com.drishti.dashboard.ui.theme.HelpTone
import com.drishti.dashboard.ui.theme.IndigoTone
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.Paper
import com.drishti.dashboard.ui.theme.Pill
import com.drishti.dashboard.ui.theme.Tone
import com.drishti.dashboard.ui.theme.WordmarkStyle
import com.drishti.dashboard.ui.theme.avatarToneFor

/**
 * The shell: a masthead that never changes, a screen, and three large
 * destinations along the bottom.
 *
 * Three, not five. Everything an NGO desk does with this splits into "who is
 * out", "who needs me now" and "what is broken in the street", and a tab bar
 * with room for three 110dp targets is a tab bar a person can hit while
 * holding a phone to their ear.
 */
@Composable
fun DashboardApp(viewModel: DashboardViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()

    val openRequests = remember(snapshot, now) { snapshot.people.openHelpRequests() }
    val needingCheck = remember(snapshot, now) {
        snapshot.people.count { liveStatusOf(it, now) == LiveStatus.NEEDS_ATTENTION }
    }

    BackHandler(enabled = viewModel.route is Route.Person) { viewModel.goBack() }

    Box(Modifier.fillMaxSize().background(Canvas)) {
        Column(Modifier.fillMaxSize()) {
            Masthead()
            Box(Modifier.weight(1f)) {
                when (val route = viewModel.route) {
                    Route.Wall -> WallScreen(viewModel, snapshot, now)
                    Route.Alerts -> AlertsScreen(viewModel, snapshot, now)
                    Route.Hazards -> HazardsScreen(viewModel, snapshot, now)
                    is Route.Person -> {
                        val person = snapshot.people.firstOrNull { it.id == route.id }
                        if (person == null) {
                            // The only way here is a person leaving the roster
                            // while their card is open. Go back rather than
                            // showing an empty shell.
                            viewModel.go(Route.Wall)
                        } else {
                            PersonScreen(viewModel, person, now)
                        }
                    }
                }
            }
            TabBar(
                current = viewModel.route,
                alertCount = openRequests.size,
                attentionCount = needingCheck,
                onSelect = viewModel::go,
            )
        }
    }
}

/**
 * The masthead: the name, and nothing else.
 *
 * The data-source badge that used to sit here moved down beside each screen's
 * heading, so the top bar is a fixed, quiet strip and the thing that changes
 * (sample versus live) sits next to the thing it describes.
 */
@Composable
private fun Masthead() {
    Surface(color = Paper, shadowElevation = 2.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Paper, IndigoTone.fill.copy(alpha = 0.55f)),
                    ),
                )
                .statusBarsPadding()
                .padding(horizontal = 22.dp, vertical = 18.dp),
        ) {
            Text(
                text = "DRISHTI Monitor",
                style = WordmarkStyle,
                color = InkStrong,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SourceBadge(source: DataSource) {
    val tone: Tone = if (source == DataSource.DEMO) ClayTone else IndigoTone
    val label = when (source) {
        DataSource.DEMO -> "Sample data"
        DataSource.LIVE -> "Live"
        DataSource.MIXED -> "1 live + sample"
    }
    Row(
        modifier = Modifier
            .clip(Pill)
            .background(tone.fill)
            .border(1.dp, tone.edge, Pill)
            .padding(horizontal = 13.dp, vertical = 7.dp)
            .semantics {
                contentDescription = when (source) {
                    DataSource.DEMO -> "Showing sample data. No device is reporting."
                    DataSource.LIVE -> "Showing live data."
                    DataSource.MIXED -> "Showing one live phone with sample programme data."
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(9.dp).clip(Pill).background(tone.strong))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tone.ink)
    }
}

@Composable
private fun TabBar(
    current: Route,
    alertCount: Int,
    attentionCount: Int,
    onSelect: (Route) -> Unit,
) {
    // A person's detail screen is reached from the wall, so the wall stays lit
    // underneath it rather than leaving no tab selected.
    val selected = if (current is Route.Person) Route.Wall else current
    Surface(color = Paper) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Hairline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabItem(
                    modifier = Modifier.weight(1f),
                    label = "People",
                    icon = Icons.Rounded.Home,
                    tone = IndigoTone,
                    selected = selected == Route.Wall,
                    badge = if (attentionCount > 0) attentionCount else null,
                    badgeTone = com.drishti.dashboard.ui.theme.AttentionTone,
                    onClick = { onSelect(Route.Wall) },
                )
                TabItem(
                    modifier = Modifier.weight(1f),
                    label = "Alerts",
                    icon = Icons.Rounded.Warning,
                    tone = HelpTone,
                    selected = selected == Route.Alerts,
                    badge = if (alertCount > 0) alertCount else null,
                    badgeTone = HelpTone,
                    onClick = { onSelect(Route.Alerts) },
                )
                TabItem(
                    modifier = Modifier.weight(1f),
                    label = "Hazards",
                    icon = Icons.Rounded.Build,
                    tone = ClayTone,
                    selected = selected == Route.Hazards,
                    badge = null,
                    badgeTone = ClayTone,
                    onClick = { onSelect(Route.Hazards) },
                )
            }
        }
    }
}

@Composable
private fun TabItem(
    label: String,
    icon: ImageVector,
    tone: Tone,
    selected: Boolean,
    badge: Int?,
    badgeTone: Tone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) tone.fill else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, tone.edge, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) tone.ink else InkMuted,
                    modifier = Modifier.size(27.dp),
                )
                if (badge != null) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(start = 16.dp)
                            .clip(Pill)
                            .background(badgeTone.strong)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            badge.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTone.onStrong,
                        )
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) tone.ink else InkMuted,
            )
        }
    }
}

/** Kept next to the chrome so avatar tinting is reachable from previews. */
internal fun toneForPerson(id: String): Tone = avatarToneFor(id)
