package com.drishti.dashboard.data

/**
 * What a coordinator is looking at.
 *
 * Everything here is a *fact reported by a device*, never a judgement. Status
 * is not stored on a person — it is derived in [com.drishti.dashboard.domain]
 * from these facts and the current time, so the wall cannot drift out of date
 * while the screen is open and nothing can mark someone "Safe" except the
 * absence of a reason not to.
 */

/** A person enrolled in the walking programme, as the desk sees them. */
data class MonitoredPerson(
    val id: String,
    val name: String,
    /** Dialled through ACTION_DIAL; the operator still taps call themselves. */
    val phoneNumber: String,
    /** Who to reach if the person cannot be reached. May be blank. */
    val emergencyContactName: String,
    val emergencyContactNumber: String,
    val area: String,
    val activity: Activity,
    /** One short line: "Crossing at Nungambakkam High Road". */
    val activityDetail: String,
    val lastUpdateMs: Long,
    val batteryPercent: Int?,
    val helpRequest: HelpRequest?,
    val events: List<SafetyEvent>,
)

/** What the phone says it is doing. Mirrors the walking app's modes. */
enum class Activity {
    WALKING,
    READING_TEXT,
    FINDING_OBJECT,
    ASKING_SCENE,
    RESTING,
    NOT_WALKING,
}

/** A help request raised from the phone, or opened by the desk on their behalf. */
data class HelpRequest(
    val kind: HelpKind,
    val requestedAtMs: Long,
    val locationLabel: String,
    val latitude: Double,
    val longitude: Double,
    /** Null until somebody at the desk takes it. Name, not an id — it is read aloud. */
    val acknowledgedBy: String?,
    val acknowledgedAtMs: Long?,
)

enum class HelpKind {
    /** The person held the SOS gesture. */
    SOS_HELD,

    /** The phone detected a fall-shaped impact and the person did not dismiss it. */
    FALL_SUSPECTED,

    /** The person asked the assistant for help finding their way. */
    LOST,

    /** No frames for long enough that the desk opened a check. */
    NO_RESPONSE,
}

/**
 * Something worth a coordinator's attention after the fact. This is the
 * per-person history: what happened, when, and where.
 */
data class SafetyEvent(
    val id: String,
    val kind: SafetyEventKind,
    val atMs: Long,
    val detail: String,
    val locationLabel: String?,
)

enum class SafetyEventKind {
    SOS_RAISED,
    FALL_SUSPECTED,
    REPEATED_STOP,
    UNSIGNALLED_CROSSING,
    OFF_USUAL_ROUTE,
    SIGNAL_LOST,
    LOW_BATTERY,
    HAZARD_REPORTED,
    WALK_STARTED,
    WALK_ENDED,
}

/**
 * A hazard several people have now walked into.
 *
 * A single report is one person's read of a kerb in bad light. The number that
 * matters to a works department is [reporterCount]: how many *different*
 * people hit the same thing. The UI never lets those two numbers blur.
 */
data class AggregateHazard(
    val id: String,
    val category: HazardCategory,
    val locationLabel: String,
    val severity: HazardSeverity,
    val reportCount: Int,
    val reporterCount: Int,
    val firstReportedMs: Long,
    val lastReportedMs: Long,
    val status: HazardStatus,
    val assignedTo: String?,
    /** Verbatim from one of the reports, for the works order. */
    val note: String,
)

enum class HazardCategory {
    OBSTRUCTED_PATH,
    BROKEN_SURFACE,
    OPEN_DRAIN,
    ROADWORKS,
    PARKED_VEHICLE,
    MISSING_CROSSING_SIGNAL,
    UNMARKED_STEP,
    STANDING_WATER,
}

enum class HazardSeverity { LOW, MEDIUM, HIGH }

enum class HazardStatus { NEW, ASSIGNED, RESOLVED }

/** Everything one refresh of the desk returns. */
data class DeskSnapshot(
    val people: List<MonitoredPerson>,
    val hazards: List<AggregateHazard>,
    /** Where the data came from, shown in the header so it is never in doubt. */
    val source: DataSource,
)

enum class DataSource {
    /** Sample data generated on the phone. No device is reporting. */
    DEMO,

    /** A real desk feed. Nothing produces this yet. */
    LIVE,
}
