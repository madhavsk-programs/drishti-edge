package com.drishti.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.system.Os
import android.util.Log
import com.drishti.app.net.SurfaceKind
import com.drishti.app.spatial.Surfaces
import java.io.File
import java.nio.FloatBuffer
import org.json.JSONObject

private const val TAG = "SegFormer"

/**
 * SegFormer-B0 ADE20K surface segmentation (ARCHITECTURE.md §6.2).
 *
 * Without this, every corridor reads UNCERTAIN and the risk cascade can only
 * answer PAUSE_UNCLEAR — it refuses to name a direction it cannot defend. So
 * this is what turns the system from honest-but-silent into useful.
 *
 * The model is `segformer_float.onnx`, produced by `tools/segformer_float_io.py`.
 * The published export declares uint16 IO, and ORT's Java API cannot construct
 * a uint16 tensor at all (`OnnxJavaType` has no UINT16), so the boundary
 * quantize/dequantize pair is removed offline and the float tensors the network
 * already computes on are promoted to be the graph's IO. The two constant
 * classifier dequantizers are folded offline as well; otherwise ORT leaves
 * those two nodes on CPU. The export tool checks that fold for exact parity.
 */
class SegFormerSegmenter private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    val backend: InferenceBackend,
    private val inputName: String,
    private val inputWidth: Int,
    private val inputHeight: Int,
    private val idToLabel: Map<Int, String>,
) : AutoCloseable {

    /** Per-class-id lookups, resolved once at load rather than per frame. */
    private val kindByClassId: IntArray = IntArray(MAX_CLASSES) { SurfaceKind.UNKNOWN.ordinal }
    // Arrays rather than Sets: these are read once per output pixel, and a
    // `Set<Int>.contains` there is an autoboxed hash lookup 16k times a frame.
    private val hazardByClassId = BooleanArray(MAX_CLASSES)
    private val wallByClassId = BooleanArray(MAX_CLASSES)
    private val hazardClassIds: Set<Int>
    private val wallClassIds: Set<Int>

    private val inputBuffer = FloatBuffer.allocate(inputWidth * inputHeight * 3)
    private val inputShape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())

    init {
        for ((id, label) in idToLabel) {
            if (id in 0 until MAX_CLASSES) kindByClassId[id] = Surfaces.kindFor(label).ordinal
        }
        hazardClassIds = Surfaces.classIdsMatching(idToLabel, Surfaces.HAZARD_SURFACE_TOKENS)
        wallClassIds = Surfaces.classIdsMatching(idToLabel, Surfaces.WALL_TOKENS)
        for (id in hazardClassIds) if (id in 0 until MAX_CLASSES) hazardByClassId[id] = true
        for (id in wallClassIds) if (id in 0 until MAX_CLASSES) wallByClassId[id] = true
    }

    /**
     * Argmax class map at the model's native output resolution, plus the
     * derived per-pixel surface kind. Logits are NOT softmaxed: argmax is
     * invariant under it and the softmax would be pure cost.
     */
    class SegmentationFrame(
        val width: Int,
        val height: Int,
        val classId: IntArray,
        val kind: IntArray,
        val hazard: BooleanArray,
        val wall: BooleanArray,
        val inferenceMillis: Double,
    )

    fun segment(frame: OrientedFrame): SegmentationFrame {
        val letterbox = Letterbox(frame.width, frame.height, inputWidth, inputHeight)
        fillInput(frame, letterbox)

        val started = System.nanoTime()
        var outWidth = 0
        var outHeight = 0
        var classCount = 0
        lateinit var logits: FloatArray
        OnnxTensor.createTensor(env, inputBuffer, inputShape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { results ->
                val value = results[0] as OnnxTensor
                val shape = (value.info as TensorInfo).shape
                classCount = shape[1].toInt()
                outHeight = shape[2].toInt()
                outWidth = shape[3].toInt()
                val flat = value.floatBuffer
                logits = FloatArray(flat.remaining())
                flat.get(logits)
            }
        }
        val classId = IntArray(outWidth * outHeight)
        argmaxChannelMajor(logits, classId, classCount)
        val pixels = classId.size
        val kind = IntArray(pixels)
        val hazard = BooleanArray(pixels)
        val wall = BooleanArray(pixels)
        for (p in 0 until pixels) {
            val best = classId[p]
            kind[p] = if (best < MAX_CLASSES) kindByClassId[best] else SurfaceKind.UNKNOWN.ordinal
            hazard[p] = hazardByClassId[best]
            wall[p] = wallByClassId[best]
        }
        // Measured AFTER the argmax, not before it. The decode is part of what a
        // segmentation costs the guidance cadence, and reporting only the
        // session run made ~2.5M float comparisons per frame invisible in the
        // diagnostics panel while they showed up in `totalMs` as a mystery.
        val elapsed = (System.nanoTime() - started) / 1_000_000.0

        return SegmentationFrame(
            width = outWidth,
            height = outHeight,
            classId = classId,
            kind = kind,
            hazard = hazard,
            wall = wall,
            inferenceMillis = elapsed,
        )
    }

    /** RGB888 → letterboxed NCHW float32 in [0,1]. */
    private fun fillInput(frame: OrientedFrame, letterbox: Letterbox) {
        val array = inputBuffer.array()
        java.util.Arrays.fill(array, 0f)
        val plane = inputWidth * inputHeight
        val rgb = frame.rgb
        for (ty in 0 until inputHeight) {
            for (tx in 0 until inputWidth) {
                val source = letterbox.sourcePixelFor(tx, ty) ?: continue
                val si = (source.second * frame.width + source.first) * 3
                val offset = ty * inputWidth + tx
                array[offset] = (rgb[si].toInt() and 0xFF) / 255f
                array[plane + offset] = (rgb[si + 1].toInt() and 0xFF) / 255f
                array[2 * plane + offset] = (rgb[si + 2].toInt() and 0xFF) / 255f
            }
        }
        inputBuffer.position(0)
        inputBuffer.limit(array.size)
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {

        /**
         * Per-pixel argmax over a [1, C, H, W] logit tensor, read in CLASS order.
         *
         * The obvious loop — for each pixel, walk the classes — strides through
         * memory by one full H*W plane per step. At 128x128x150 that is a 64 KB
         * jump per comparison, so every one of the ~2.5M reads misses cache, and
         * on the phone this cost more than the network inference it decodes.
         * Sweeping one class plane at a time over running best/score arrays does
         * exactly the same comparisons against sequential memory.
         *
         * Internal so [SegFormerArgmaxTest] can hold it to the naive result.
         */
        internal fun argmaxChannelMajor(logits: FloatArray, out: IntArray, classCount: Int) {
            val pixels = out.size
            val best = FloatArray(pixels)
            System.arraycopy(logits, 0, best, 0, pixels)
            java.util.Arrays.fill(out, 0)
            for (c in 1 until classCount) {
                val base = c * pixels
                for (p in 0 until pixels) {
                    val score = logits[base + p]
                    if (score > best[p]) {
                        best[p] = score
                        out[p] = c
                    }
                }
            }
        }

        const val MODEL = "segformer_float.onnx"
        const val LABELS = "ade20k_config.json"
        private const val MAX_CLASSES = 256

        /**
         * @return null when the model or its label map is not staged. The
         *   caller degrades to detection-only guidance and SAYS so, rather than
         *   presenting surface-blind advice as fully informed.
         */
        fun create(context: Context): SegFormerSegmenter? {
            val dir = context.getExternalFilesDir(null)
            val model = File(dir, MODEL)
            if (!model.isFile) {
                Log.w(TAG, "No $MODEL in ${dir?.absolutePath}; segmentation disabled")
                return null
            }
            val labels = readLabels(File(dir, LABELS))
            if (labels.isEmpty()) {
                Log.w(TAG, "No usable $LABELS; segmentation disabled")
                return null
            }
            val env = OrtEnvironment.getEnvironment()
            setAdspLibraryPath(context)

            val npuOptions = OrtSession.SessionOptions()
            try {
                npuOptions.addConfigEntry("session.disable_cpu_ep_fallback", "1")
                npuOptions.addQnn(
                    mapOf(
                        "backend_path" to "libQnnHtp.so",
                        "htp_performance_mode" to "burst",
                    )
                )
                return open(env, model, labels, npuOptions, InferenceBackend.NPU)
            } catch (exc: Throwable) {
                Log.w(TAG, "guarded NPU rung unavailable; descending to CPU", exc)
            } finally {
                runCatching { npuOptions.close() }
            }

            val cpuOptions = OrtSession.SessionOptions()
            return try {
                open(env, model, labels, cpuOptions, InferenceBackend.CPU)
            } catch (exc: Throwable) {
                Log.e(TAG, "SegFormer failed to load", exc)
                null
            } finally {
                runCatching { cpuOptions.close() }
            }
        }

        private fun open(
            env: OrtEnvironment,
            model: File,
            labels: Map<Int, String>,
            options: OrtSession.SessionOptions,
            backend: InferenceBackend,
        ): SegFormerSegmenter {
            val session = env.createSession(model.absolutePath, options)
            return try {
                val inputName = session.inputNames.first()
                val shape = (session.inputInfo.getValue(inputName).info as TensorInfo).shape
                SegFormerSegmenter(
                    env = env,
                    session = session,
                    backend = backend,
                    inputName = inputName,
                    inputHeight = shape[2].toInt(),
                    inputWidth = shape[3].toInt(),
                    idToLabel = labels,
                ).also {
                    Log.i(
                        TAG,
                        "segmentation ready on $backend: ${it.inputWidth}x${it.inputHeight}, " +
                            "${labels.size} classes",
                    )
                }
            } catch (exc: Throwable) {
                runCatching { session.close() }
                throw exc
            }
        }

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

        internal fun readLabels(file: File): Map<Int, String> {
            if (!file.isFile) return emptyMap()
            return runCatching {
                val root = JSONObject(file.readText())
                val map = if (root.has("id2label")) root.getJSONObject("id2label") else root
                buildMap {
                    for (key in map.keys()) {
                        key.toIntOrNull()?.let { put(it, map.getString(key)) }
                    }
                }
            }.getOrElse {
                Log.e(TAG, "Could not parse $file", it)
                emptyMap()
            }
        }
    }
}
