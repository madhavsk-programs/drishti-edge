package com.drishti.app.risk

import com.drishti.app.config.PipelineSettings
import com.drishti.app.net.CorridorChoice
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.RiskLevel

/**
 * Kotlin port of `app/risk/state_machine.py` (BUILD_PLAN.md task A4).
 *
 * This is what stops the system from chattering a direction it is about to
 * reverse. Persistence, decay and cooldown are safety behaviour, not polish.
 */
data class StableDecision(
    val action: GuidanceAction,
    val level: RiskLevel,
    val reasonCode: String,
    val preferredCorridor: CorridorChoice,
    val criticalTrackIds: Set<Int>,
    val speak: Boolean,
    val blockingLabel: String?,
)

class AlertStateMachine(private val settings: PipelineSettings) {

    private var current: ProposedDecision = clearDecision()
    private var pending: ProposedDecision? = null
    private var pendingFrames = 0
    private var clearFrames = 0
    private var lastSpokenAtMillis: Long? = null

    fun apply(proposed: ProposedDecision, nowMillis: Long): StableDecision {
        if (proposed.level == RiskLevel.CRITICAL) {
            resetPending()
            clearFrames = 0
            return commit(proposed, nowMillis, bypassCooldown = true)
        }

        if (proposed.action == GuidanceAction.PAUSE_UNCLEAR) {
            resetPending()
            clearFrames = 0
            return commit(proposed, nowMillis)
        }

        if (proposed.action == GuidanceAction.CLEAR) {
            return applyClear(proposed, nowMillis)
        }

        clearFrames = 0
        if (sameDecision(proposed, current)) {
            resetPending()
            return commit(proposed, nowMillis)
        }

        val currentPending = pending
        if (currentPending != null && sameDecision(proposed, currentPending)) {
            pendingFrames += 1
        } else {
            pending = proposed
            pendingFrames = 1
        }

        if (pendingFrames >= settings.alertPersistenceFrames) {
            resetPending()
            return commit(proposed, nowMillis)
        }

        if (current.action == GuidanceAction.MOVE_LEFT ||
            current.action == GuidanceAction.MOVE_RIGHT
        ) {
            // Already steering and the proposal has changed: say "pause" rather
            // than reverse a direction that has not yet earned persistence.
            val interim = ProposedDecision(
                action = GuidanceAction.PAUSE_UNCLEAR,
                level = RiskLevel.WARN,
                reasonCode = "DIRECTION_CHANGE_PENDING",
                preferredCorridor = CorridorChoice.NONE,
            )
            return commit(interim, nowMillis)
        }
        return stable(
            ProposedDecision(
                action = GuidanceAction.CLEAR,
                level = RiskLevel.WATCH,
                reasonCode = "ALERT_PERSISTENCE_PENDING",
                preferredCorridor = CorridorChoice.NONE,
            ),
            speak = false,
        )
    }

    private fun applyClear(proposed: ProposedDecision, nowMillis: Long): StableDecision {
        resetPending()
        if (current.action == GuidanceAction.CLEAR) {
            clearFrames = 0
            return commit(proposed, nowMillis)
        }
        if (proposed.evidenceScore >= settings.riskWarnExit) {
            clearFrames = 0
            return stable(
                ProposedDecision(
                    action = GuidanceAction.CAUTION,
                    level = RiskLevel.WATCH,
                    reasonCode = "RISK_HYSTERESIS_ACTIVE",
                    preferredCorridor = CorridorChoice.CENTRE,
                    evidenceScore = proposed.evidenceScore,
                ),
                speak = false,
            )
        }
        clearFrames += 1
        if (clearFrames >= settings.alertClearFrames) {
            clearFrames = 0
            return commit(proposed, nowMillis)
        }
        return stable(
            ProposedDecision(
                action = GuidanceAction.CAUTION,
                level = RiskLevel.WATCH,
                reasonCode = "RISK_DECAY_PENDING",
                preferredCorridor = CorridorChoice.CENTRE,
            ),
            speak = false,
        )
    }

    private fun commit(
        proposed: ProposedDecision,
        nowMillis: Long,
        bypassCooldown: Boolean = false,
    ): StableDecision {
        val changed = !sameDecision(proposed, current)
        val increased = levelRank(proposed.level) > levelRank(current.level)
        val lastSpoken = lastSpokenAtMillis
        val cooldownElapsed = lastSpoken == null ||
            (nowMillis - lastSpoken) / 1000.0 >= settings.alertCooldownSeconds
        val hasMessage = proposed.action != GuidanceAction.CLEAR
        var speak = hasMessage && (changed || increased || (cooldownElapsed && !bypassCooldown))
        if (bypassCooldown && (changed || increased)) {
            speak = true
        }
        current = proposed
        if (speak) {
            lastSpokenAtMillis = nowMillis
        }
        return stable(proposed, speak = speak)
    }

    private fun resetPending() {
        pending = null
        pendingFrames = 0
    }
}

private fun clearDecision(): ProposedDecision = ProposedDecision(
    action = GuidanceAction.CLEAR,
    level = RiskLevel.CLEAR,
    reasonCode = "PATH_CLEAR",
    preferredCorridor = CorridorChoice.CENTRE,
)

private fun sameDecision(left: ProposedDecision, right: ProposedDecision): Boolean =
    left.action == right.action &&
        left.level == right.level &&
        left.preferredCorridor == right.preferredCorridor &&
        left.reasonCode == right.reasonCode

private fun stable(decision: ProposedDecision, speak: Boolean): StableDecision = StableDecision(
    action = decision.action,
    level = decision.level,
    reasonCode = decision.reasonCode,
    preferredCorridor = decision.preferredCorridor,
    criticalTrackIds = decision.criticalTrackIds,
    speak = speak,
    blockingLabel = decision.blockingLabel,
)

private fun levelRank(level: RiskLevel): Int = when (level) {
    RiskLevel.CLEAR -> 0
    RiskLevel.WATCH -> 1
    RiskLevel.WARN -> 2
    RiskLevel.HIGH -> 3
    RiskLevel.CRITICAL -> 4
}
