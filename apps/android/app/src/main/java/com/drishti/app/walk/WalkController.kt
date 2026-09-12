package com.drishti.app.walk

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.drishti.app.R
import com.drishti.app.config.PipelineSettings
import com.drishti.app.explore.ExploreController
import com.drishti.app.inference.InferenceBackend
import com.drishti.app.inference.OrientedFrame
import com.drishti.app.inference.OrtYoloDetector
import com.drishti.app.inference.SegFormerSegmenter
import com.drishti.app.inference.YuvToRgb
import com.drishti.app.feedback.AudioFocusManager
import com.drishti.app.feedback.GuidanceStrings
import com.drishti.app.feedback.GyroSteering
import com.drishti.app.feedback.HapticEngine
import com.drishti.app.feedback.SpatialAudioEngine
import com.drishti.app.feedback.SpeechEngine
import com.drishti.app.feedback.VoicePrompt
import com.drishti.app.hazard.HazardReporter
import com.drishti.app.hazard.NearbyAdvisor
import com.drishti.app.net.ApiResult
import com.drishti.app.net.DrishtiApi
import com.drishti.app.net.RiskLevel
import com.drishti.app.net.TargetTrackingState
import com.drishti.app.net.TargetTrackingTelemetry
import com.drishti.app.net.StartWalkSessionRequest
import com.drishti.app.net.WalkSettings
import com.drishti.app.net.apiCall
import com.drishti.app.scene.SceneDescriber
import com.drishti.app.scene.SceneVlm
import com.drishti.app.scene.TargetLocator
import com.drishti.app.settings.DrishtiSettings
import com.drishti.app.settings.SettingsStore
import com.drishti.app.sos.SosController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates Walk Mode: session lifecycle, the latest-frame-wins capture loop,
 * response freshness, and fan-out to speech / haptics / spatial audio / overlay.
 * Lives in [WalkForegroundService]; the Activity observes [state].
 */
private val LOCATE_LEAD_STOPWORDS =
    setOf(
        "me", "the", "a", "an", "my", "to", "for", "of", "out", "that", "this",
        "some", "any", "it", "them", "one", "there", "something", "anything", "us",
    )

private val LOCATE_QUESTION_STARTS =
    listOf("what", "whats", "what's", "how", "why", "who", "is", "are", "do", "does", "can")

private val LOCATE_TRAILING_CLAUSE =
    Regex("""\s+(i|which|that|from|earlier|before)\b.*""", RegexOption.IGNORE_CASE)

/**
 * Pure routing helper for the "Ask" gesture: is a spoken phrase an
 * "Ask -> Lock" target, and if so what is the cleaned target name?
 *
 * The locate keyword may sit anywhere in the phrase. Text after it is the
 * target ("can you locate me the blue bucket which I had" -> "blue bucket");
 * for verb-final languages (Hindi/Tamil) text *before* a trailing keyword is
 * used instead. Leading filler and "... which I had" tails are stripped.
 * Returns null for plain scene questions so the caller falls back to Scene Mode.
 *
 * @param markers lower-cased keyword list (trailing spaces tolerated).
 */
fun parseLocateTarget(heard: String?, markers: List<String>): String? {
    val raw = heard?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val lower = raw.lowercase()

    var best = -1
    var markerLen = 0
    for (marker in markers.map { it.trim().lowercase() }) {
        if (marker.isEmpty()) continue
        var from = 0
        while (true) {
            val at = lower.indexOf(marker, from)
            if (at < 0) break
            val end = at + marker.length
            val leftOk = at == 0 || !lower[at - 1].isLetter()
            val rightOk = end >= lower.length || !lower[end].isLetter()
            if (leftOk && rightOk && (best < 0 || at < best)) {
                best = at
                markerLen = marker.length
            }
            from = at + 1
        }
    }
    if (best < 0) return null

    val after = raw.substring(best + markerLen).trim()
    val before = raw.substring(0, best).trim()
    val words = (if (after.isNotEmpty()) after else before)
        .split(Regex("\\s+"))
        .toMutableList()
    while (words.isNotEmpty() &&
        words.first().lowercase().trim('?', '.', '!', ',') in LOCATE_LEAD_STOPWORDS
    ) {
        words.removeAt(0)
    }
    var target = LOCATE_TRAILING_CLAUSE.replace(words.joinToString(" "), "")
        .trim()
        .trimEnd('?', '.', '!', ',')
    if (target.isBlank()) return null
    val tl = target.lowercase()
    if (LOCATE_QUESTION_STARTS.any { tl == it || tl.startsWith("$it ") }) return null
    return target.take(80)
}

class WalkController(
    context: Context,
    private val api: DrishtiApi,
    private val settingsStore: SettingsStore,
    private val speech: SpeechEngine,
    private val haptic: HapticEngine,
    private val strings: GuidanceStrings,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private val pipeline = CameraFramePipeline(app)
    private val spatial = SpatialAudioEngine()
    private val gyro = GyroSteering(app)
    private val focus = AudioFocusManager(app)
    private val explore = ExploreController(pipeline, speech, strings)
    // Scene Mode answers on the phone. Null when the GGUF files are not
    // staged; SceneDescriber then refuses audibly rather than silently.
    private val sceneVlm = SceneVlm.create(app)
    private val scene = SceneDescriber(sceneVlm, pipeline, speech, strings, VoicePrompt(app))
    private val locator = TargetLocator(speech, strings)
    private val hazards = HazardReporter(api, pipeline, speech, strings)
    private val nearby = NearbyAdvisor(api, speech, strings)
    private val sos = SosController(scope, speech, strings)

    private val gate = CaptureLoopGate()
    private val freshness = FrameFreshnessGate()
    private val frameCounter = AtomicInteger(0)
    private val startStopLock = Mutex()

    /**
     * The walking loop runs entirely on this device (BUILD_PLAN.md task A6).
     * There is no path from here to the network in any state, by any toggle —
     * reintroducing one under failure conditions restores exactly the coupling
     * this migration exists to remove, at the moment the user can least
     * tolerate it (ARCHITECTURE.md §19.3).
     */
    private val yuv = YuvToRgb()
    private var localPipeline: LocalWalkPipeline? = null

    /** Serialises frames into the tracker, which carries per-session history. */
    private val inferenceLock = Mutex()

    private val jpegType = "image/jpeg".toMediaType()
    private val textType = "text/plain".toMediaType()

    private var settings: DrishtiSettings = DrishtiSettings()
    private var sessionId: String? = null
    private var maxImageWidth = 1280
    private var maxImageBytes = 5L * 1024 * 1024
    private var maxResultAgeMs = 3000L
    private var recommendedFps = 2.0
    private var nextAllowedAtMs = 0L
    private var announcedSurfaceDegraded = false
    private var lastTargetSpeech: String? = null
    private var loggedFirstLocalFrame = false

    private var tickers = mutableListOf<Job>()
    private var boundOwner: LifecycleOwner? = null
    private var retryJob: Job? = null

    private val _state = MutableStateFlow(WalkUiState(mode = WalkMode.STOPPED))
    val state: StateFlow<WalkUiState> = _state.asStateFlow()

    // ---- lifecycle ---------------------------------------------------------

    fun start(owner: LifecycleOwner) = scope.launch {
        boundOwner = owner
        retryJob?.cancel()
        startStopLock.withLock {
            if (_state.value.mode == WalkMode.WALKING || _state.value.mode == WalkMode.STARTING) return@withLock
            _state.value = WalkUiState(mode = WalkMode.STARTING)
            settings = runCatching { settingsStore.settings.first() }.getOrDefault(DrishtiSettings())
            configureFeedback(settings)
            speech.ensureAudible()

            // Session identity and pacing are now local decisions. There is no
            // negotiation because there is no peer to negotiate with.
            sessionId = "local-${UUID.randomUUID()}"

            val detector = OrtYoloDetector.create(app, PipelineSettings())
            if (detector.backend == InferenceBackend.UNAVAILABLE) {
                Log.e(TAG, "no detector: ${detector.detail}")
                return@withLock fail(detector.detail)
            }
            Log.i(TAG, "detector ready on ${detector.backend}: ${detector.detail}")
            val segmenter = SegFormerSegmenter.create(app)
            if (segmenter == null) {
                Log.w(TAG, "segmentation unavailable; guidance will PAUSE on surfaces")
            }
            localPipeline?.close()
            localPipeline = LocalWalkPipeline(
                detector = detector,
                settings = PipelineSettings(),
                segmenter = segmenter,
                // Detection every frame, surfaces every 3rd. Surfaces change far
                // more slowly than the obstacles standing on them.
                segmentationStride = 3,
            )

            freshness.reset()
            gate.reset()
            nearby.reset()
            frameCounter.set(0)
            announcedSurfaceDegraded = false
            lastTargetSpeech = null
            loggedFirstLocalFrame = false
            nextAllowedAtMs = 0L

            spatial.start()
            spatial.setEnabled(settings.spatialAudioEnabled)
            gyro.start()
            focus.acquire()

            runCatching {
                pipeline.start(owner, targetWidth = maxImageWidth, onFrame = ::onCameraFrame)
            }.onFailure {
                Log.e(TAG, "camera start failed", it)
                return@withLock fail("Camera unavailable")
            }

            _state.value = _state.value.copy(mode = WalkMode.WALKING, message = null)
            speech.say(strings.string(R.string.walk_started), flush = true)
            startTickers()
        }
    }

    fun stop() = scope.launch {
        startStopLock.withLock {
            retryJob?.cancel()
            retryJob = null
            boundOwner = null
            tickers.forEach { it.cancel() }
            tickers.clear()
            sos.cancel()
            pipeline.stop()
            spatial.stop()
            gyro.stop()
            focus.release()
            haptic.cancel()
            sessionId = null
            localPipeline?.close()
            localPipeline = null
            _state.value = WalkUiState(mode = WalkMode.STOPPED)
            speech.say(strings.string(R.string.walk_stopped), flush = true)
        }
    }

    fun shutdown() {
        // Shared speech/haptic are owned by AppContainer; only tear down what we own.
        scope.launch { stop().join() }
        pipeline.shutdown()
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(mode = WalkMode.ERROR, message = message)
        speech.say(message, flush = true)
        scope.launch { spatial.stop(); gyro.stop(); focus.release() }
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(4_000)
            val owner = boundOwner
            if (owner != null && _state.value.mode == WalkMode.ERROR) start(owner)
        }
    }

    private fun configureFeedback(s: DrishtiSettings) {
        strings.setLanguage(s.language)
        speech.setLanguage(s.language)
        speech.setRate(s.speechRate)
        haptic.enabled = s.hapticsEnabled
        spatial.setEnabled(s.spatialAudioEnabled)
    }

    private fun startTickers() {
        tickers += scope.launch {
            while (isActive) {
                spatial.applyYaw(gyro.yawDeltaSinceReference(maxResultAgeMs))
                delay(60)
            }
        }
        tickers += scope.launch {
            while (isActive) {
                delay(20_000)
                if (_state.value.mode == WalkMode.WALKING) nearby.poll(settings)
            }
        }
    }

    // ---- capture loop -----------------------------------------------------

    private fun onCameraFrame(image: ImageProxy) {
        val mode = _state.value.mode
        if (!mode.keepsWalkInferenceActive()) { image.close(); return }
        val now = System.currentTimeMillis()
        if (now < nextAllowedAtMs || !gate.tryBegin()) { image.close(); return }

        // Convert on the camera thread and release the proxy immediately;
        // KEEP_ONLY_LATEST stalls the whole stream while a proxy is held.
        val frame = runCatching {
            val rgb = yuv.convert(image)
            OrientedFrame(
                rgb = rgb,
                width = yuv.width,
                height = yuv.height,
                frameId = frameCounter.incrementAndGet(),
                capturedAtMillis = now,
            )
        }.getOrElse {
            Log.e(TAG, "YUV conversion failed", it)
            null
        }
        image.close()
        if (frame == null) { gate.finishRequestFailure(); return }

        scope.launch { analyze(frame) }
    }

    /**
     * [frame] borrows [YuvToRgb]'s reused buffer. That is safe because
     * [CaptureLoopGate] admits exactly one frame at a time: the gate is held
     * from `tryBegin` in [onCameraFrame] until a `finish*` call below, so no
     * second conversion can overwrite the buffer while this runs.
     */
    private suspend fun analyze(frame: OrientedFrame) {
        val id = sessionId
        val pipeline = localPipeline
        if (id == null || pipeline == null) { gate.finishRequestFailure(); return }

        val response = inferenceLock.withLock {
            runCatching {
                pipeline.process(
                    frame = frame,
                    sessionId = id,
                    riskSensitivity = settings.riskSensitivity.toDouble(),
                    hapticsEnabled = settings.hapticsEnabled,
                    headingDegrees = gyro.currentHeadingDegrees()?.toDouble(),
                )
            }
        }
        response.onSuccess {
            gate.finishSuccess()
            if (!loggedFirstLocalFrame) {
                loggedFirstLocalFrame = true
                Log.i(
                    TAG,
                    "first local frame ${it.frameId}: total=%.2f ms, detection=%s ms, segmentation=%s ms"
                        .format(
                            it.timings.totalMs,
                            it.timings.detectionMs?.let { value -> "%.2f".format(value) } ?: "n/a",
                            it.timings.segmentationMs?.let { value -> "%.2f".format(value) } ?: "reused",
                        ),
                )
            }
            pace(it.timings.totalMs, it.frameAgeMs)
            applyResponse(it)
        }.onFailure {
            // A failed inference is a real failure, not a quiet empty frame.
            // Returning no detections would read downstream as "path clear".
            Log.e(TAG, "on-device inference failed for frame ${frame.frameId}", it)
            gate.finishRequestFailure()
            _state.value = _state.value.copy(message = strings.string(R.string.models_not_ready))
            pace(null, null)
        }
    }

    private fun pace(totalMs: Double?, frameAgeMs: Double?) {
        val delayMs = CapturePacing.nextDelayMs(
            CapturePacing.Input(
                consecutiveFailures = gate.consecutiveFailures,
                frameAgeMs = frameAgeMs,
                maxResultAgeMs = maxResultAgeMs,
                recommendedFps = recommendedFps,
                totalProcessingMs = totalMs,
            ),
        )
        nextAllowedAtMs = System.currentTimeMillis() + delayMs
    }

    private fun applyResponse(resp: com.drishti.app.net.FrameAnalysisResponse) {
        // Read is deliberately concurrent with walking safety. Other explicit
        // modes suppress results from frames already in flight.
        if (!_state.value.mode.keepsWalkInferenceActive()) return
        val verdict = freshness.evaluate(
            FrameFreshnessGate.Input(
                responseSessionId = resp.sessionId,
                activeSessionId = sessionId,
                sessionRunning = _state.value.mode.keepsWalkInferenceActive(),
                frameId = resp.frameId,
                overlayValidUntil = parseInstant(resp.overlay.validUntil),
                capturedAt = parseInstant(resp.capturedAt),
                maxResultAgeMs = maxResultAgeMs,
            ),
        )
        if (verdict is FrameFreshnessGate.Verdict.Discard) {
            Log.d(TAG, "discard frame ${resp.frameId}: ${verdict.reason}")
            return
        }
        freshness.markApplied(resp.frameId)
        gyro.markReference()

        val degraded = resp.degradedModules.any { it == "segmentation" || it == "india_hazards" }
        _state.value = _state.value.copy(
            guidance = resp.guidance,
            detections = resp.detections,
            overlay = resp.overlay,
            geometry = resp.geometry,
            surfacesDegraded = degraded,
            lastTotalMs = resp.timings.totalMs,
            reason = strings.reasonText(resp.guidance),
            message = null,
        )

        val phrase = strings.speechFor(resp.guidance)
        if (resp.guidance.speak && phrase != null) {
            val flush = resp.guidance.level == RiskLevel.HIGH ||
                resp.guidance.level == RiskLevel.CRITICAL ||
                resp.guidance.action == com.drishti.app.net.GuidanceAction.STOP
            speech.say(phrase, flush = flush)
        }
        haptic.play(resp.guidance.hapticPattern, resp.guidance.action)
        spatial.updateFromDetections(resp.detections)
        applyTargetTracking(resp.targetTracking)

        if (degraded && !announcedSurfaceDegraded) {
            announcedSurfaceDegraded = true
            speech.say(strings.string(R.string.surface_degraded), flush = false)
        }
    }

    /**
     * Render the backend's Ask -> Guide decision. The backend stays the
     * authority: on any non-CLEAR safety action it has already set
     * `is_safety_overridden` and blanked `speak` / `haptic_pattern`, so target
     * cues can never preempt or delay a safety instruction. Target speech is
     * always QUEUE_ADD, never a flush.
     */
    private fun applyTargetTracking(raw: TargetTrackingTelemetry?) {
        if (raw == null) return
        // The engine leaves `speech` empty; the line is chosen here so it
        // follows the spoken-language setting.
        val tt = if (raw.speak) raw.copy(speech = strings.targetLine(raw).orEmpty()) else raw
        _state.value = _state.value.copy(target = tt)
        if (tt.isSafetyOverridden) {
            spatial.targetPan(null)
            lastTargetSpeech = null
            return
        }
        if (tt.speak && tt.speech.isNotBlank() && tt.speech != lastTargetSpeech) {
            speech.say(tt.speech, flush = false, dedupe = false)
            lastTargetSpeech = tt.speech
        } else if (!tt.speak) {
            lastTargetSpeech = null
        }
        if (tt.trackingState == TargetTrackingState.ARRIVED ||
            tt.guidanceStep == com.drishti.app.net.TargetGuidanceStep.ARRIVED
        ) {
            haptic.playTargetArrived()
        } else {
            haptic.playTarget(tt.hapticPattern)
        }
        // Pan the audio beacon to the live target box only while actively guiding.
        spatial.targetPan(
            if (tt.trackingState == TargetTrackingState.GUIDING) {
                tt.targetCenter?.x?.toFloat()
            } else {
                null
            },
        )
    }

    // ---- user actions ---------------------------------------------------

    fun repeatLast() {
        if (!speech.repeatLast()) speech.say(strings.string(R.string.repeat_hint), flush = true)
    }

    private var exploreClearJob: Job? = null

    fun triggerExplore() = scope.launch {
        if (_state.value.mode != WalkMode.WALKING) return@launch
        _state.value = _state.value.copy(mode = WalkMode.READING)
        spatial.clear()
        haptic.ack()
        val result = explore.readTextOnce()
        if (result != null) {
            _state.value = _state.value.copy(
                explore = ExploreCard(
                    text = result.text,
                    routeNumbers = result.routeNumbers,
                    quality = result.confidenceQualification,
                    shownAtMs = System.currentTimeMillis(),
                ),
            )
            exploreClearJob?.cancel()
            exploreClearJob = scope.launch {
                delay(20_000)
                _state.value = _state.value.copy(explore = null)
            }
        }
        if (_state.value.mode == WalkMode.READING) {
            _state.value = _state.value.copy(mode = WalkMode.WALKING)
        }
    }

    private var sceneClearJob: Job? = null

    /**
     * The one "Ask" gesture. Pauses the Walk loop, speaks a prompt, captures one
     * spoken phrase, then routes by a leading keyword:
     *
     *  - "find <place>" / "where is <place>" / ... -> `/vlm/locate`: lock the
     *    target so per-frame `target_tracking` telemetry then guides the user.
     *  - anything else (or nothing heard) -> Scene Mode `/vlm/query`: describe
     *    what is ahead, unchanged.
     *
     * Locate is only reachable while WALKING, so an active session always backs
     * the backend's in-memory frame and the telemetry stream.
     */
    fun triggerAsk() = scope.launch {
        if (_state.value.mode != WalkMode.WALKING) return@launch
        val id = sessionId ?: return@launch
        _state.value = _state.value.copy(mode = WalkMode.DESCRIBING, message = null)
        spatial.clear()
        haptic.ack()
        lastTargetSpeech = null

        val heard = scene.listenForRequest(settings.language.tag)
        val target = extractLocateTarget(heard)
        if (target != null) {
            // Resolved from the session's landmark memory and the last live
            // frame, on device. Under the inference lock so it never reads
            // memory while a frame is being folded in.
            inferenceLock.withLock {
                locator.locateOnce(target, localPipeline, gyro.currentHeadingDegrees()?.toDouble())
            }
            // Ongoing guidance now flows from targetTracking on each walk frame.
        } else {
            val result = scene.describeOnce(heard)
            if (result != null) {
                _state.value = _state.value.copy(
                    scene = SceneCard(
                        question = result.question,
                        answer = result.answer,
                        totalMs = result.totalMs,
                        shownAtMs = System.currentTimeMillis(),
                    ),
                )
                sceneClearJob?.cancel()
                sceneClearJob = scope.launch {
                    delay(35_000)
                    _state.value = _state.value.copy(scene = null)
                }
            }
        }
        if (_state.value.mode == WalkMode.DESCRIBING) {
            _state.value = _state.value.copy(mode = WalkMode.WALKING)
        }
    }

    /**
     * Decide whether a spoken phrase is an "Ask -> Lock" target and, if so,
     * return a cleaned target name.
     *
     * The locate keyword ("find", "locate", "where is", ...) may sit anywhere in
     * the phrase, not just at the start, so conversational wording works:
     * "can you locate me the blue bucket which I had" -> "blue bucket". Text
     * after the keyword is the target (text before it, for verb-final languages
     * like Hindi/Tamil); leading filler words and trailing "... which I had"
     * clauses are stripped. Returns null for plain scene questions.
     */
    private fun extractLocateTarget(heard: String?): String? =
        parseLocateTarget(heard, strings.locatePrefixes())

    fun armHazard() {
        if (_state.value.mode != WalkMode.WALKING) return
        haptic.ack()
        _state.value = _state.value.copy(hazardArming = true, message = strings.string(R.string.hazard_prompt))
        speech.say(strings.string(R.string.hazard_prompt), flush = true)
        scope.launch {
            delay(8_000)
            if (_state.value.hazardArming) {
                _state.value = _state.value.copy(hazardArming = false, message = null)
            }
        }
    }

    fun confirmHazard(withEvidence: Boolean) = scope.launch {
        if (!_state.value.hazardArming) return@launch
        _state.value = _state.value.copy(hazardArming = false, message = null)
        val dir = _state.value.detections.maxByOrNull { it.riskScore }?.direction
        hazards.report(settings, sessionId, direction = dir, withEvidence = withEvidence)
    }

    fun cancelHazard() {
        if (!_state.value.hazardArming) return
        _state.value = _state.value.copy(hazardArming = false, message = null)
        speech.say(strings.string(R.string.hazard_cancelled), flush = true)
    }

    fun toggleBlank() {
        val blank = !_state.value.screenBlank
        _state.value = _state.value.copy(screenBlank = blank)
        speech.say(
            strings.string(if (blank) R.string.screen_blanked else R.string.screen_unblanked),
            flush = true,
        )
    }

    fun triggerSos() {
        haptic.play(com.drishti.app.net.HapticPattern.CRITICAL_RAPID)
        _state.value = _state.value.copy(mode = WalkMode.SOS)
        sos.activate()
    }

    fun cancelSos() {
        if (_state.value.mode != WalkMode.SOS) return
        sos.cancel()
        _state.value = _state.value.copy(mode = if (sessionId != null) WalkMode.WALKING else WalkMode.STOPPED)
    }

    // ---- preview surface (Activity attaches/detaches) --------------------

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) = pipeline.attachPreview(surfaceProvider)
    fun detachPreview() = pipeline.detachPreview()

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrElse { Instant.now().plusSeconds(1) }

    private companion object { const val TAG = "WalkController" }
}
