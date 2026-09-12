package com.drishti.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import android.util.Log
import com.drishti.app.config.PipelineSettings
import com.drishti.app.perception.CANONICAL_LABELS
import com.drishti.app.perception.DetectionSet
import com.drishti.app.perception.RawDetection
import com.drishti.app.perception.canonicalizeDetections
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONObject

private const val TAG = "OrtYoloDetector"

/**
 * YOLO11n through ONNX Runtime, NPU first (BUILD_PLAN.md task A6).
 *
 * Portable QDQ first, legacy context second, CPU fallback last:
 *
 * | Model | Input | Layout | Runs on |
 * |---|---|---|---|
 * | `yolo11n_qdq.onnx` | `[1,3,640,640]` | NCHW | QNN (compile on device), CPU |
 * | `yolo11n_qnn.onnx` | `[1,640,640,3]` | NHWC | QNN EP only (EPContext) |
 * | `yolo11n_fp32_nchw.onnx` | `[1,3,640,640]` | NCHW | any EP |
 *
 * The layout is READ FROM THE SESSION rather than assumed. Feeding NCHW to the
 * NHWC graph produces a session that runs and returns confident nonsense, which
 * is the single easiest way to lose hours on this seam.
 */
class OrtYoloDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    override val backend: InferenceBackend,
    override val detail: String,
    private val settings: PipelineSettings,
    private val inputName: String,
    private val outputName: String,
    private val layout: TensorLayout,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val classNames: Map<Int, String>,
) : OnDeviceDetector {

    enum class TensorLayout { NHWC, NCHW }

    private val pixelCount = inputWidth * inputHeight
    private val inputBuffer: FloatBuffer = FloatBuffer.allocate(pixelCount * 3)
    private val inputShape = when (layout) {
        TensorLayout.NHWC -> longArrayOf(1, inputHeight.toLong(), inputWidth.toLong(), 3)
        TensorLayout.NCHW -> longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
    }

    override fun detect(frame: OrientedFrame): DetectionOutcome {
        val preStart = System.nanoTime()
        val letterbox = Letterbox(frame.width, frame.height, inputWidth, inputHeight)
        fillInput(frame, letterbox)
        val preEnd = System.nanoTime()

        val raw: FloatArray
        val outputShape: LongArray
        OnnxTensor.createTensor(env, inputBuffer, inputShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val value = results[0] as OnnxTensor
                outputShape = (value.info as TensorInfo).shape
                val flat = value.floatBuffer
                raw = FloatArray(flat.remaining())
                flat.get(raw)
            }
        }
        val inferEnd = System.nanoTime()

        // [1, channels, anchors] — channel-major, no NMS applied by the export.
        val channels = outputShape[1].toInt()
        val anchors = outputShape[2].toInt()
        val decoded = decodeYoloHead(raw, channels, anchors, CONFIDENCE_FLOOR)
        val kept = nonMaximumSuppression(decoded, IOU_THRESHOLD)

        val rawDetections = kept.map { box ->
            // Un-letterbox BEFORE normalising: boxes are emitted against the
            // full oriented capture, not the letterboxed tensor.
            val (x1, y1) = letterbox.toOrientedNormalized(box.left, box.top)
            val (x2, y2) = letterbox.toOrientedNormalized(box.right, box.bottom)
            RawDetection(
                label = classNames[box.classId] ?: "class_${box.classId}",
                confidence = box.score,
                // canonicalizeDetections divides by width/height, so hand it
                // the normalised values against a 1×1 frame.
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
            )
        }

        // ONE forward pass, two label filterings (ARCHITECTURE.md §9.3).
        val riskView = canonicalizeDetections(
            detections = rawDetections,
            width = 1,
            height = 1,
            confidenceThreshold = settings.detectorConfidenceThreshold,
            allowedLabels = CANONICAL_LABELS,
            applyAliases = true,
        )
        val fullView = canonicalizeDetections(
            detections = rawDetections,
            width = 1,
            height = 1,
            confidenceThreshold = settings.detectorConfidenceThreshold,
            allowedLabels = null,
            applyAliases = false,
        )
        val postEnd = System.nanoTime()

        return DetectionOutcome(
            detections = DetectionSet(risk = riskView, all = fullView),
            stats = InferenceStats(
                backend = backend,
                preprocessMillis = (preEnd - preStart) / 1_000_000.0,
                inferenceMillis = (inferEnd - preEnd) / 1_000_000.0,
                postprocessMillis = (postEnd - inferEnd) / 1_000_000.0,
            ),
        )
    }

    /**
     * RGB888 → letterboxed float32 0–1, straight into the reused buffer.
     *
     * No Bitmap is allocated. At 5–10 fps a per-frame Bitmap is a GC storm that
     * shows up as stutter in the guidance cadence (ARCHITECTURE.md §5.3).
     */
    private fun fillInput(frame: OrientedFrame, letterbox: Letterbox) {
        val buffer = inputBuffer
        buffer.clear()
        val array = buffer.array()
        java.util.Arrays.fill(array, PAD_VALUE)

        val rgb = frame.rgb
        val sourceWidth = frame.width
        for (ty in 0 until inputHeight) {
            for (tx in 0 until inputWidth) {
                val source = letterbox.sourcePixelFor(tx, ty) ?: continue
                val sourceIndex = (source.second * sourceWidth + source.first) * 3
                val r = (rgb[sourceIndex].toInt() and 0xFF) / 255f
                val g = (rgb[sourceIndex + 1].toInt() and 0xFF) / 255f
                val b = (rgb[sourceIndex + 2].toInt() and 0xFF) / 255f
                when (layout) {
                    TensorLayout.NHWC -> {
                        val base = (ty * inputWidth + tx) * 3
                        array[base] = r
                        array[base + 1] = g
                        array[base + 2] = b
                    }

                    TensorLayout.NCHW -> {
                        val offset = ty * inputWidth + tx
                        array[offset] = r
                        array[pixelCount + offset] = g
                        array[2 * pixelCount + offset] = b
                    }
                }
            }
        }
        buffer.position(0)
        buffer.limit(array.size)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        /** Ultralytics' default inference gate, below the risk engine's own. */
        const val CONFIDENCE_FLOOR = 0.35
        const val IOU_THRESHOLD = 0.45

        /** Grey padding, matching Ultralytics' letterbox fill (114/255). */
        private const val PAD_VALUE = 114f / 255f

        /**
         * Model files live in the app's external files dir so a model can be
         * swapped with `adb push` and no rebuild:
         *
         *   adb push yolo11n_qnn.onnx /sdcard/Android/data/com.drishti.app/files/
         */
        const val NPU_MODEL = "yolo11n_qnn.onnx"
        const val QDQ_MODEL = "yolo11n_qdq.onnx"
        const val CPU_MODEL = "yolo11n_fp32_nchw.onnx"

        /**
         * Descend the runtime ladder (BUILD_PLAN.md §3.3): NPU with the fallback
         * guard on, then CPU. A silent CPU fallback is never acceptable — the
         * guard turns it into a thrown exception that we catch and REPORT.
         */
        fun create(context: Context, settings: PipelineSettings): OnDeviceDetector {
            val env = OrtEnvironment.getEnvironment()
            val modelsDir = context.getExternalFilesDir(null)

            setAdspLibraryPath(context)

            for (modelName in listOf(QDQ_MODEL, NPU_MODEL)) {
                val npuModel = File(modelsDir, modelName)
                if (npuModel.isFile) {
                    try {
                        return open(
                            env, npuModel, settings, InferenceBackend.NPU,
                            "YOLO11n on the Hexagon NPU (QNN HTP, CPU fallback disabled).",
                        ) { options ->
                            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                            options.addQnn(
                                mapOf(
                                    "backend_path" to "libQnnHtp.so",
                                    "htp_performance_mode" to "burst",
                                )
                            )
                        }
                    } catch (exc: Throwable) {
                        // Expected when the QAIRT libraries are absent or the device
                        // is not recognised by them. Report it; do not hide it.
                        Log.w(TAG, "$modelName NPU rung unavailable; trying next artifact", exc)
                    }
                } else {
                    Log.w(TAG, "No $modelName in ${modelsDir?.absolutePath}; skipping artifact")
                }
            }

            val cpuModel = File(modelsDir, CPU_MODEL)
            if (cpuModel.isFile) {
                try {
                    return open(
                        env, cpuModel, settings, InferenceBackend.CPU,
                        "YOLO11n on the CPU. The NPU rung was unavailable on this device.",
                    ) { }
                } catch (exc: Throwable) {
                    Log.e(TAG, "CPU rung failed", exc)
                    return UnavailableDetector(
                        "Detection is unavailable: ${exc.javaClass.simpleName}."
                    )
                }
            }

            return UnavailableDetector(
                "No detector model is staged. Push $CPU_MODEL to " +
                    "${modelsDir?.absolutePath} and restart Walk Mode."
            )
        }

        /**
         * The CPU rung on its own, forced. Not part of the runtime ladder: this
         * exists for the diagnostics panel's side-by-side, where the operator
         * toggles the same model onto the CPU to show the NPU's millisecond
         * count against it (BUILD_PLAN.md §5.2 step 9). Returns an
         * [UnavailableDetector] rather than throwing when the CPU model is
         * absent, so a toggle can fail quietly.
         */
        fun createCpuOnly(context: Context, settings: PipelineSettings): OnDeviceDetector {
            val env = OrtEnvironment.getEnvironment()
            val cpuModel = File(context.getExternalFilesDir(null), CPU_MODEL)
            if (!cpuModel.isFile) {
                return UnavailableDetector("No $CPU_MODEL staged for the CPU comparison.")
            }
            return try {
                open(
                    env, cpuModel, settings, InferenceBackend.CPU,
                    "YOLO11n on the CPU (diagnostics comparison).",
                ) { }
            } catch (exc: Throwable) {
                Log.e(TAG, "CPU comparison rung failed", exc)
                UnavailableDetector("CPU comparison unavailable: ${exc.javaClass.simpleName}.")
            }
        }

        /**
         * `libQnnHtpV81Skel.so` executes on the DSP, not the CPU, so the normal
         * linker never finds it. Missing this presents as a generic backend init
         * failure with no mention of the DSP (BUILD_PLAN.md §3.4).
         */
        private fun setAdspLibraryPath(context: Context) {
            val libDir = context.applicationInfo.nativeLibraryDir
            val path = listOf(
                libDir,
                "/vendor/lib/rfsa/adsp",
                "/vendor/dsp/cdsp",
                "/system/lib/rfsa/adsp",
            ).joinToString(";")
            runCatching { Os.setenv("ADSP_LIBRARY_PATH", path, true) }
                .onFailure { Log.w(TAG, "Could not set ADSP_LIBRARY_PATH", it) }
        }

        private fun open(
            env: OrtEnvironment,
            model: File,
            settings: PipelineSettings,
            backend: InferenceBackend,
            detail: String,
            configure: (OrtSession.SessionOptions) -> Unit,
        ): OrtYoloDetector {
            val options = OrtSession.SessionOptions()
            configure(options)
            val session = env.createSession(model.absolutePath, options)

            val inputName = session.inputNames.first()
            val outputName = session.outputNames.first()
            val info = session.inputInfo.getValue(inputName).info as TensorInfo
            val shape = info.shape

            // [1,H,W,3] is NHWC; [1,3,H,W] is NCHW. Read it, never assume it.
            val layout = if (shape.size == 4 && shape[3] == 3L) {
                TensorLayout.NHWC
            } else {
                TensorLayout.NCHW
            }
            val height: Int
            val width: Int
            if (layout == TensorLayout.NHWC) {
                height = shape[1].toInt()
                width = shape[2].toInt()
            } else {
                height = shape[2].toInt()
                width = shape[3].toInt()
            }

            return OrtYoloDetector(
                env = env,
                session = session,
                backend = backend,
                detail = detail,
                settings = settings,
                inputName = inputName,
                outputName = outputName,
                layout = layout,
                inputWidth = width,
                inputHeight = height,
                classNames = readClassNames(session),
            )
        }

        /**
         * Class names come from the export's own `names` metadata, not from a
         * hardcoded COCO ordering — the deployed model is the authority on what
         * index 17 means.
         */
        internal fun readClassNames(session: OrtSession): Map<Int, String> {
            val raw = runCatching {
                session.metadata.customMetadata["names"]
            }.getOrNull() ?: return emptyMap()
            return parseNamesMetadata(raw)
        }

        /**
         * The value is a Python dict literal (`{0: 'person', 1: 'bicycle', ...}`),
         * not JSON — single quotes and bare integer keys. Normalised before
         * parsing rather than pattern-matched, so an unexpected entry fails
         * loudly here instead of mislabelling a detection later.
         */
        internal fun parseNamesMetadata(raw: String): Map<Int, String> = runCatching {
            val asJson = raw.replace('\'', '"')
                .replace(Regex("(?<=[{,])\\s*(\\d+)\\s*:"), "\"$1\":")
            val obj = JSONObject(asJson)
            buildMap {
                for (key in obj.keys()) {
                    val index = key.toIntOrNull() ?: continue
                    put(index, obj.getString(key).lowercase())
                }
            }
        }.getOrElse {
            Log.w(TAG, "Could not parse `names` metadata; labels will be numeric", it)
            emptyMap()
        }
    }
}
