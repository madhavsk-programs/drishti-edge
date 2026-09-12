package com.drishti.dashboard.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drishti.dashboard.ui.theme.Hairline
import com.drishti.dashboard.ui.theme.InkMuted
import com.drishti.dashboard.ui.theme.InkStrong
import com.drishti.dashboard.ui.theme.NumeralStyle
import com.drishti.dashboard.ui.theme.Paper
import com.drishti.dashboard.ui.theme.Pill
import com.drishti.dashboard.ui.theme.Tone
import com.drishti.dashboard.ui.theme.avatarToneFor

/**
 * The shared pieces. Every screen is built out of these so that "a card" means
 * one thing throughout, and so a change of mind about corners or shadows is a
 * change in one file.
 *
 * Sizes here are deliberately large: 64dp buttons, 72dp avatars, 20-24dp
 * padding. This is a glance-and-act tool, sometimes one-handed, sometimes by
 * someone who is also holding a phone to their ear.
 */

/** The standard white card: soft shadow, hairline edge, generous radius. */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    border: Color = Hairline,
    background: Color = Paper,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        color = background,
        shape = shape,
        border = BorderStroke(1.dp, border),
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = shape,
                ambientColor = Color(0x14312A22),
                spotColor = Color(0x1A312A22),
            )
            .then(clickable),
        content = { content() },
    )
}

/**
 * A card with a colour rail down its left edge.
 *
 * The rail is how the wall is scanned: four cards down, the eye finds the red
 * one before it has read a single word.
 */
@Composable
fun RailCard(
    tone: Tone,
    modifier: Modifier = Modifier,
    railWidth: androidx.compose.ui.unit.Dp = 8.dp,
    background: Color = Paper,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    PaperCard(
        modifier = modifier,
        border = tone.edge,
        background = background,
        onClick = onClick,
    ) {
        // IntrinsicSize.Min lets the rail take the height the content settles
        // on; a fixed height would clip a two-line name.
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(railWidth)
                    .fillMaxHeight()
                    .background(tone.strong),
            )
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** A heading with a coloured eyebrow above it. */
@Composable
fun SectionHeading(
    eyebrow: String,
    title: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = eyebrow.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = tone.ink,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = InkStrong,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

/** A status or category pill: dot, word, tinted ground. */
@Composable
fun TonePill(
    label: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    showDot: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(Pill)
            .background(tone.fill)
            .border(1.dp, tone.edge, Pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(18.dp))
        } else if (showDot) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(Pill)
                    .background(tone.strong),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tone.ink,
            maxLines = 1,
        )
    }
}

/** A loud pill for the one thing on screen that must not be missed. */
@Composable
fun SolidPill(
    label: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .clip(Pill)
            .background(tone.strong)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tone.onStrong, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tone.onStrong,
            maxLines = 1,
        )
    }
}

/** Initials in a tinted block. Colour is stable per person id. */
@Composable
fun Avatar(
    name: String,
    id: String,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 64.dp,
) {
    val tone = avatarToneFor(id)
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 32))
            .background(tone.fill)
            .border(1.5.dp, tone.edge, RoundedCornerShape(percent = 32))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsOf(name),
            style = MaterialTheme.typography.headlineSmall,
            color = tone.ink,
        )
    }
}

internal fun initialsOf(name: String): String {
    val parts = name.trim().split(" ").filter { it.isNotBlank() }
    return when (parts.size) {
        0 -> "?"
        1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}

/** The big filled action: 64dp tall, pill, impossible to mis-tap. */
@Composable
fun BigAction(
    label: String,
    tone: Tone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        color = if (enabled) tone.strong else tone.fill,
        shape = Pill,
        modifier = modifier
            .defaultMinSize(minHeight = 64.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) tone.onStrong else tone.ink,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) tone.onStrong else tone.ink,
                maxLines = 1,
            )
        }
    }
}

/** The quieter sibling: same size, tinted rather than filled. */
@Composable
fun QuietAction(
    label: String,
    tone: Tone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Surface(
        color = tone.fill,
        shape = Pill,
        border = BorderStroke(1.5.dp, tone.edge),
        modifier = modifier
            .defaultMinSize(minHeight = 64.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(24.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = tone.ink,
                maxLines = 1,
            )
        }
    }
}

/** One of the counts along the top of a screen. */
@Composable
fun CountTile(
    count: Int,
    label: String,
    tone: Tone,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        color = if (selected) tone.strong else tone.fill,
        shape = shape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) tone.strong else tone.edge),
        // A floor, so a two-line caption next to a one-line caption does not
        // leave the row of tiles ragged.
        modifier = modifier
            .heightIn(min = 122.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                text = count.toString(),
                style = NumeralStyle,
                color = if (selected) tone.onStrong else tone.ink,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) tone.onStrong else tone.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Label above, value below — the unit a detail sheet is made of. */
@Composable
fun FactRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = InkStrong,
    icon: ImageVector? = null,
    iconTint: Color = InkMuted,
) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        // The gutter is reserved whether or not there is an icon, so a row
        // without one still lines up with the rows above it.
        Box(
            Modifier
                .padding(top = 4.dp, end = 14.dp)
                .size(22.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = InkMuted,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = valueColor,
            )
        }
    }
}

/** A hairline divider that matches the card edges. */
@Composable
fun Rule(modifier: Modifier = Modifier, color: Color = Hairline) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}

/**
 * Four tiles in two columns. Two rows of two, never a strip that slides:
 * on a phone in portrait the third and fourth counts would otherwise be
 * off-screen, and "one of the four numbers is hidden" is the wrong property
 * for a status wall to have.
 */
@Composable
fun TileGrid(
    modifier: Modifier = Modifier,
    gap: androidx.compose.ui.unit.Dp = 10.dp,
    tiles: List<@Composable (Modifier) -> Unit>,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                pair.forEach { tile -> tile(Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
