package com.drishti.app.feedback

import android.content.Context
import android.content.res.Configuration
import com.drishti.app.R
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.TargetGuidanceStep
import com.drishti.app.net.TargetTrackingState
import com.drishti.app.net.TargetTrackingTelemetry
import com.drishti.app.net.GuidanceContract

/**
 * Turns a backend [GuidanceContract] into a phrase in the user's chosen spoken
 * language. The backend only sends English `speech`; for Hindi/Tamil we resolve
 * our own localized resources keyed by `action`, refined by `reason_code` for a
 * few high-value cases. English output matches the contract wording.
 */
class GuidanceStrings(appContext: Context) {

    private val base = appContext.applicationContext
    @Volatile private var localized: Context = base

    fun setLanguage(language: SpokenLanguage) {
        val config = Configuration(base.resources.configuration)
        config.setLocale(language.locale)
        localized = base.createConfigurationContext(config)
    }

    /**
     * Phrase to speak, or null when nothing should be spoken (CLEAR / empty).
     *
     * When the verdict is about one identified object, its name goes into the
     * line. "Stop" makes a blind user halt and then stand there working out
     * why; "stop, a chair directly ahead" lets them reach for it, step round it
     * or recognise where they are. The name is only used when the detector
     * actually produced one -- a surface-only verdict keeps the generic wording
     * rather than inventing a culprit.
     */
    fun speechFor(guidance: GuidanceContract): String? {
        val ctx = localized
        val what = obstacleName(guidance.blockingLabel)
        return when (guidance.reasonCode) {
            "WALL_OR_DEAD_END_AHEAD" -> ctx.getString(R.string.guide_wall)
            "APPROACHING_VEHICLE_CENTRE" -> ctx.getString(R.string.guide_vehicle)
            "ALL_CORRIDORS_BLOCKED" ->
                if (what != null) ctx.getString(R.string.guide_all_blocked_named, what)
                else ctx.getString(R.string.guide_all_blocked)
            "CENTRE_SURFACE_UNCERTAIN" -> ctx.getString(R.string.guide_surface_uncertain)
            "STAIRS_OR_LEVEL_CHANGE_AHEAD" -> ctx.getString(R.string.guide_stairs)
            else -> when (guidance.action) {
                GuidanceAction.CLEAR -> null
                GuidanceAction.CAUTION ->
                    if (what != null) ctx.getString(R.string.guide_caution_named, what)
                    else ctx.getString(R.string.guide_caution)
                GuidanceAction.MOVE_LEFT ->
                    if (what != null) ctx.getString(R.string.guide_move_left_named, what)
                    else ctx.getString(R.string.guide_move_left)
                GuidanceAction.MOVE_RIGHT ->
                    if (what != null) ctx.getString(R.string.guide_move_right_named, what)
                    else ctx.getString(R.string.guide_move_right)
                GuidanceAction.STOP ->
                    if (what != null) ctx.getString(R.string.guide_stop_named, what)
                    else ctx.getString(R.string.guide_stop)
                GuidanceAction.PAUSE_UNCLEAR -> ctx.getString(R.string.guide_pause_unclear)
            }
        }
    }

    /**
     * Spoken name for an audited detector label, in the user's language.
     *
     * An explicit map rather than a generated resource name: the label set is
     * the audited allowlist, and a label that gains no name here reads as an
     * unnamed obstacle instead of being announced by its raw COCO spelling.
     */
    fun obstacleName(label: String?): String? {
        val resId = when (label) {
            "person" -> R.string.obstacle_person
            "chair" -> R.string.obstacle_chair
            "bag" -> R.string.obstacle_bag
            "desk" -> R.string.obstacle_desk
            "bicycle" -> R.string.obstacle_bicycle
            "motorcycle" -> R.string.obstacle_motorcycle
            "car" -> R.string.obstacle_car
            "bus" -> R.string.obstacle_bus
            "truck" -> R.string.obstacle_truck
            "train" -> R.string.obstacle_train
            "dog" -> R.string.obstacle_dog
            "fire hydrant" -> R.string.obstacle_fire_hydrant
            "stop sign" -> R.string.obstacle_stop_sign
            "parking meter" -> R.string.obstacle_parking_meter
            "traffic light" -> R.string.obstacle_traffic_light
            "bench" -> R.string.obstacle_bench
            "door" -> R.string.obstacle_door
            "suitcase" -> R.string.obstacle_suitcase
            "umbrella" -> R.string.obstacle_umbrella
            "potted plant" -> R.string.obstacle_potted_plant
            "couch" -> R.string.obstacle_couch
            "bed" -> R.string.obstacle_bed
            "tv" -> R.string.obstacle_tv
            "refrigerator" -> R.string.obstacle_refrigerator
            "sink" -> R.string.obstacle_sink
            "toilet" -> R.string.obstacle_toilet
            "laptop" -> R.string.obstacle_laptop
            "bottle" -> R.string.obstacle_bottle
            "cup" -> R.string.obstacle_cup
            "bowl" -> R.string.obstacle_bowl
            "vase" -> R.string.obstacle_vase
            "book" -> R.string.obstacle_book
            "keyboard" -> R.string.obstacle_keyboard
            "skateboard" -> R.string.obstacle_skateboard
            "sports ball" -> R.string.obstacle_sports_ball
            "microwave" -> R.string.obstacle_microwave
            "oven" -> R.string.obstacle_oven
            "toaster" -> R.string.obstacle_toaster
            else -> return null
        }
        return localized.getString(resId)
    }

    /**
     * Short human-readable "why" for the current guidance — shown under the
     * banner so a low-vision user can see *why* it says STOP / move / pause.
     * Null when the path is clear.
     */
    fun reasonText(guidance: GuidanceContract): String? {
        val ctx = localized
        val what = obstacleName(guidance.blockingLabel)
        fun named(namedId: Int, plainId: Int): String =
            if (what != null) ctx.getString(namedId, what) else ctx.getString(plainId)
        return when (guidance.reasonCode) {
            "WALL_OR_DEAD_END_AHEAD" -> ctx.getString(R.string.reason_wall)
            "APPROACHING_VEHICLE_CENTRE" -> ctx.getString(R.string.reason_vehicle)
            "ALL_CORRIDORS_BLOCKED" ->
                named(R.string.reason_all_blocked_named, R.string.reason_all_blocked)
            "CENTRE_BLOCKED_CLEARER_SIDE" ->
                named(
                    R.string.reason_centre_blocked_side_named,
                    R.string.reason_centre_blocked_side,
                )
            "CENTRE_BLOCKED_DIRECTION_UNCLEAR" ->
                named(
                    R.string.reason_centre_blocked_unclear_named,
                    R.string.reason_centre_blocked_unclear,
                )
            "CENTRE_SURFACE_UNCERTAIN" -> ctx.getString(R.string.reason_surface_uncertain)
            "STAIRS_OR_LEVEL_CHANGE_AHEAD" -> ctx.getString(R.string.reason_stairs)
            "OBSTACLE_NEARBY" ->
                named(R.string.reason_obstacle_nearby_named, R.string.reason_obstacle_nearby)
            "LOW_RISK_MONITORED" -> ctx.getString(R.string.reason_low_risk)
            // Emitted with action = CLEAR while evidence persists. The CLEAR
            // suppresses speech; it does not mean the path is clear, and the
            // banner says CAUTION, so the subtitle has to explain the hold.
            "ALERT_PERSISTENCE_PENDING" -> ctx.getString(R.string.reason_checking)
            "PATH_CLEAR" -> null
            else -> when (guidance.action) {
                GuidanceAction.CLEAR -> null
                GuidanceAction.STOP ->
                    named(R.string.reason_obstacle_ahead_named, R.string.reason_obstacle_ahead)
                GuidanceAction.MOVE_LEFT, GuidanceAction.MOVE_RIGHT ->
                    named(
                        R.string.reason_centre_blocked_side_named,
                        R.string.reason_centre_blocked_side,
                    )
                GuidanceAction.PAUSE_UNCLEAR -> ctx.getString(R.string.reason_surface_uncertain)
                GuidanceAction.CAUTION ->
                    named(R.string.reason_obstacle_nearby_named, R.string.reason_obstacle_nearby)
            }
        }
    }

    fun string(resId: Int, vararg args: Any): String = localized.getString(resId, *args)

    /**
     * The spoken line for a target-guidance step (ARCHITECTURE.md §14.3).
     * Chosen here, not in the guidance engine, so it follows the spoken
     * language setting like every other line.
     */
    fun targetLine(tt: TargetTrackingTelemetry): String? {
        val side = localized.getString(
            if ((tt.bearingDegrees ?: 0.0) > 0) R.string.side_right else R.string.side_left,
        )
        return when (tt.guidanceStep) {
            TargetGuidanceStep.TURN_LEFT, TargetGuidanceStep.TURN_RIGHT ->
                localized.getString(R.string.target_turn, side)
            TargetGuidanceStep.KEEP_TURNING -> localized.getString(R.string.target_keep_turning, side)
            TargetGuidanceStep.FACE_AND_WALK -> localized.getString(R.string.target_face_and_walk)
            TargetGuidanceStep.WALKING -> localized.getString(R.string.target_walking)
            TargetGuidanceStep.ARRIVED -> localized.getString(R.string.target_arrived)
            TargetGuidanceStep.REACQUIRE ->
                if (tt.trackingState == TargetTrackingState.LOST) {
                    localized.getString(R.string.target_lost)
                } else {
                    localized.getString(R.string.target_reacquire)
                }
            TargetGuidanceStep.NONE -> null
        }
    }

    /**
     * Lower-cased leading phrases that turn a spoken request into an
     * "Ask -> Lock" target instead of a Scene-Mode question. Locale-specific;
     * each item keeps its trailing space so `startsWith` leaves a clean name.
     */
    fun locatePrefixes(): List<String> =
        localized.resources.getStringArray(R.array.locate_prefixes)
            .map { it.lowercase() }
}
