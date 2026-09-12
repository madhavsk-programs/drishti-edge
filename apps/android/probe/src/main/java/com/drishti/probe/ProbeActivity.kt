package com.drishti.probe

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlin.concurrent.thread

/**
 * P0.8 probe UI. Deliberately plain Android views - no Compose, no theme work.
 * Its only job is to print evidence and to make that evidence easy to read off a
 * mirrored screen or an `adb logcat` capture.
 */
class ProbeActivity : ComponentActivity() {

    private lateinit var output: TextView

    /**
     * Swap by pushing different files; no rebuild needed.
     * first = the QNN/EPContext model, second = a plain-ONNX peer for the CPU
     * rung (an EPContext model cannot run on the CPU EP at all - see QnnProbe).
     */
    private val models = listOf(
        // YOLO: the QNN export is an EPContext binary, QNN-EP-only and NHWC.
        // Its CPU peer must be the separate plain fp32 NCHW export.
        "yolo11n_qnn.onnx" to "yolo11n_fp32_nchw.onnx",
        // SegFormer: Qualcomm ship a plain QDQ graph (no EPContext), NCHW, which
        // runs on BOTH backends. One artifact, two EPs - so this is the cleaner
        // apples-to-apples NPU-vs-CPU comparison for BUILD_PLAN.md §6.2 proof 3.
        "segformer_base.onnx" to "segformer_base.onnx",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }

        val run = Button(this).apply {
            text = "RUN PROBE"
            setOnClickListener { runProbe() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                run,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER },
            )
            addView(
                ScrollView(this@ProbeActivity).apply { addView(output) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                ).apply { weight = 1f },
            )
        }
        setContentView(root)
        runProbe()
    }

    private fun runProbe() {
        output.text = "running..."
        thread {
            val report = buildString {
                appendLine(deviceHeader())
                for ((npuModel, cpuModel) in models) {
                    appendLine(QnnProbe.run(this@ProbeActivity, npuModel, cpuModel))
                    appendLine()
                    appendLine("-".repeat(58))
                    appendLine()
                }
            }
            // Chunked so logcat's per-line cap does not truncate the evidence.
            report.lineSequence().forEach { Log.i("DrishtiProbe", it) }
            runOnUiThread { output.text = report }
        }
    }

    /**
     * BUILD_PLAN.md §7.4 / Part 4: every quoted measurement carries the device it
     * came from, so a playground number can never be mistaken for a loaner one.
     */
    private fun deviceHeader(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val mb = 1024L * 1024L
        return buildString {
            appendLine("=".repeat(58))
            appendLine("device   ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("soc      ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            appendLine("board    ${Build.BOARD}  abi ${Build.SUPPORTED_ABIS.firstOrNull()}")
            appendLine("android  ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
            appendLine("memory   avail ${mi.availMem / mb} MB / total ${mi.totalMem / mb} MB" +
                "  lowMemory=${mi.lowMemory}")
            appendLine("thermal  ${pm.currentThermalStatus}")
            appendLine("=".repeat(58))
        }
    }
}
