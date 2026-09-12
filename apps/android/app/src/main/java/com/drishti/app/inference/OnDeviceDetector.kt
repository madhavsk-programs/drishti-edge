package com.drishti.app.inference

import com.drishti.app.perception.DetectionSet

/**
 * Where an inference actually executed. This is evidence for the on-device AI
 * claim (BUILD_PLAN.md §6.2), NOT a degradation switch — there is no rung here
 * that reaches the network, in any state, by any toggle.
 */
enum class InferenceBackend { NPU, GPU, CPU, UNAVAILABLE }

/**
 * One oriented camera frame, already rotated upright.
 *
 * [rgb] is tightly packed RGB888, `width * height * 3` bytes, in the
 * ORIENTED_CAPTURE coordinate space that detections are normalised against.
 */
class OrientedFrame(
    val rgb: ByteArray,
    val width: Int,
    val height: Int,
    val frameId: Int,
    val capturedAtMillis: Long,
)

/** Timing and provenance for one inference, surfaced in the diagnostics panel. */
data class InferenceStats(
    val backend: InferenceBackend,
    val preprocessMillis: Double,
    val inferenceMillis: Double,
    val postprocessMillis: Double,
) {
    val totalMillis: Double get() = preprocessMillis + inferenceMillis + postprocessMillis
}

data class DetectionOutcome(
    val detections: DetectionSet,
    val stats: InferenceStats,
)

interface OnDeviceDetector : AutoCloseable {
    val backend: InferenceBackend

    /** Human-readable provenance, shown in diagnostics and spoken if asked. */
    val detail: String

    /** Runs ONE forward pass and returns both label views of it. */
    fun detect(frame: OrientedFrame): DetectionOutcome

    override fun close()
}

/** Stand-in used when no model could be loaded. Never silently returns empty. */
class UnavailableDetector(override val detail: String) : OnDeviceDetector {
    override val backend: InferenceBackend = InferenceBackend.UNAVAILABLE

    override fun detect(frame: OrientedFrame): DetectionOutcome =
        throw IllegalStateException(detail)

    override fun close() = Unit
}
