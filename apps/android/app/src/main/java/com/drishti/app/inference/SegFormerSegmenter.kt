package com.drishti.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
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
 * already computes on are promoted to be the graph's IO. Lossless, and it works
 * on both the CPU and the NPU rung.
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

    /** Per-class-id surface kind, resolved once at load rather than per frame. */
    private val kindByClassId: IntArray = IntArray(MAX_CLASSES) { SurfaceKind.UNKNOWN.ordinal }
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
        val elapsed = (System.nanoTime() - started) / 1_000_000.0

        val pixels = outWidth * outHeight
        val classId = IntArray(pixels)
        val kind = IntArray(pixels)
        val hazard = BooleanArray(pixels)
        val wall = BooleanArray(pixels)
        for (p in 0 until pixels) {
            var best = 0
            var bestScore = logits[p]
            for (c in 1 until classCount) {
                val score = logits[c * pixels + p]
                if (score > bestScore) {
                    bestScore = score
                    best = c
                }
            }
            classId[p] = best
            kind[p] = if (best < MAX_CLASSES) kindByClassId[best] else SurfaceKind.UNKNOWN.ordinal
            hazard[p] = best in hazardClassIds
            wall[p] = best in wallClassIds
        }

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
            return try {
                val env = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                val session = env.createSession(model.absolutePath, options)
                val inputName = session.inputNames.first()
                val shape = (session.inputInfo.getValue(inputName).info as TensorInfo).shape
                SegFormerSegmenter(
                    env = env,
                    session = session,
                    backend = InferenceBackend.CPU,
                    inputName = inputName,
                    inputHeight = shape[2].toInt(),
                    inputWidth = shape[3].toInt(),
                    idToLabel = labels,
                ).also {
                    Log.i(TAG, "segmentation ready: ${it.inputWidth}x${it.inputHeight}, ${labels.size} classes")
                }
            } catch (exc: Throwable) {
                Log.e(TAG, "SegFormer failed to load", exc)
                null
            }
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
