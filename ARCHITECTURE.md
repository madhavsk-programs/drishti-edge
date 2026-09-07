# DRISHTI Edge — On-Device Rebuild Architecture

> **Status:** build specification for the iQOO Hackathon Chennai City Battle,
> 12–13 September 2026.
> **Target device:** iQOO 15, Snapdragon 8 Elite Gen 5, **12 GB RAM variant assumed**.
> **Purpose:** move the entire DRISHTI perception and guidance stack off a laptop
> GPU and onto the phone's Hexagon NPU, so a walking user is never dependent on a
> nearby machine.
>
> This document exists so that the rebuild does not have to rediscover anything.
> Every behaviour DRISHTI already gets right is written down here as a constraint,
> and every open question is written down as a gate with a decision rule attached.

---

## Table of contents

1. [How to read this document](#1-how-to-read-this-document)
2. [What is being rebuilt and what is not](#2-what-is-being-rebuilt-and-what-is-not)
3. [The non-negotiable safety contract](#3-the-non-negotiable-safety-contract)
4. [Device budget — 12 GB iQOO 15](#4-device-budget--12-gb-iqoo-15)
5. [Memory architecture](#5-memory-architecture)
6. [Model selection and fallback ladders](#6-model-selection-and-fallback-ladders)
7. [Runtime and deployment path](#7-runtime-and-deployment-path)
8. [Pipeline architecture](#8-pipeline-architecture)
9. [Stage specifications](#9-stage-specifications)
10. [The coordinate transform contract](#10-the-coordinate-transform-contract)
11. [Spatial reasoning port](#11-spatial-reasoning-port)
12. [Risk engine port](#12-risk-engine-port)
13. [Guidance state machine](#13-guidance-state-machine)
14. [Output layer — speech, haptics, spatial audio](#14-output-layer--speech-haptics-spatial-audio)
15. [Threading and concurrency model](#15-threading-and-concurrency-model)
16. [Thermal and sustained performance](#16-thermal-and-sustained-performance)
17. [On-demand modes — Explore and Scene](#17-on-demand-modes--explore-and-scene)
18. [The Office Kit bridge](#18-the-office-kit-bridge)
19. [Degradation ladder](#19-degradation-ladder)
20. [The 30-hour schedule](#20-the-30-hour-schedule)
21. [Verification plan](#21-verification-plan)
22. [Unknowns to resolve on-site, in order](#22-unknowns-to-resolve-on-site-in-order)
23. [Appendix A — preserved contract types](#appendix-a--preserved-contract-types)
24. [Appendix B — the 19-class risk set](#appendix-b--the-19-class-risk-set)
25. [Appendix C — reason codes](#appendix-c--reason-codes)

---

## 1. How to read this document

### 1.1 Precedence

When two parts of this document disagree, resolve in this order:

1. **§3 Safety contract.** Nothing overrides it. Not performance, not the demo,
   not a judge's question.
2. **Appendix A, the typed contract.** The data shapes are frozen. The dashboard
   and the Android client are both already written against them.
3. **The gates** in §20 and §22. A gate that fails changes the plan; it does not
   get argued with.
4. Everything else.

### 1.2 The three categories of statement

Statements in this document are one of three kinds, and they are marked:

- **MUST** — a hard requirement. Violating it either breaks the safety contract
  or breaks a contract the existing code depends on.
- **SHOULD** — the intended approach. Deviate only with a stated reason and only
  when a gate result forces it.
- **MEASURE** — a number that is currently unknown and must be measured on the
  loaner device before anything is designed around it. Every `MEASURE` in this
  document has a decision rule attached saying what to do with each outcome.

### 1.3 The one-sentence summary

The phone captures a frame, two small NPU models say *what is in front* and
*where the floor is*, pure Kotlin logic turns that into one of eight guidance
actions, and the user hears and feels the result — with no network involved at
any point in that loop.

---

## 2. What is being rebuilt and what is not

### 2.1 Carried over unchanged

These are in this repository and predate the rebuild. They are consumed by the
new pipeline, not rewritten by it.

| Component | Path | Why it carries over |
|---|---|---|
| Coordinator dashboard | `apps/dashboard/` | Runs on the laptop, reached over Office Kit. Never in the walking path. |
| Typed contracts | `packages/contracts/` | The data shapes the rebuilt pipeline must produce. Frozen. |
| Android UI shell | `apps/android/.../ui/` | Compose screens, overlay canvas, gesture handling. |
| Output engines | `apps/android/.../feedback/` | Speech, haptics, spatial audio, audio focus, gyro steering. |
| Camera plumbing | `apps/android/.../walk/` | CameraX capture, frame encoder, freshness gate. |
| Coordinate transform | `apps/android/.../ui/PreviewTransform.kt` | Already correct. Regressing it breaks the overlay. |

### 2.2 Rebuilt during the event

Everything below currently lives in Python on a CUDA laptop and does not exist
on the phone at all. **This is the event-window work.**

| Component | Currently | Becomes |
|---|---|---|
| Object detection | YOLO11n, PyTorch, CUDA | NPU-quantized YOLOv8-det or YOLOX |
| Surface segmentation | SegFormer-B0 ADE20K, CUDA | NPU segmentation model |
| Object tracking | Python, session-scoped | Kotlin, in-process |
| Spatial reasoning | Python | Kotlin |
| Risk engine | Python | Kotlin |
| Guidance state machine | Python | Kotlin |
| OCR | Tesseract 5, laptop CPU | On-device OCR |
| Scene VLM | Moondream2, CUDA | Qwen3-VL-2B on NPU |
| Transport | HTTP multipart over LAN | **Deleted. No transport.** |

### 2.3 Deliberately not rebuilt

- **SQLite hazard persistence, the verify/assign/resolve workflow, accessibility
  scoring.** These stay on the laptop behind the dashboard. They are coordinator
  concerns, not walker concerns, and nothing in the walking path waits on them.
- **The FastAPI service.** Deleted from the walking path entirely. The phone does
  not call it. If a laptop is present, hazard reports are pushed to it over the
  Office Kit bridge as a fire-and-forget side effect (§18).

### 2.4 The architectural inversion, stated plainly

```
BEFORE                                  AFTER
------                                  -----
phone: capture, encode, POST            phone: capture, infer, reason, speak
  |                                       |
  | JPEG over Wi-Fi, every frame          | (nothing)
  v                                       |
laptop: decode, detect, segment,          v
        track, reason, score,           laptop: dashboard only, optional,
        guide                                   fire-and-forget hazard sync
  |
  | JSON back over Wi-Fi
  v
phone: speak, vibrate, draw
```

The round trip in the BEFORE column is the product's fatal flaw. Removing it is
the entire point of the build.

---

## 3. The non-negotiable safety contract

Carried from `DECISIONS.md` §3 of the parent project. These are not guidelines.
A build that violates any of them is worse than no build, because the user is
blind and cannot cross-check what the phone tells them.

### 3.1 What the system must never say

- **MUST NOT** state or imply that a road, crossing, or path is *safe*. It may
  report what it detects. It may never certify.
- **MUST NOT** state distance in metres, feet, or any absolute unit. A single
  camera cannot measure it. Use the relative bands only: `FAR`, `MEDIUM`,
  `NEAR`, `IMMEDIATE`, `UNKNOWN`.
- **MUST NOT** invent a direction when the evidence is weak or contradictory.
  The correct output in that case is `PAUSE_UNCLEAR`, spoken as an admission of
  uncertainty.
- **MUST NOT** present itself as a replacement for a white cane, a guide dog,
  mobility training, or human judgement. It is an assistive prototype.

### 3.2 What the system must always do

- **MUST** prefer `STOP` or `PAUSE_UNCLEAR` over a confident wrong answer. For
  this user, a confident wrong answer is the most dangerous possible output.
- **MUST** carry every state in more than one channel. Colour is never the only
  signal — every state also has a word, an icon shape, and a haptic pattern.
  A blind user receives nothing from colour at all.
- **MUST** let safety guidance preempt everything else. If Walk guidance and a
  target-tracking cue or a scene answer contend for the speech channel, safety
  wins immediately and the other is dropped, not queued.
- **MUST** degrade loudly. If segmentation is unavailable, the user is told the
  system is running reduced, not left to assume full capability.

### 3.3 Privacy invariants

- **MUST NOT** store frames. Frames live in a ring buffer and are overwritten.
  Nothing is written to disk in the walking path.
- **MUST NOT** perform facial recognition, identity tracking, or route-history
  logging.
- A hazard evidence JPEG leaves the device **only** after an explicit per-report
  consent gesture, and only to the paired laptop over Office Kit.
- **MUST NOT** make a network call of any kind during Walk Mode. This is now
  enforceable in a way it never was before: there is no client. Assert it in
  code and demonstrate it in airplane mode.

### 3.4 Why this section is first

Every shortcut available under time pressure in a 30-hour build trades against
one of these. Writing them at the top makes the trade visible when someone at
hour 22 suggests "just say it's clear if we don't detect anything." That
suggestion is a §3.1 violation and the answer is no.

---

## 4. Device budget — 12 GB iQOO 15

### 4.1 Why 12 GB is the planning assumption

The loaner variant is unconfirmed. The iQOO 15 ships in 12 GB and 16 GB
configurations, and the event provides one flagship loaner per person without
specifying the SKU. **Every MUST-tier item in this document fits 12 GB.**
Anything requiring 16 GB is explicitly marked as a stretch and is never on the
demo's critical path.

This is deliberate: a demo that depends on drawing the 16 GB variant is a demo
that can fail at check-in for reasons entirely outside our control.

### 4.2 The consequence that matters most

The parent project's research notes that NexaSDK's Android path documents a
**16 GB RAM floor** for its model set. On a 12 GB device that path may simply be
unavailable.

> **MEASURE 4.2.1** — Does NexaSDK initialise on the loaner, and does it expose
> an NPU backend rather than silently falling back to CPU?
>
> **Decision rule:**
> - Initialises with NPU backend → use it for OCR and the VLM.
> - Initialises, CPU only → do not use it. CPU inference in the walk loop is a
>   thermal and latency disaster. Fall back to the Qualcomm AI Hub / QNN path.
> - Does not initialise → AI Hub / QNN path only, and the VLM becomes a stretch
>   item rather than a Should.

### 4.3 Realistic RAM accounting

| Consumer | Estimate | Note |
|---|---|---|
| Android 16 + OriginOS resident | 3.5 – 4.5 GB | **MEASURE** on the loaner, idle, after reboot |
| Other system services, background apps | ~1.0 GB | Reducible by closing everything |
| Our app baseline — Compose, CameraX, buffers | 0.4 – 0.6 GB | Measured on our own hardware |
| **Remaining headroom** | **~5.5 – 6.5 GB** | Before the low-memory killer becomes a threat |

> **Planning ceiling: total model residency MUST stay at or below 3.5 GB, with a
> hard ceiling of 4.5 GB.** Above that the Android low-memory killer starts
> reclaiming, and the failure mode is our process being killed mid-walk. That is
> the single worst thing that can happen during a demo, and it is silent until it
> happens.

### 4.4 Model residency budget

| Stage | Resident? | Est. runtime footprint |
|---|---|---|
| Detection (YOLOv8n INT8) | **Always** | 120 – 180 MB |
| Segmentation (small, INT8) | **Always** | 150 – 250 MB |
| Tracking, spatial, risk, guidance | Always | < 20 MB, pure Kotlin |
| **Walk loop total** | | **~300 – 450 MB** |
| OCR (on demand) | Load / unload | 150 – 250 MB peak |
| VLM (Qwen3-VL-2B INT4, on demand) | Load / unload | 2.0 – 2.7 GB peak |
| Reasoning LLM (Qwen3-4B INT4) | **Stretch only** | 3.0 – 3.5 GB peak |

The Walk loop is comfortable. Everything else is a spike.

### 4.5 The single most important memory rule

> **MUST: the VLM and the reasoning LLM are never co-resident, with each other or
> with anything else large.** On 12 GB, `2.5 GB + 3.2 GB` is over the ceiling and
> the process dies.

The parent project already solved this and the pattern transfers exactly: the
Moondream2 integration loads the model only for an explicit snapshot request,
holds a single non-queueing worker, guards on free memory before loading, and
**unloads before the response is returned**. Target tracking only starts after
the unload, on CPU state.

Reimplement that pattern verbatim. It was designed for a 8 GB VRAM budget and it
is exactly what a 12 GB phone needs.

---

## 5. Memory architecture

### 5.1 Three residency classes

**Class A — resident for the session.**
Detection and segmentation. Loaded when Walk Mode starts, unloaded when it ends.
Never unloaded mid-walk, because a reload stall is a gap in safety coverage.

**Class B — load on demand, single occupancy, unload before return.**
OCR and the VLM. At most one Class B model is in memory at any instant. The
sequence is strictly:

```
check free memory  →  refuse if below floor
       ↓
load model
       ↓
run exactly one inference
       ↓
unload model, release native buffers
       ↓
return the result to the caller
```

**MUST:** the result is returned *after* the unload, not before. Returning first
and unloading asynchronously creates a window where a second request can arrive
and double the footprint.

**Class C — never on device.**
The reasoning LLM on a 12 GB unit. Marked stretch, and on 12 GB the honest answer
is probably no.

### 5.2 The free-memory floor

Before any Class B load:

```kotlin
// Refuse rather than risk the low-memory killer taking the process.
val info = ActivityManager.MemoryInfo()
activityManager.getMemoryInfo(info)
val requiredBytes = model.estimatedFootprintBytes + SAFETY_MARGIN_BYTES
if (info.availMem < requiredBytes || info.lowMemory) {
    return ModeResult.Unavailable(reason = "INSUFFICIENT_MEMORY")
}
```

> **MUST:** `SAFETY_MARGIN_BYTES` is not optional and is not tuned down to make a
> demo work. Set it at 800 MB. A refused Scene query is a minor disappointment;
> a killed process mid-walk is a safety failure.

The refusal **MUST** be spoken, not silent: *"Scene mode is not available right
now."* The user is told, and Walk Mode continues uninterrupted.

### 5.3 Frame buffers

- A ring of **3** frame buffers, reused. No per-frame allocation in the hot path.
- Frames are `ImageProxy` → NV21/YUV → the model's expected input, converted in
  place where possible.
- **MUST NOT** allocate a new `Bitmap` per frame. At 5–10 fps that is a garbage
  collection storm that will show up as stutter in the guidance cadence.

---

## 6. Model selection and fallback ladders

Every stage has a ladder. Start at the top. Drop one rung when a gate fails.
**MUST:** never skip straight to the bottom rung to save time — each rung down
costs real capability that the demo depends on.

### 6.1 Detection

| Rung | Model | Notes |
|---|---|---|
| 1 | **YOLOv8-det** (AI Hub) | First choice. AI Hub lists Snapdragon 8 Elite Gen 5. |
| 2 | **YOLOX** (AI Hub) | Equivalent role, different operator coverage. Try if YOLOv8 fails conversion. |
| 3 | YOLOv5 (AI Hub) | Older, broadest operator support. Last NPU option. |
| 4 | Any of the above on GPU | Slower, hotter, still acceptable. |
| 5 | CPU | **Demo-only fallback.** Announce it as degraded. |

**Output required:** boxes in normalized coordinates, class label, confidence.
The 19-class risk set (Appendix B) is a *filter applied after inference*, not a
retrained model. Run the full COCO output and filter twice, exactly as the parent
project does — the risk set feeds tracking and guidance, while the full set feeds
landmark memory so a user's spoken word is not discarded by aliasing.

### 6.2 Segmentation

| Rung | Model | Notes |
|---|---|---|
| 1 | Small AI Hub segmentation model, ADE20K-like labels | Ideal — direct label mapping from the existing logic. |
| 2 | Any AI Hub segmentation model + a hand-written label map | Costs a mapping table, not architecture. |
| 3 | Reduced cadence — segment every 3rd frame | See §16.3. Buys thermal headroom. |
| 4 | **Geometric floor estimate, no segmentation** | Corridor costs from detection footprints and a horizon prior. Degrades honestly. |

> **MUST:** rung 4 sets `degraded_modules = ["segmentation"]` and the user is
> told. The parent project's `PAUSE_UNCLEAR` behaviour exists precisely for this
> case — with no surface evidence, most frames should resolve to `PAUSE_UNCLEAR`
> rather than a confident corridor.

**Label mapping required.** The existing logic needs these classes:

| Existing `SurfaceKind` | ADE20K sources |
|---|---|
| `WALKABLE` | floor, road, sidewalk, path, rug, earth |
| `ROAD` | road, highway (kept separate — road is walkable-but-dangerous) |
| `NON_WALKABLE` | wall, building, furniture, stairs, water, fence |
| `UNKNOWN` | everything else, and low-confidence pixels |

### 6.3 OCR

| Rung | Model | Notes |
|---|---|---|
| 1 | **PaddleOCR** via NexaSDK | Depends on gate 4.2.1. |
| 2 | AI Hub OCR / text-detection model | Fallback if Nexa is unavailable. |
| 3 | **ML Kit text recognition** (on-device, Google) | Boring, reliable, genuinely on-device, no NPU claim. |
| 4 | Cut Explore Mode | It is a Should, not a Must. |

> Rung 3 is worth naming clearly: ML Kit runs on-device and will work. It does
> not run on the NPU, so it does not strengthen the on-device-AI story, but it
> keeps the feature alive. Use it rather than losing Explore Mode entirely, and
> be honest about what it is if a judge asks.

### 6.4 Scene VLM

| Rung | Model | Notes |
|---|---|---|
| 1 | **Qwen3-VL-2B-Instruct** | AI Hub lists Snapdragon 8 Elite support. ~2.5 GB peak. |
| 2 | Detection-derived scene summary | Compose a sentence from the detection list — no VLM at all. |
| 3 | Cut Scene Mode | Nice-to-have tier. |

> **Do not plan around Qwen3-VL-4B.** The parent project's research already
> caught its AI Hub page simultaneously listing 8 Elite Gen 5 as supported and
> stating *"This model is currently not supported on any Mobile chipset."* That
> contradiction is exactly the trap this ladder exists to avoid.

Rung 2 deserves respect. "A person ahead on the left, a chair to the right, a
doorway centre" composed from detections is genuinely useful, costs no memory,
and cannot fail. If the VLM does not land, this is a good answer, not a
consolation prize.

---

## 7. Runtime and deployment path

### 7.1 The pipeline that actually matters

The NPU is not a generic accelerator. A model reaches it through:

```
open-source model
      ↓  compatible architecture and operators
      ↓  quantization  (INT8 / INT4; 8 Elite Gen 5 also offers INT2, FP8)
      ↓  conversion to a Qualcomm-compatible runtime format
      ↓  device-specific optimization
   Hexagon NPU
```

A model can be small enough, quantized enough, and still fail — unsupported
operators, unsupported attention implementation, dynamic shapes, incompatible
quantization. **This is why the AI Hub catalogue is the starting point rather
than Hugging Face:** it answers *supported chipset, supported runtime, supported
precision, known performance* instead of leaving it to be discovered at hour 14.

### 7.2 Two candidate paths

**Path A — Qualcomm AI Hub + QNN / QAIRT.**
Pre-optimized, pre-validated models. Highest confidence for the vision models.
This is the primary path for detection and segmentation.

**Path B — NexaSDK for Android.**
A unified interface across CPU/GPU/NPU backends, covering LLMs, VLMs, OCR, ASR.
Attractive for OCR and the VLM in one dependency. **Gated on 4.2.1 (the 16 GB
floor).**

> **SHOULD:** vision on Path A, on-demand models on Path B if the gate passes.
> Do not make the walk loop depend on Path B.

### 7.3 Proving the NPU is actually being used

This matters for scoring — the 15% is for *on-device AI*, and a judge may
reasonably ask whether it is the NPU or a CPU fallback. It also matters for
engineering, because a silent CPU fallback will pass functional testing and then
melt the phone at hour 20.

> **MUST:** build an on-screen diagnostics panel by hour 8 showing, per stage:
> the backend actually in use (NPU / GPU / CPU), inference milliseconds, rolling
> FPS, resident memory, and the current thermal status.
>
> **MEASURE 7.3.1** — Does inference time drop by roughly an order of magnitude
> when the NPU backend is selected versus CPU? If not, the NPU is not being used
> regardless of what the API reports.

This panel is also the best possible demo prop. Toggling backends live and
showing the millisecond count collapse is a more convincing argument for
technical depth than any slide.

---

## 8. Pipeline architecture

### 8.1 The walking loop

```
CameraX ImageAnalysis  (backpressure: KEEP_ONLY_LATEST)
        │
        ▼
  Frame ring buffer  ─────────────────────────────┐
        │                                          │
        ▼                                          │
  Orientation correction  ──►  FrameGeometry       │
        │                                          │
        ├──────────────────┬───────────────────┐   │
        ▼                  ▼                   │   │
  Detection (NPU)    Segmentation (NPU)         │   │
  every frame        every Nth frame            │   │
        │                  │                   │   │
        ▼                  ▼                   │   │
  DetectionResult[]   SurfaceRegion[]           │   │
        │                  │                   │   │
        └────────┬─────────┘                   │   │
                 ▼                             │   │
         Tracker  (CPU, session-scoped)         │   │
                 │  motion, approach state      │   │
                 ▼                             │   │
         Spatial reasoning  (CPU)               │   │
                 │  proximity bands             │   │
                 │  corridor costs              │   │
                 ▼                             │   │
         Risk engine  (CPU)                     │   │
                 │  RiskLevel per detection     │   │
                 │  frame-level risk            │   │
                 ▼                             │   │
         Guidance state machine  (CPU)          │   │
                 │  hysteresis, preemption      │   │
                 ▼                             │   │
      ┌──────────┼──────────┬─────────────┐    │   │
      ▼          ▼          ▼             ▼    │   │
   Speech    Haptics   Spatial audio   Overlay ◄┘   │
                                          │         │
                                          └─────────┘
                                        (preview transform)
```

### 8.2 What changed versus the current system

The structure is identical. That is the point — the pipeline is proven, and only
its execution location moves. Three things are genuinely different:

1. **The JPEG encode is gone.** No compression, no multipart, no decode. The
   camera buffer goes to the model. This alone removes 20–40 ms per frame.
2. **The freshness gate changes meaning.** It exists today because a response
   could arrive describing a frame from 800 ms ago. With no transport, staleness
   comes only from processing overrun. Keep the gate — it now guards against
   thermal slowdown instead of network lag.
3. **Segmentation cadence becomes a tunable.** On a laptop GPU both models ran
   every frame. On the phone, decoupling them is the main thermal lever (§16.3).

### 8.3 Frame lifecycle

| Step | Owner | Budget |
|---|---|---|
| Acquire + convert YUV | Camera thread | 10 – 20 ms |
| Preprocess (letterbox, normalize) | Inference thread | 5 – 15 ms |
| Detection inference | NPU | **MEASURE**, target < 20 ms |
| Segmentation inference | NPU | **MEASURE**, target < 40 ms |
| Track + spatial + risk + guidance | Compute thread | 5 – 15 ms |
| Emit outputs | Main / audio | < 5 ms |
| **Total** | | **target ≤ 120 ms → ≥ 8 fps** |

> **MEASURE 8.3.1** — sustained end-to-end frame time over a 10-minute walk, not
> a cold single-shot benchmark. The cold number will look great and is not the
> number that matters.
>
> **Decision rule:** sustained ≥ 8 fps → ship as designed. 4–8 fps → drop
> segmentation to every 3rd frame. < 4 fps → drop segmentation to rung 4 of §6.2
> and announce degradation.

---

## 9. Stage specifications

### 9.1 Capture

- **CameraX `ImageAnalysis`**, `STRATEGY_KEEP_ONLY_LATEST`. Never queue frames;
  a queued frame is a stale frame and stale guidance is dangerous.
- Resolution: request the smallest that preserves detection quality.
  **MEASURE** — start at 640×480, step up only if small-obstacle recall suffers.
- Format: YUV_420_888, converted once.
- **MUST:** `ImageProxy.close()` on every path including exceptions. A leaked
  proxy stalls the camera pipeline within a handful of frames, and the symptom
  (the app freezes after ~5 seconds) looks nothing like the cause.

### 9.2 Orientation correction

Produces `FrameGeometry`:

```typescript
{
  coordinate_space: "ORIENTED_CAPTURE_NORMALIZED",
  source_width: number,
  source_height: number,
  rotation_degrees: 0 | 90 | 180 | 270,
  mirrored: false
}
```

- **MUST** apply rotation *before* inference, so model coordinates are already in
  the oriented frame. Correcting after inference means every downstream consumer
  needs the rotation, and one of them will forget.
- `mirrored` is `false` always — the rear camera is the only camera used.

### 9.3 Detection

**Input:** oriented frame, letterboxed to the model's input size.
**Output:** `DetectionResult[]` (Appendix A).

- Boxes **MUST** be emitted in `ORIENTED_CAPTURE_NORMALIZED` space — normalized
  against the full oriented capture, **not** the letterboxed tensor. Un-letterbox
  before normalizing. This is the single most common source of overlay
  misalignment and it is silent: boxes will be drawn slightly wrong in a way that
  looks like camera jitter.
- Confidence floor: **MEASURE**, start at 0.35.
- NMS IoU: 0.45.
- Filter to the 19-class risk set for guidance; retain the full COCO output for
  landmark memory.

### 9.4 Segmentation

**Input:** oriented frame, model input size (typically smaller than detection).
**Output:** `SurfaceRegion[]` with `NormalizedPolygon` geometry.

- Run the label map from §6.2.
- Contour extraction: threshold the class mask, find contours, simplify with
  Douglas–Peucker, emit as normalized polygons.
- **MUST** cap polygon vertex count. An unsimplified mask contour can carry
  thousands of points; that will stall the overlay renderer. Cap at 40 vertices
  per polygon.
- Low-confidence pixels map to `UNKNOWN`, not to `WALKABLE`. **Defaulting
  uncertainty to walkable is a §3 violation** — it manufactures confidence the
  model did not express.

### 9.5 Tracking

Port of the existing session-scoped tracker.

- Associate detections across frames by IoU + class identity.
- Maintain per-track: id, class, box history (last N=10), first-seen, last-seen.
- Derive `MotionVector` from the box-centre trajectory.
- Derive `ApproachState` from box-area growth:
  - area growing beyond a threshold → `APPROACHING`
  - area shrinking → `RECEDING`
  - within the deadband → `STATIONARY`
  - insufficient history (< 3 frames) → `UNKNOWN`
- **MUST** run on CPU. It is trivially cheap and there is no reason to involve
  an accelerator.

### 9.6 Spatial reasoning

- **Proximity band** from box geometry — box height relative to frame height,
  vertical position relative to the horizon prior. Purely relative.
  **MUST NOT** be converted into or presented as a metric distance.
- **Direction** — `LEFT` / `CENTRE` / `RIGHT` from the box centre against
  corridor thirds; `UNKNOWN` when it straddles a boundary ambiguously.
- **Corridor costs** — for each of left/centre/right, accumulate cost from
  blocking detections weighted by proximity band, plus non-walkable surface
  fraction from segmentation.

Emitted as `CorridorCosts { left_cost, centre_cost, right_cost }`.

---

## 10. The coordinate transform contract

> This section describes something already solved and working. It is here so the
> rebuild does not break it. **Read it before touching the overlay.**

### 10.1 The three coordinate spaces

1. **Model tensor space** — letterboxed, padded, model-specific. Never leaves the
   inference stage.
2. **`ORIENTED_CAPTURE_NORMALIZED`** — the full orientation-corrected capture,
   normalized to `[0,1]`. **This is the wire contract.** Everything downstream
   speaks it.
3. **Preview space** — actual pixels in the `PreviewView`, which is cropping
   and/or scaling the capture to fit the screen.

### 10.2 The rules

- **MUST** un-letterbox before normalizing. The padding is not part of the image.
- **MUST** normalize against the *oriented* dimensions, applying the rotation
  swap: at 90° or 270°, source width and height exchange roles.
- **MUST** apply the preview transform (`COVER` or `CONTAIN`) only at draw time
  in `PreviewTransform.kt`. No other component knows about preview geometry.
- **MUST NOT** let the preview crop leak backwards into detection coordinates.
  Guidance reasons about the full capture; the user's screen shows a crop of it.
  They are different, and conflating them shifts every box.

### 10.3 The test that catches it

Draw a box at exactly `(0.0, 0.0, 1.0, 1.0)` in normalized space. It **MUST**
trace the full oriented capture. Under a `COVER` preview it will extend beyond
the visible viewport on the cropped axis — that is correct and expected. If it
sits neatly inside the screen edges, the crop has leaked backwards and every
detection is wrong by the same factor.

Run this test in portrait *and* landscape before trusting any overlay.

---

## 11. Spatial reasoning port

### 11.1 Corridor occupancy

The frame divides into three vertical corridors. For each, cost accumulates from:

| Source | Contribution |
|---|---|
| Detection in corridor, `IMMEDIATE` | very high |
| Detection in corridor, `NEAR` | high |
| Detection in corridor, `MEDIUM` | moderate |
| Detection in corridor, `FAR` | low |
| `APPROACHING` state | multiplier on the above |
| Non-walkable surface fraction | proportional |
| `UNKNOWN` surface fraction | **raises uncertainty, not cost** |

That last row is the subtle one and it is a §3 requirement. Unknown surface does
not make a corridor *blocked*; it makes the frame *unclear*. High unknown
fraction across all three corridors resolves to `PAUSE_UNCLEAR`, not to a
confident pick of whichever corridor scored least.

### 11.2 Preferred corridor

```
if all three corridors exceed the block threshold        → STOP
if unknown fraction is high across the frame             → PAUSE_UNCLEAR
if centre is clear                                       → CENTRE (prefer straight)
if exactly one side is clearly better than the other     → that side
otherwise                                                → PAUSE_UNCLEAR
```

**MUST:** prefer `CENTRE` when it is viable. Steering a blind user sideways
without cause is disorienting and erodes trust in the system.

### 11.3 Indoor structure detection

The parent project's Phase 8 work added these and they are among its most useful
outputs:

- **Frontal wall / dead end** — high non-walkable fraction across the full frame
  width at the lower-middle band, stable across consecutive frames → stabilizes
  to `WALL_OR_DEAD_END_AHEAD`.
- **Stairs / level change** — the segmentation stairs class, or a strong
  horizontal-edge band in the floor region → `STAIRS_OR_LEVEL_CHANGE_AHEAD`.
- **Side wall with open forward path** — high non-walkable on one side but a
  viable centre → do **not** stop; guide centre.

> **MUST:** both stop conditions require **stabilization across consecutive
> frames** before they are announced. A single-frame segmentation flicker
> announcing "stairs ahead" to a walking blind user is exactly the confident
> wrong answer §3.2 forbids. Require 3 consecutive frames.

---

## 12. Risk engine port

### 12.1 Per-detection risk

Inputs: class, proximity band, approach state, direction, confidence.

```
base risk        ← class severity from the 19-class table (Appendix B)
proximity        ← escalates: FAR → MEDIUM → NEAR → IMMEDIATE
approach         ← APPROACHING escalates one level; RECEDING de-escalates one
direction        ← CENTRE escalates relative to LEFT / RIGHT
confidence       ← below the floor, cap the contribution rather than dropping it
                   entirely (a low-confidence obstacle is still evidence)
```

Result: `RiskLevel` ∈ `CLEAR | WATCH | WARN | HIGH | CRITICAL` per detection.

### 12.2 Frame-level risk

The frame takes the **maximum** of its detection risks, then adjusts:

- Corridor costs indicating no viable path → escalate to at least `HIGH`.
- High unknown fraction → do not escalate; route to `PAUSE_UNCLEAR` instead.
  Uncertainty is not the same as danger, and conflating them makes the system
  cry wolf until the user stops listening.

### 12.3 Why maximum and not a sum

A summed score lets three `FAR` chairs outweigh one `IMMEDIATE` person. The
maximum is correct: the user needs to know about the worst thing in front of
them, and the presence of additional distant clutter does not change what to do
about it.

---

## 13. Guidance state machine

### 13.1 The action vocabulary

Frozen in `packages/contracts`. **MUST NOT** be extended without also updating
the dashboard, the speech strings, and the haptic map.

| Action | Meaning |
|---|---|
| `CLEAR` | Path ahead is viable. Minimal or no speech. |
| `CAUTION` | Something is present and worth knowing about. Continue. |
| `MOVE_LEFT` | Centre is blocked, left is viable. |
| `MOVE_RIGHT` | Centre is blocked, right is viable. |
| `STOP` | No viable path, or an immediate hazard. |
| `PAUSE_UNCLEAR` | Evidence is weak or contradictory. **Not a failure state.** |

Plus two indoor stop reasons carried in `reason_code`:
`WALL_OR_DEAD_END_AHEAD`, `STAIRS_OR_LEVEL_CHANGE_AHEAD`.

### 13.2 Haptic mapping

| Action | Pattern |
|---|---|
| `CLEAR` | `NONE` |
| `CAUTION` | `CAUTION_SHORT` |
| `MOVE_LEFT` / `MOVE_RIGHT` | `WARNING_DOUBLE` |
| `STOP` | `CRITICAL_RAPID` |
| `PAUSE_UNCLEAR` | `UNCLEAR_LONG` |

> `PAUSE_UNCLEAR` has its own distinct long pattern deliberately. The user must
> be able to feel the difference between *"stop, there is a hazard"* and *"I
> don't know what I'm looking at."* Those call for different human responses.

### 13.3 Hysteresis

Raw per-frame output flickers. Speaking every flicker is unusable.

- **Escalation is immediate.** Rising to `STOP` takes effect on the frame that
  produces it. Never delay a stop.
- **De-escalation requires stability.** Dropping from `STOP` to `CLEAR` requires
  **3 consecutive** frames of the lower level.
- **Speech cooldown:** the same action is not re-spoken within 2.5 s unless the
  level escalated. **MEASURE** on a real walk — too long feels unresponsive, too
  short is chatter.
- **Haptics are not subject to the speech cooldown.** They are cheap, fast, and
  do not compete for the speech channel.

The asymmetry between escalation and de-escalation is the whole design: fast to
warn, slow to reassure.

### 13.4 Preemption

```
priority 1  Walk safety guidance      (STOP, PAUSE_UNCLEAR, MOVE_*)
priority 2  Target guidance           (Ask → Lock → Guide)
priority 3  Scene answers, OCR results
priority 4  Ambient / status
```

**MUST:** priority 1 interrupts anything below it mid-utterance. Do not wait for
a scene description to finish before saying "stop."

---

## 14. Output layer — speech, haptics, spatial audio

All of this already exists in `apps/android/.../feedback/` and carries over. What
changes: it is now driven by an in-process guidance object instead of a parsed
HTTP response. The interface should be identical.

- **`SpeechEngine`** — TTS, tri-lingual (English, Hindi, Tamil), with
  `SpokenLanguage` selection. Strings in `GuidanceStrings.kt` and the `values-*`
  resource files.
- **`HapticEngine`** — the five patterns above.
- **`SpatialAudioEngine`** + **`SonarMapping`** — directional cue rendering.
- **`AudioFocusManager`** — **MUST** duck rather than stop the user's own audio.
  Blind users very often have music or a podcast running; killing it is hostile.
- **`GyroSteering`** — interpolates directional cues between inference results.
  With a faster on-device loop this matters less than it did, but it still
  smooths the experience and it is already written.

> **MUST:** these run on their own thread and never block the inference loop.

---

## 15. Threading and concurrency model

| Thread | Responsibility | Rules |
|---|---|---|
| Main / UI | Compose, overlay draw | Never blocks. Receives immutable snapshots. |
| Camera | `ImageAnalysis` callback | Converts, hands off, closes the proxy. Fast. |
| Inference | NPU calls | One at a time. Serialized. |
| Compute | Track, spatial, risk, guidance | Pure Kotlin, no I/O. |
| Output | Speech, haptics, audio | Own thread. Isolated from everything. |
| Bridge | Office Kit hazard sync | **Fully detached.** Fire-and-forget. |

### 15.1 Rules

- **MUST NOT** allow the bridge thread to block anything. If the laptop is gone,
  the walk continues without noticing. Enforce with a bounded queue that drops
  on overflow, never blocks on enqueue.
- **MUST** serialize NPU access. Two concurrent inference calls on the same
  backend will at best serialize internally and at worst fault.
- **MUST** pass immutable snapshots between stages. A shared mutable detection
  list being read by the overlay while the tracker mutates it is a crash that
  will only appear under load — which is to say, during the demo.
- The foreground service (`WalkForegroundService`) keeps the loop alive with the
  screen off. That is already implemented and **MUST** be preserved: a blind user
  has no reason to keep a screen lit, and the battery saving is substantial.

---

## 16. Thermal and sustained performance

### 16.1 Why this is a first-class concern

A 30-hour hackathon demo happens at the end, on a phone that has been running
inference all weekend, in a crowded warm room. Cold benchmarks are irrelevant.
Sustained thermal behaviour is what a live demonstration actually exercises.

### 16.2 Monitoring

```kotlin
val status = powerManager.currentThermalStatus
// THERMAL_STATUS_NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
```

Poll every 5 s. Surface it in the diagnostics panel from §7.3.

### 16.3 The response ladder

| Thermal status | Response |
|---|---|
| `NONE`, `LIGHT` | Full rate. Detection every frame, segmentation every 2nd. |
| `MODERATE` | Segmentation every 4th frame. Cap capture at 5 fps. |
| `SEVERE` | Segmentation off (rung 4 of §6.2). Announce degradation. Detection only. |
| `CRITICAL`+ | Suspend Walk Mode. **Tell the user out loud.** Do not fail silently. |

> **MUST:** the `CRITICAL` path speaks. A blind user walking with a phone that
> has quietly stopped analysing is in a materially more dangerous position than
> one who knows to stop and rely on their cane.

### 16.4 Practical mitigations

- Keep the screen off during the walk — the foreground service already allows it,
  and the display is a significant thermal contributor.
- Do not charge while demoing. Charging and sustained NPU load together will
  throttle much faster.
- **MEASURE 16.4.1** — run a 20-minute continuous walk and record frame time at
  minutes 1, 5, 10, 20. The delta between minute 1 and minute 20 is the number
  that determines whether the demo holds up.

---

## 17. On-demand modes — Explore and Scene

Both are **Class B** (§5.1): load, one inference, unload, then return.

### 17.1 Explore Mode — read a sign

- Trigger: an explicit gesture. Never automatic, never in the walk loop.
- Capture one frame, run OCR, extract text and any route-number token
  (`BUS 42A CENTRAL` → route `42A`).
- Output `ReadTextResponse`, including an `OcrConfidenceQualification` of
  `HIGH | LOW | NONE`.
- **MUST** speak the qualification, not just the text. *"Low confidence: bus four
  two A"* is honest; reading it flatly implies a certainty the model did not have.
- **MUST NOT** block Walk Mode. Walk guidance continues throughout.

### 17.2 Scene Mode — ask about what is in front

- Trigger: explicit gesture, then a spoken question.
- Memory check (§5.2) → load VLM → one inference → unload → answer.
- **MUST** refuse gracefully and audibly when memory is insufficient.
- **MUST** be preempted by Walk safety guidance mid-answer (§13.4).
- Timeout: **MEASURE**, start at 15 s. On timeout, unload and apologise.

> **MUST NOT** put the VLM in the continuous loop under any circumstance. This is
> both a memory rule and a §3 rule — a 2 B VLM's latency is far too high to
> produce safety guidance, and any architecture that lets it try will eventually
> speak a stale answer about a scene the user has already walked past.

---

## 18. The Office Kit bridge

### 18.1 What it is for

Two things, both optional, neither in the safety path:

1. **Hazard reports** — the walker reports an obstacle; the report reaches the
   coordinator dashboard on the laptop.
2. **Demo mirroring** — screen-mirror the phone for a live demonstration, so an audience can
   see the overlay and the diagnostics panel.

### 18.2 The rule that defines it

> **MUST:** the walking experience is fully functional with the laptop absent,
> powered off, or out of range. The bridge is a side channel. Nothing in the
> guidance loop reads from it, waits on it, or checks whether it is connected.

This is what makes the Red Light round survivable, and it is the demo's whole
argument. Build it detached from day one — retrofitting independence is much
harder than starting with it.

### 18.3 Implementation

- Bounded queue, capacity ~50, **drop-oldest** on overflow.
- Sync attempts on a detached coroutine with a short timeout.
- Failure is logged to the diagnostics panel, never surfaced as a user-facing
  error mid-walk.
- Evidence JPEGs only after the explicit consent gesture (§3.3).

### 18.4 What the bridge is not

It is not a fallback path for guidance. If the on-device pipeline degrades, the
response is §19's ladder, not a request to the laptop. Reintroducing a network
dependency under failure conditions would restore precisely the coupling this
rebuild exists to remove — and it would do so at the moment the user is least
able to tolerate it.

---

## 19. Degradation ladder

The order in which capability is surrendered under time or thermal pressure.
**Descend in order. Never skip.**

| # | State | User told? | Still useful? |
|---|---|---|---|
| 0 | Everything: detection, segmentation, OCR, VLM | — | Full product |
| 1 | Drop VLM Scene Mode | On request only | Yes |
| 2 | Drop OCR Explore Mode | On request only | Yes |
| 3 | Segmentation every 4th frame | No | Yes, slightly coarser |
| 4 | Segmentation off, geometric floor estimate | **Yes, spoken** | Yes, more `PAUSE_UNCLEAR` |
| 5 | Detection on GPU instead of NPU | No | Yes, hotter |
| 6 | Detection on CPU | **Yes, spoken** | Barely — demo only |
| 7 | Walk Mode suspended | **Yes, spoken, insistent** | No |

> **MUST:** every level from 4 down is announced. The user's safety decisions
> depend on knowing what the system can currently see, and a silently degraded
> assistant is worse than an honestly absent one.

---

## 20. The 30-hour schedule

Chennai: clock starts Saturday 10:00, active hacking from 11:00, awards Sunday
~17:00. Two scored evaluation rounds plus a Top 10 pitch.

### Saturday

| Hours | Work | Gate |
|---|---|---|
| 10:00–11:00 | Check-in, teach-in, device handover, Office Kit pairing | — |
| 11:00–13:00 | **Device recon.** RAM variant. Custom APK install. SDK availability. NPU reachable at all. | **GATE 1** |
| 13:00–16:00 | Detection on NPU. Diagnostics panel. Prove NPU ≠ CPU. | **GATE 2** |
| 16:00–19:00 | Segmentation on NPU. Camera pipeline wired end to end. Overlay correct. | — |
| 19:00–20:00 | **Evaluation round 1** — show live on-device detection | — |
| 20:00–00:00 | Port tracker, spatial reasoning, risk engine to Kotlin | — |

### Sunday

| Hours | Work | Gate |
|---|---|---|
| 00:00–04:00 | Guidance state machine. Reconnect speech and haptics. | — |
| 04:00–07:00 | **Airplane-mode walk test.** End-to-end, no network. | **GATE 3** |
| 07:00–09:00 | Office Kit bridge, dashboard live, hazard reporting | — |
| 09:00–10:00 | **Evaluation round 2** | — |
| 10:00–13:00 | OCR if green; VLM only if everything above is solid | — |
| 13:00–15:00 | **Freeze.** Rehearse the demo. Thermal soak test. | — |
| 15:00–17:00 | Top 10 pitch, awards | — |

### The gates

- **GATE 1 (13:00 Sat)** — if custom APKs cannot be installed or the NPU is
  unreachable, the entire plan changes and there are still 21 hours to pivot.
  **This is why device recon is first and nothing else starts before it.**
- **GATE 2 (16:00 Sat)** — if detection is not on the NPU by hour 5, drop the VLM
  and OCR from the plan immediately and spend the time on the walk loop. Do not
  carry two stretch goals past this point.
- **GATE 3 (07:00 Sun)** — if the airplane-mode walk does not work, stop all
  feature work and fix it. **This single test is the entire pitch.** A polished
  app that needs Wi-Fi has lost.

### Scheduling notes

- The rehearsal block is not padding. A demo that has never been rehearsed will
  fail in a room full of judges in a way it never failed on the bench.
- Sleep in shifts. The 00:00–04:00 block is the highest-risk work in the schedule
  and it is scheduled for the middle of the night; make sure whoever owns the
  risk-engine port is not the person who has been awake for 20 hours.

---

## 21. Verification plan

### 21.1 The one test that matters

> **Airplane mode. Walk a real corridor. Guidance continues, correctly, for ten
> minutes.**

If this passes, the architecture holds. If it does not, nothing else compensates.
Run it at GATE 3 and again after the freeze.

### 21.2 Functional checks

| # | Test | Pass condition |
|---|---|---|
| 1 | Clear hall | Reaches `CLEAR`, safe polygon on the floor |
| 2 | Frontal wall | Stabilizes to `WALL_OR_DEAD_END_AHEAD`, no movement cue into it |
| 3 | Side wall, open centre | Guides `CENTRE`, does **not** stop |
| 4 | Stairs | Stabilizes to `STAIRS_OR_LEVEL_CHANGE_AHEAD` |
| 5 | Person approaching | Escalates as they close; `APPROACHING` set |
| 6 | Obstacle centre, left clear | `MOVE_LEFT` |
| 7 | Camera covered | `PAUSE_UNCLEAR`, never a confident direction |
| 8 | Lens smeared / low light | `PAUSE_UNCLEAR`, degradation announced |
| 9 | Overlay alignment | `(0,0,1,1)` box traces full oriented capture, both orientations |
| 10 | Airplane mode | Full function, no error, no retry storm |
| 11 | Screen off | Guidance continues via foreground service |
| 12 | 20-minute soak | Frame time at minute 20 within 2× of minute 1 |
| 13 | Scene Mode under memory pressure | Refuses audibly, walk continues |
| 14 | Laptop powered off mid-walk | No interruption whatsoever |

> Tests 7 and 8 are the ones most likely to be skipped and are the most important
> in the set. They verify the system admits uncertainty rather than inventing
> confidence, which is the §3 behaviour that separates this from a demo toy.

### 21.3 Physical testing safety

**MUST NOT** test blindfolded. The parent project's rules are explicit: physical
testing uses controlled environments and never involves unsafe blindfolded
walking. A sighted tester holds the phone and evaluates whether the guidance
*would have been* correct and sufficient.

---

## 22. Unknowns to resolve on-site, in order

Work top to bottom. Each answer changes what is worth attempting below it.
**Nothing else starts until items 1–4 are answered.**

| # | Question | If the answer is bad |
|---|---|---|
| 1 | RAM variant — 12 or 16 GB? | 12 GB → this document as written. |
| 2 | Can we install a custom APK? | No → the entire plan is void. Escalate to organisers immediately. |
| 3 | Can custom model files be loaded? | No → AI Hub prebuilt only, no custom quantization. |
| 4 | Is the NPU reachable from a third-party app? | No → GPU path, and the on-device-AI pitch weakens sharply. |
| 5 | Which SDK/runtime do organisers provide? | Shapes §7 entirely. |
| 6 | Is internet available during setup? | No → models must be pre-staged on the laptop before arrival. |
| 7 | Does NexaSDK initialise on 12 GB? | No → §6.3 rung 2/3, §6.4 rung 2. |
| 8 | Is AI Hub / GenieX reachable during the event? | No → pre-download every candidate model beforehand. |
| 9 | Can NPU telemetry be shown to judges? | No → the diagnostics panel becomes the only evidence. |
| 10 | Does HackTracker distinguish NPU from CPU inference? | Unknown → assume not; rely on the diagnostics panel as evidence. |

> **Item 6 deserves emphasis: pre-stage every candidate model on the laptop
> before travelling to Chennai.** Downloading multi-gigabyte model files over
> venue Wi-Fi shared with a thousand builders is a way to lose four hours at
> exactly the moment they are worth the most. Bring them on disk. This is
> preparation, not pre-built code, and it is entirely within the rules.

---

## Appendix A — preserved contract types

From `packages/contracts/src/index.ts`. The rebuilt pipeline **MUST** produce
these shapes. The dashboard and Android client are already written against them.

```typescript
type RiskLevel     = "CLEAR" | "WATCH" | "WARN" | "HIGH" | "CRITICAL";
type Direction     = "LEFT" | "CENTRE" | "RIGHT" | "UNKNOWN";
type ProximityBand = "FAR" | "MEDIUM" | "NEAR" | "IMMEDIATE" | "UNKNOWN";
type ApproachState = "APPROACHING" | "RECEDING" | "STATIONARY" | "UNKNOWN";
type SurfaceKind   = "WALKABLE" | "ROAD" | "NON_WALKABLE" | "UNKNOWN";
type DisplayColor  = "GREEN" | "YELLOW" | "RED" | "GREY";

type CoordinateSpace   = "ORIENTED_CAPTURE_NORMALIZED";
type PreviewResizeMode = "COVER" | "CONTAIN";

interface CorridorCosts {
  left_cost: number;
  centre_cost: number;
  right_cost: number;
}

interface OverlayContract {
  coordinate_space: CoordinateSpace;
  preferred_corridor: "LEFT" | "CENTRE" | "RIGHT" | "NONE";
  safe_polygons: NormalizedPolygon[];
  blocked_polygons: NormalizedPolygon[];
  uncertain_polygons: NormalizedPolygon[];
  direction_arrow: "LEFT" | "RIGHT" | "STOP" | "NONE";
  valid_until: Timestamp;
}

interface GuidanceContract {
  level: RiskLevel;
  action: "CLEAR" | "CAUTION" | "MOVE_LEFT" | "MOVE_RIGHT" | "STOP" | "PAUSE_UNCLEAR";
  speech: string;
  haptic_pattern: "NONE" | "CAUTION_SHORT" | "WARNING_DOUBLE" | "CRITICAL_RAPID" | "UNCLEAR_LONG";
  speak: boolean;
  reason_code: string;
}

interface StageTimings {
  decode_ms: number;
  detection_ms?: number | null;
  segmentation_ms?: number | null;
  tracking_depth_ms?: number | null;
  spatial_ms?: number | null;
  risk_ms?: number | null;
  total_ms: number;
}
```

### One contract change the rebuild requires

`ComputeDevice` is currently `"CUDA" | "CPU" | "NONE"`. On-device it becomes:

```typescript
type ComputeDevice = "NPU" | "GPU" | "CPU" | "NONE";
```

This is the **only** contract change permitted without a written decision.
Everything else is frozen.

---

## Appendix B — the 19-class risk set

Filtered from full COCO output after inference. Severity is the base input to
§12.1; proximity, approach and direction modify it from there.

| Class | Base severity | Note |
|---|---|---|
| `person` | HIGH | Highest when `APPROACHING`. Never identified, only detected. |
| `bicycle` | HIGH | Fast, quiet, often unnoticed |
| `car` | CRITICAL | |
| `motorcycle` | CRITICAL | Fast and common in Indian street contexts |
| `bus` | CRITICAL | |
| `truck` | CRITICAL | |
| `traffic light` | INFO | Context, never a crossing instruction — see §3.1 |
| `stop sign` | INFO | Context only |
| `bench` | MEDIUM | Static trip hazard |
| `chair` | MEDIUM | The most common indoor obstacle |
| `couch` | MEDIUM | |
| `potted plant` | MEDIUM | Very common in Indian corridors and lobbies |
| `dining table` | MEDIUM | Low edges, hard to see |
| `dog` | HIGH | Unpredictable movement |
| `cow` | HIGH | Genuinely common in Indian street contexts |
| `pole` | HIGH | Narrow, easily missed by detection, painful to hit |
| `door` | INFO | Navigation landmark |
| `stairs` | CRITICAL | Level change — the highest-consequence indoor hazard |
| `backpack` | LOW | Floor clutter |

> `traffic light` and `stop sign` are **INFO and stay INFO**. Escalating them
> toward a crossing recommendation is the most tempting §3.1 violation available
> in this codebase. The system reports that a traffic light is present. It never
> reports what it means.

---

## Appendix C — reason codes

`GuidanceContract.reason_code` carries the *why* for the dashboard and for
debugging. Not spoken verbatim.

| Code | Trigger |
|---|---|
| `CLEAR_PATH` | No blocking evidence |
| `OBSTACLE_CENTRE` | Blocking detection in centre corridor |
| `OBSTACLE_APPROACHING` | Tracked object with `APPROACHING` state |
| `ALL_CORRIDORS_BLOCKED` | Every corridor over threshold |
| `WALL_OR_DEAD_END_AHEAD` | Stabilized frontal wall |
| `STAIRS_OR_LEVEL_CHANGE_AHEAD` | Stabilized stairs / level change |
| `INSUFFICIENT_SURFACE_EVIDENCE` | Unknown surface fraction too high |
| `CONTRADICTORY_EVIDENCE` | Detection and segmentation disagree |
| `LOW_LIGHT` | Frame luminance below threshold |
| `SEGMENTATION_UNAVAILABLE` | Running degraded, rung 4 |
| `THERMAL_THROTTLE` | Reduced cadence from thermal status |
| `MODEL_UNAVAILABLE` | A required model failed to load |

---

## Note on scope

The models are the tractable part of this port: each has a listed, supported
counterpart in Qualcomm's catalogue and a documented conversion path.

The behaviour is the hard part — knowing when to report uncertainty, refusing to
convert weak evidence into a confident direction, and announcing degradation
audibly. Those behaviours are what make the system usable by someone who cannot
verify its output, and they are specified here rather than left to be
rediscovered under time pressure.
