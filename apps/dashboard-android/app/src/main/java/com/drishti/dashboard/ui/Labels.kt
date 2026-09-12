package com.drishti.dashboard.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import com.drishti.dashboard.data.Activity
import com.drishti.dashboard.data.HazardCategory
import com.drishti.dashboard.data.HazardSeverity
import com.drishti.dashboard.data.HazardStatus
import com.drishti.dashboard.data.HelpKind
import com.drishti.dashboard.data.SafetyEventKind
import com.drishti.dashboard.domain.LiveStatus
import com.drishti.dashboard.ui.theme.AttentionTone
import com.drishti.dashboard.ui.theme.ClayTone
import com.drishti.dashboard.ui.theme.HelpTone
import com.drishti.dashboard.ui.theme.IndigoTone
import com.drishti.dashboard.ui.theme.OfflineTone
import com.drishti.dashboard.ui.theme.PlumTone
import com.drishti.dashboard.ui.theme.SafeTone
import com.drishti.dashboard.ui.theme.SlateTone
import com.drishti.dashboard.ui.theme.TealTone
import com.drishti.dashboard.ui.theme.Tone

/**
 * Every enum's words, colour and glyph, in one place.
 *
 * Wording is the product here as much as the layout is. "Needs attention" is
 * not "warning"; "No signal" is not "offline device"; a hazard nobody has
 * confirmed says so in the pill rather than being silently ranked lower. If
 * two screens ever disagree about what a state is called, it is because
 * somebody wrote a string outside this file.
 */

// ---- live status ---------------------------------------------------------

fun LiveStatus.label(): String = when (this) {
    LiveStatus.HELP_REQUIRED -> "Help required"
    LiveStatus.NEEDS_ATTENTION -> "Needs attention"
    LiveStatus.OFFLINE -> "No signal"
    LiveStatus.SAFE -> "Safe"
}

fun LiveStatus.tone(): Tone = when (this) {
    LiveStatus.HELP_REQUIRED -> HelpTone
    LiveStatus.NEEDS_ATTENTION -> AttentionTone
    LiveStatus.OFFLINE -> OfflineTone
    LiveStatus.SAFE -> SafeTone
}

fun LiveStatus.icon(): ImageVector = when (this) {
    LiveStatus.HELP_REQUIRED -> Icons.Rounded.Warning
    LiveStatus.NEEDS_ATTENTION -> Icons.Rounded.Info
    LiveStatus.OFFLINE -> Icons.Rounded.Clear
    LiveStatus.SAFE -> Icons.Rounded.CheckCircle
}

/** The tile caption: plural, and honest when the count is zero. */
fun LiveStatus.tileLabel(): String = when (this) {
    LiveStatus.HELP_REQUIRED -> "Help required"
    LiveStatus.NEEDS_ATTENTION -> "Need a check"
    LiveStatus.OFFLINE -> "No signal"
    LiveStatus.SAFE -> "Safe"
}

// ---- activity ------------------------------------------------------------

fun Activity.label(): String = when (this) {
    Activity.WALKING -> "Walking"
    Activity.READING_TEXT -> "Reading text"
    Activity.FINDING_OBJECT -> "Finding something"
    Activity.ASKING_SCENE -> "Asking about the scene"
    Activity.RESTING -> "Stopped"
    Activity.NOT_WALKING -> "Not walking"
}

fun Activity.icon(): ImageVector = when (this) {
    Activity.WALKING -> Icons.AutoMirrored.Rounded.ArrowForward
    Activity.READING_TEXT -> Icons.AutoMirrored.Rounded.List
    Activity.FINDING_OBJECT -> Icons.Rounded.Search
    Activity.ASKING_SCENE -> Icons.Rounded.Face
    Activity.RESTING -> Icons.Rounded.Info
    Activity.NOT_WALKING -> Icons.Rounded.Person
}

// ---- help requests -------------------------------------------------------

fun HelpKind.label(): String = when (this) {
    HelpKind.SOS_HELD -> "SOS pressed"
    HelpKind.FALL_SUSPECTED -> "Possible fall"
    HelpKind.LOST -> "Lost the way"
    HelpKind.NO_RESPONSE -> "Desk check — phone silent"
}

/**
 * What actually happened, in the words the operator will repeat on the phone.
 * A possible fall is *possible*: the phone detected an impact, nothing more,
 * and the sentence says so rather than telling a coordinator someone fell.
 */
fun HelpKind.explanation(): String = when (this) {
    HelpKind.SOS_HELD -> "They held the SOS gesture on their phone."
    HelpKind.FALL_SUSPECTED ->
        "Their phone detected an impact and the check prompt was not dismissed. They may have fallen."
    HelpKind.LOST -> "They asked the assistant for help finding their way."
    HelpKind.NO_RESPONSE ->
        "The desk opened this because the phone stopped reporting. The person has not asked for help."
}

// ---- safety events -------------------------------------------------------

fun SafetyEventKind.label(): String = when (this) {
    SafetyEventKind.SOS_RAISED -> "SOS raised"
    SafetyEventKind.FALL_SUSPECTED -> "Possible fall"
    SafetyEventKind.REPEATED_STOP -> "Repeated stops"
    SafetyEventKind.UNSIGNALLED_CROSSING -> "Crossed without a signal"
    SafetyEventKind.OFF_USUAL_ROUTE -> "Off the usual route"
    SafetyEventKind.SIGNAL_LOST -> "Signal lost"
    SafetyEventKind.LOW_BATTERY -> "Battery low"
    SafetyEventKind.HAZARD_REPORTED -> "Reported a hazard"
    SafetyEventKind.WALK_STARTED -> "Walk started"
    SafetyEventKind.WALK_ENDED -> "Walk ended"
}

fun SafetyEventKind.tone(): Tone = when (this) {
    SafetyEventKind.SOS_RAISED, SafetyEventKind.FALL_SUSPECTED -> HelpTone
    SafetyEventKind.REPEATED_STOP,
    SafetyEventKind.UNSIGNALLED_CROSSING,
    SafetyEventKind.LOW_BATTERY,
    -> AttentionTone

    SafetyEventKind.SIGNAL_LOST, SafetyEventKind.OFF_USUAL_ROUTE -> OfflineTone
    SafetyEventKind.HAZARD_REPORTED -> ClayTone
    SafetyEventKind.WALK_STARTED, SafetyEventKind.WALK_ENDED -> SlateTone
}

fun SafetyEventKind.icon(): ImageVector = when (this) {
    SafetyEventKind.SOS_RAISED, SafetyEventKind.FALL_SUSPECTED -> Icons.Rounded.Warning
    SafetyEventKind.REPEATED_STOP -> Icons.Rounded.Info
    SafetyEventKind.UNSIGNALLED_CROSSING -> Icons.Rounded.Warning
    SafetyEventKind.OFF_USUAL_ROUTE -> Icons.Rounded.LocationOn
    SafetyEventKind.SIGNAL_LOST -> Icons.Rounded.Clear
    SafetyEventKind.LOW_BATTERY -> Icons.Rounded.Info
    SafetyEventKind.HAZARD_REPORTED -> Icons.Rounded.Build
    SafetyEventKind.WALK_STARTED -> Icons.Rounded.Star
    SafetyEventKind.WALK_ENDED -> Icons.Rounded.CheckCircle
}

// ---- hazards -------------------------------------------------------------

fun HazardCategory.label(): String = when (this) {
    HazardCategory.OBSTRUCTED_PATH -> "Path blocked"
    HazardCategory.BROKEN_SURFACE -> "Broken surface"
    HazardCategory.OPEN_DRAIN -> "Open drain"
    HazardCategory.ROADWORKS -> "Roadworks"
    HazardCategory.PARKED_VEHICLE -> "Vehicle on the footpath"
    HazardCategory.MISSING_CROSSING_SIGNAL -> "Crossing signal silent"
    HazardCategory.UNMARKED_STEP -> "Unmarked step"
    HazardCategory.STANDING_WATER -> "Standing water"
}

fun HazardCategory.tone(): Tone = when (this) {
    HazardCategory.OPEN_DRAIN, HazardCategory.UNMARKED_STEP -> ClayTone
    HazardCategory.BROKEN_SURFACE, HazardCategory.OBSTRUCTED_PATH -> PlumTone
    HazardCategory.ROADWORKS, HazardCategory.PARKED_VEHICLE -> IndigoTone
    HazardCategory.MISSING_CROSSING_SIGNAL, HazardCategory.STANDING_WATER -> TealTone
}

fun HazardSeverity.label(): String = when (this) {
    HazardSeverity.LOW -> "Low"
    HazardSeverity.MEDIUM -> "Medium"
    HazardSeverity.HIGH -> "High"
}

fun HazardSeverity.tone(): Tone = when (this) {
    HazardSeverity.LOW -> SlateTone
    HazardSeverity.MEDIUM -> AttentionTone
    HazardSeverity.HIGH -> HelpTone
}

fun HazardStatus.label(): String = when (this) {
    HazardStatus.NEW -> "Not reported yet"
    HazardStatus.ASSIGNED -> "With the council"
    HazardStatus.RESOLVED -> "Fixed"
}

fun HazardStatus.tone(): Tone = when (this) {
    HazardStatus.NEW -> ClayTone
    HazardStatus.ASSIGNED -> IndigoTone
    HazardStatus.RESOLVED -> SafeTone
}

fun HazardStatus.icon(): ImageVector = when (this) {
    HazardStatus.NEW -> Icons.Rounded.Warning
    HazardStatus.ASSIGNED -> Icons.Rounded.Refresh
    HazardStatus.RESOLVED -> Icons.Rounded.CheckCircle
}

/** The call action's glyph, kept here so the three screens agree. */
val CallIcon: ImageVector = Icons.Rounded.Call
