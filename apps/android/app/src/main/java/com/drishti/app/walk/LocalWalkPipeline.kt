package com.drishti.app.walk

import android.util.Log
import com.drishti.app.config.PipelineSettings
import com.drishti.app.inference.InferenceBackend
import com.drishti.app.inference.Letterbox
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.inference.OnDeviceDetector
import com.drishti.app.inference.OrientedFrame
import com.drishti.app.net.DetectionResult
import com.drishti.app.net.DirectionArrow
import com.drishti.app.net.DisplayColor
import com.drishti.app.net.FrameAnalysisResponse
import com.drishti.app.net.FrameGeometry
import com.drishti.app.net.GuidanceAction
import com.drishti.app.net.GuidanceContract
import com.drishti.app.net.HapticPattern
import com.drishti.app.net.MotionVector
import com.drishti.app.net.NormalizedBoundingBox
import com.drishti.app.net.NormalizedPoint
import com.drishti.app.net.OverlayContract
import com.drishti.app.net.RiskLevel
import com.drishti.app.net.StageTimings
import com.drishti.app.perception.DetectionCandidate
import com.drishti.app.perception.LandmarkMemory
import com.drishti.app.perception.SessionTracker
import com.drishti.app.risk.AlertStateMachine
import com.drishti.app.risk.scoreTracks
import com.drishti.app.risk.selectAction
import com.drishti.app.scene.TargetGuidance
import com.drishti.app.spatial.SurfaceEvidence
import com.drishti.app.spatial.SurfaceEvidenceBuilder
import com.drishti.app.spatial.analyzeCorridors
import com.drishti.app.net.CorridorCosts as CorridorCostsDto
import java.time.Instant

/**
 * The walking loop, entirely on the phone (BUILD_PLAN.md task A6).
 *
 * This replaces the FastAPI round-trip. It deliberately emits the SAME
 * [FrameAnalysisResponse] shape the backend used to return, so the overlay,
 * speech, haptics and spatial audio downstream of it are untouched — one seam
 * changes, not the client (ARCHITECTURE.md §2.2).
 *
 * Not thread-safe by design: the tracker and the state machine carry per-session
 * history and MUST see frames in order. [WalkController] serialises calls.
 */
class LocalWalkPipeline(
    private val detector: OnDeviceDetector,
    private val settings: PipelineSettings = PipelineSettings(),
    private val segmenter: SegFormerSegmenter? = null,
    /**
     * Segmentation runs every Nth frame. Surfaces change far more slowly than
     * the obstacles in front of them, so reusing the last map between runs
     * costs little accuracy and keeps detection at full rate. On the CPU rung
     * segmentation is ~10x the cost of detection, so this is what keeps the
     * guidance cadence usable (ARCHITECTURE.md §17.3).
     */
    private val segmentationStride: Int = 1,
) {
    private var lastSurfaces: SurfaceEvidence? = null
    private var lastSegmentationMillis: Double? = null
    private val tracker = SessionTracker(
        iouThreshold = settings.trackIouThreshold,
        centreDistanceThreshold = settings.trackCentreDistanceThreshold,
        maxAgeFrames = settings.trackMaxAgeFrames,
    )
    private val stateMachine = AlertStateMachine(settings)

    /**
     * Find, on device (ARCHITECTURE.md §14). [landmarks] is fed every frame
     * from the full COCO view; [targetGuidance] steps once per frame after the
     * safety verdict so a target cue can never outrank it. Both are scoped to
     * this pipeline, i.e. to the walk session, and die with it.
     */
    val landmarks = LandmarkMemory(cameraHfovDegrees = TargetGuidance.WALK_CAMERA_HFOV_DEGREES)
    val targetGuidance = TargetGuidance(cameraHfovDegrees = TargetGuidance.WALK_CAMERA_HFOV_DEGREES)

    /** The last frame's full COCO view, for a locate against the live scene. */
    @Volatile
    var lastFullView: List<DetectionCandidate> = emptyList()
        private set

    @Volatile
    var lastFrameMillis: Long = 0L
        private set

    /** Surfaced in diagnostics; the §6.2 evidence set reads this. */
    val backend: InferenceBackend get() = detector.backend
    val detail: String get() = detector.detail

    @Volatile
    var lastInferenceMillis: Double = 0.0
        private set

    fun process(
        frame: OrientedFrame,
        sessionId: String,
        riskSensitivity: Double,
        hapticsEnabled: Boolean,
        headingDegrees: Double? = null,
    ): FrameAnalysisResponse {
        val started = System.nanoTime()
        val outcome = detector.detect(frame)
        lastInferenceMillis = outcome.stats.inferenceMillis

        // Landmark memory reads the full COCO view straight off the detector,
        // before tracking or scoring: a label says an object is present, which
        // is all Find needs, and is not evidence that it obstructs the path.
        landmarks.observe(frame.capturedAtMillis, headingDegrees, outcome.detections.all)
        lastFullView = outcome.detections.all
        lastFrameMillis = frame.capturedAtMillis

        val tracked = tracker.update(
            detections = outcome.detections.risk,
            frameId = frame.frameId,
            capturedAtMillis = frame.capturedAtMillis,
        )
        val trackingEnd = System.nanoTime()

        // Surfaces are absent rather than assumed clear when segmentation is
        // unavailable: every corridor then reads UNCERTAIN and the cascade
        // answers PAUSE_UNCLEAR instead of inventing a direction
        // (docs/SAFETY_RULES.md).
        val surfaces = updateSurfaces(frame)
        val corridor = analyzeCorridors(tracked, settings, surfaces = surfaces)
        val spatialEnd = System.nanoTime()

        val assessments = scoreTracks(corridor.tracks, settings, riskSensitivity)
        val proposed = selectAction(assessments, corridor, settings)
        val stable = stateMachine.apply(proposed, nowMillis = frame.capturedAtMillis)
        val riskEnd = System.nanoTime()

        // Safety has already decided. Anything other than CLEAR overrides the
        // target cue for this frame; it is dropped, not queued (§13.4).
        val targetTracking = targetGuidance.step(
            nowMs = frame.capturedAtMillis,
            headingDegrees = headingDegrees,
            detections = outcome.detections.all,
            isSafetyOverridden = stable.action != GuidanceAction.CLEAR,
            hapticsEnabled = hapticsEnabled,
        )

        val detections = assessments.map { assessment ->
            val spatial = assessment.spatial
            val detection = spatial.tracked.detection
            DetectionResult(
                trackId = spatial.tracked.trackId,
                label = detection.label,
                confidence = detection.confidence,
                bbox = NormalizedBoundingBox(
                    x1 = detection.x1,
                    y1 = detection.y1,
                    x2 = detection.x2,
                    y2 = detection.y2,
                ),
                anchor = NormalizedPoint(
                    x = (detection.x1 + detection.x2) / 2,
                    y = detection.y2,
                ),
                direction = spatial.direction,
                proximity = spatial.proximity.band,
                proximityScore = spatial.proximity.score,
                approachState = assessment.approachState,
                approachRate = spatial.tracked.approachRate,
                motionVector = spatial.tracked.motionDx?.let { dx ->
                    spatial.tracked.motionDy?.let { dy -> MotionVector(dx, dy) }
                },
                pathOverlap = spatial.pathOverlap,
                riskScore = assessment.score,
                riskLevel = assessment.level,
                displayColor = displayColor(assessment.level),
            )
        }

        val now = Instant.ofEpochMilli(frame.capturedAtMillis)
        val totalMs = (riskEnd - started) / 1_000_000.0

        return FrameAnalysisResponse(
            schemaVersion = SCHEMA_VERSION,
            serverTime = now.toString(),
            sessionId = sessionId,
            frameId = frame.frameId,
            capturedAt = now.toString(),
            receivedAt = now.toString(),
            processedAt = now.toString(),
            frameAgeMs = 0.0,
            geometry = FrameGeometry(
                coordinateSpace = COORDINATE_SPACE,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                // The frame handed to the detector is already upright, so the
                // overlay must not rotate it again.
                rotationDegrees = 0,
                mirrored = false,
            ),
            detections = detections,
            surfaces = emptyList(),
            corridors = CorridorCostsDto(
                leftCost = corridor.costs.leftCost,
                centreCost = corridor.costs.centreCost,
                rightCost = corridor.costs.rightCost,
            ),
            overlay = OverlayContract(
                coordinateSpace = COORDINATE_SPACE,
                preferredCorridor = stable.preferredCorridor,
                safePolygons = corridor.safePolygons.map { it.toNormalized() },
                blockedPolygons = corridor.blockedPolygons.map { it.toNormalized() },
                uncertainPolygons = corridor.uncertainPolygons.map { it.toNormalized() },
                directionArrow = directionArrow(stable.action),
                validUntil = now.plusMillis(OVERLAY_VALID_MS).toString(),
            ),
            guidance = GuidanceContract(
                level = stable.level,
                action = stable.action,
                // The spoken words are chosen on-device by GuidanceStrings from
                // the reason code, so this field stays empty rather than
                // carrying an untranslated duplicate.
                speech = "",
                hapticPattern = if (hapticsEnabled) {
                    hapticFor(stable.action)
                } else {
                    HapticPattern.NONE
                },
                speak = stable.speak,
                reasonCode = stable.reasonCode,
            ),
            targetTracking = targetTracking,
            timings = StageTimings(
                decodeMs = outcome.stats.preprocessMillis,
                detectionMs = outcome.stats.inferenceMillis,
                segmentationMs = lastSegmentationMillis,
                trackingDepthMs = (trackingEnd - started) / 1_000_000.0,
                spatialMs = (spatialEnd - trackingEnd) / 1_000_000.0,
                riskMs = (riskEnd - spatialEnd) / 1_000_000.0,
                totalMs = totalMs,
            ),
            // Says so only when it is actually true, so the banner stays
            // meaningful rather than becoming permanent furniture.
            degradedModules = if (surfaces == null) listOf("segmentation") else emptyList(),
        )
    }

    /**
     * Re-segment on stride frames, otherwise reuse the last map. Returns null
     * only when segmentation is genuinely unavailable — never a zeroed map,
     * which would read downstream as "measured, and clear".
     */
    private fun updateSurfaces(frame: OrientedFrame): SurfaceEvidence? {
        val active = segmenter ?: return null
        val due = lastSurfaces == null || frame.frameId % segmentationStride == 0
        if (!due) return lastSurfaces
        return try {
            val segmentation = active.segment(frame)
            lastSegmentationMillis = segmentation.inferenceMillis
            val letterbox = Letterbox(
                frame.width,
                frame.height,
                segmentation.width * SEGMENTATION_OUTPUT_STRIDE,
                segmentation.height * SEGMENTATION_OUTPUT_STRIDE,
            )
            SurfaceEvidenceBuilder.build(segmentation, letterbox, settings)
                .also { lastSurfaces = it }
        } catch (exc: Throwable) {
            Log.e(TAG, "segmentation failed; corridors fall back to UNCERTAIN", exc)
            lastSurfaces = null
            null
        }
    }

    fun close() {
        detector.close()
        segmenter?.close()
    }

    private companion object {
        /** SegFormer emits at 1/4 of its input resolution (512 -> 128). */
        const val SEGMENTATION_OUTPUT_STRIDE = 4

        const val TAG = "LocalWalkPipeline"
        const val SCHEMA_VERSION = "1.0.0"
        const val COORDINATE_SPACE = "ORIENTED_CAPTURE_NORMALIZED"
        const val OVERLAY_VALID_MS = 400L

        fun displayColor(level: RiskLevel): DisplayColor = when (level) {
            RiskLevel.HIGH, RiskLevel.CRITICAL -> DisplayColor.RED
            RiskLevel.WATCH, RiskLevel.WARN -> DisplayColor.YELLOW
            RiskLevel.CLEAR -> DisplayColor.GREY
        }

        fun hapticFor(action: GuidanceAction): HapticPattern = when (action) {
            GuidanceAction.CLEAR -> HapticPattern.NONE
            GuidanceAction.CAUTION -> HapticPattern.CAUTION_SHORT
            GuidanceAction.MOVE_LEFT, GuidanceAction.MOVE_RIGHT -> HapticPattern.WARNING_DOUBLE
            GuidanceAction.STOP -> HapticPattern.CRITICAL_RAPID
            GuidanceAction.PAUSE_UNCLEAR -> HapticPattern.UNCLEAR_LONG
        }

        fun directionArrow(action: GuidanceAction): DirectionArrow = when (action) {
            GuidanceAction.MOVE_LEFT -> DirectionArrow.LEFT
            GuidanceAction.MOVE_RIGHT -> DirectionArrow.RIGHT
            GuidanceAction.STOP -> DirectionArrow.STOP
            else -> DirectionArrow.NONE
        }

        fun List<Pair<Double, Double>>.toNormalized(): List<NormalizedPoint> =
            map { NormalizedPoint(x = it.first, y = it.second) }
    }
}
