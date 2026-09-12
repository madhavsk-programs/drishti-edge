package com.drishti.dashboard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * Light, always.
 *
 * The walking app is dark because its user never looks at the screen. This one
 * is the opposite: it is read in an office, in daylight, next to paper, by
 * someone comparing it against a handwritten roster. Following the system dark
 * setting would give that person a different-looking wall every time their
 * phone crossed sunset, and would halve the contrast of the one thing that
 * matters — the red band at the top.
 *
 * The tinted status fills are also built for a light ground; there is no dark
 * counterpart for them yet, and inventing one that has never been looked at
 * would be worse than not offering it.
 */
@Composable
fun DrishtiDashboardTheme(content: @Composable () -> Unit) {
    val colors = lightColorScheme(
        primary = IndigoTone.strong,
        onPrimary = IndigoTone.onStrong,
        primaryContainer = IndigoTone.fill,
        onPrimaryContainer = IndigoTone.ink,
        secondary = TealTone.strong,
        onSecondary = TealTone.onStrong,
        secondaryContainer = TealTone.fill,
        onSecondaryContainer = TealTone.ink,
        tertiary = PlumTone.strong,
        onTertiary = PlumTone.onStrong,
        tertiaryContainer = PlumTone.fill,
        onTertiaryContainer = PlumTone.ink,
        error = HelpTone.strong,
        onError = HelpTone.onStrong,
        errorContainer = HelpTone.fill,
        onErrorContainer = HelpTone.ink,
        background = Canvas,
        onBackground = InkBody,
        surface = Paper,
        onSurface = InkBody,
        surfaceVariant = Sunken,
        onSurfaceVariant = InkMuted,
        outline = HairlineStrong,
        outlineVariant = Hairline,
    )

    MaterialTheme(
        colorScheme = colors,
        typography = DashboardTypography,
        shapes = DashboardShapes,
    ) {
        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodyLarge,
            content = content,
        )
    }
}

/**
 * Generous radii. Everything on this screen is a physical-feeling card, and at
 * these component sizes a 4dp corner reads as a hairline mistake.
 */
val DashboardShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** A pill: used for status badges and every primary action. */
val Pill = RoundedCornerShape(percent = 50)
