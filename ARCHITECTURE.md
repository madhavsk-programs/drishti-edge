# DRISHTI Edge — Phone-First On-Device Architecture

> **Status:** build specification for the iQOO Hackathon Chennai City Battle,
> 12–13 September 2026. The phone-first technical revision this document was
> built on has been folded in; that source file is no longer carried separately.
> **Target device:** iQOO 15, Snapdragon 8 Elite Gen 5, **16 GB RAM confirmed**
> (§4 is written against 12 GB and is therefore a floor, not a ceiling — see
> [`BUILD_PLAN.md` §4](BUILD_PLAN.md#4-the-12-gb--16-gb-fork)).
> **Purpose:** move the perception and guidance stack off a laptop GPU and onto
> the phone's Hexagon NPU, so a walking user is never dependent on a nearby
> machine.
>
> This is a **controlled migration, not a rewrite.** The Kotlin client and the
> dashboard are mature and stay. The tested Python safety behaviour is ported to
> Kotlin under golden-vector parity. The FastAPI service is narrowed to a
> coordinator that sits outside the walking path rather than owning every frame.
>
> Wire-contract changes proposed here (Appendix A) are **proposals**. They take
> effect only once recorded in `docs/DECISIONS.md` with tests in Python,
> TypeScript, and Kotlin.

---

## Table of contents

1. [How to read this document](#1-how-to-read-this-document)
2. [What is retained, refined, ported, narrowed, and excluded](#2-what-is-retained-refined-ported-narrowed-and-excluded)
3. [The non-negotiable safety contract](#3-the-non-negotiable-safety-contract)
4. [Device budget — 12 GB iQOO 15](#4-device-budget--12-gb-iqoo-15)
5. [Memory architecture](#5-memory-architecture)
6. [Model selection and gates](#6-model-selection-and-gates)
7. [Runtime and deployment path](#7-runtime-and-deployment-path)
8. [Pipeline architecture](#8-pipeline-architecture)
9. [Stage specifications](#9-stage-specifications)
10. [The coordinate transform contract](#10-the-coordinate-transform-contract)
11. [Spatial reasoning port](#11-spatial-reasoning-port)
12. [Risk engine port](#12-risk-engine-port)
13. [Guidance state machine](#13-guidance-state-machine)
14. [Target guidance — Ask, Lock, Guide](#14-target-guidance--ask-lock-guide)
15. [Output layer — speech and spatial audio](#15-output-layer--speech-and-spatial-audio)
16. [Threading and the single-accelerator scheduler](#16-threading-and-the-single-accelerator-scheduler)
17. [Thermal and sustained performance](#17-thermal-and-sustained-performance)
18. [On-demand modes — Explore and Scene](#18-on-demand-modes--explore-and-scene)
19. [The laptop coordinator and the dashboard](#19-the-laptop-coordinator-and-the-dashboard)
20. [Office Kit — development control plane](#20-office-kit--development-control-plane)
21. [Degradation ladder](#21-degradation-ladder)
22. [Build plan — dependency gates](#22-build-plan--dependency-gates)
23. [Verification plan](#23-verification-plan)
24. [Unknowns to resolve on-site, in order](#24-unknowns-to-resolve-on-site-in-order)
25. [Appendix A — contract types and proposed amendments](#appendix-a--contract-types-and-proposed-amendments)
26. [Appendix B — the 19-class risk set](#appendix-b--the-19-class-risk-set)
27. [Appendix C — reason codes](#appendix-c--reason-codes)
28. [Appendix D — the COCO label list](#appendix-d--the-coco-label-list)

---

## 1. How to read this document

### 1.1 Precedence

When two parts of this document disagree, resolve in this order:

1. **§3 Safety contract.** Nothing overrides it. Not performance, not the demo,
   not a judge's question.
2. **Appendix A, the typed contract.** The data shapes are frozen. The dashboard
   and the Android client are both already written against them, and neither is
   being rebuilt.
3. **The gates** in §22 and §24. A gate that fails changes the plan; it does not
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

The phone captures a frame, **one** NPU detector invocation produces two views of
it — an audited risk view and a full-COCO target-memory view — a segmentation
model says where the floor is if it passes its gate, pure Kotlin logic turns that
into one of six guidance actions, and the user hears the result; the laptop keeps
a small coordinator for the dashboard and never sits in that loop.

---

## 2. What is retained, refined, ported, narrowed, and excluded

### 2.1 Retained unchanged

These predate the migration and are consumed by it, not rewritten by it.

| Component | Path | Why it carries over |
|---|---|---|
| Coordinator dashboard | `apps/dashboard/` | Mature React client. Runs on the laptop. Never in the walking path. **Being retired** — see §19.5. |
| Typed contracts | `packages/contracts/` | The data shapes the pipeline must produce. Frozen except by the Appendix A proposals. |
| Android UI shell | `apps/android/.../ui/` | Compose screens, overlay canvas, gesture handling. |
| Output engines | `apps/android/.../feedback/` | Speech, spatial audio, sonar mapping, audio focus, gyro steering. |
| Camera plumbing | `apps/android/.../walk/` | CameraX capture, capture pacing, frame freshness gate. |
| Coordinate transform | `apps/android/.../ui/PreviewTransform.kt` | Already correct. Regressing it breaks the overlay. |

### 2.2 Refined, not rebuilt

The Kotlin client is the product. It already has CameraX processing, frame
freshness, capture pacing, preview transforms, gestures, speech, spatial audio,
sonar mapping, API DTOs, and target-location seams.

**The single structural change is the inference seam.** `CameraFramePipeline`
currently encodes a frame and posts it to FastAPI. It instead calls an
`OnDeviceDetector` interface. Everything above and below that seam is refined in
place.

> **MUST NOT** start the Android client from zero. A rewrite discards working
> camera transforms, pacing, feedback wiring, and the existing unit tests, and
> buys nothing that refactoring one seam does not.

### 2.3 Ported from Python to Kotlin

Behaviour that currently runs on a CUDA laptop and must run in-process on the
phone. **This is the event-window work.**

| Component | Currently | Becomes |
|---|---|---|
| Object detection | YOLO11n, PyTorch, CUDA | YOLO11 on the Hexagon NPU (§6.1) |
| Detection canonicalization | `detector.py`, two filtered views | Kotlin, two views from one inference |
| Surface segmentation | SegFormer-B0 ADE20K, CUDA | ADE20K semantics on NPU **only if §6.2 passes** |
| Object tracking | Python, session-scoped | Kotlin, in-process |
| Spatial reasoning | Python | Kotlin |
| Risk engine | Python | Kotlin |
| Guidance state machine | Python | Kotlin |
| Target guidance | Python `target_guidance.py` | Kotlin |
| Landmark memory | Python, TTL-bounded | Kotlin, TTL-bounded |
| OCR | Tesseract 5, laptop CPU | **Bundled ML Kit OCR, phone CPU** (§6.3) |
| Walking-frame transport | HTTP multipart over LAN | **Deleted. No transport in the walking loop.** |

**MUST:** every ported stage is validated against golden JSON vectors exported
from the Python implementation (§22, R0 and R3). A port that changes behaviour
while changing platform is a port whose failures cannot be isolated.

### 2.4 Narrowed, not deleted

The FastAPI service is **not** removed. Its hackathon runtime responsibility is
narrowed to the one real participant requested for the Android Monitor: health,
structured telemetry ingestion, and a latest-person feed. The remaining roster
and aggregate hazard programme stay explicitly hardcoded in Monitor.

It receives structured telemetry, never the walking frame stream.

### 2.5 Deliberately out of scope

Not build requirements for this revision. Each was cut because it costs event
hours without strengthening the technical case.

- **Haptic guidance.** `HapticEngine` and the `haptic_pattern` contract field
  remain in the codebase — the client is unchanged — but haptic output is not on
  the acceptance path, and no gate in §22 or test in §23 depends on it.
- **Screen-off CameraX operation.** Walk Mode is specified for a screen-on phone.
- **Flight-mode and public-network-disconnection demonstrations.** The
  independence *invariant* stands (§3.3) and is verified by powering the
  coordinator off (§23), not by a radio-off stunt.
- **Rebuilding the dashboard.**
- **A schedule copied from the event agenda.** §22 is dependency-gated instead.
- **Eligibility, team-bucket, and end-user-validation planning.**

Directional output for this build is **spatial audio plus concise speech**.

### 2.6 The architectural inversion, stated plainly

```
BEFORE                                  AFTER
------                                  -----
phone: capture, encode, POST            phone: capture, infer, reason, speak
  |                                       |
  | JPEG over Wi-Fi, every frame          | (nothing — in-process)
  v                                       |
laptop: decode, detect, segment,          +--> bounded structured telemetry
        track, reason, score,                  (optional, fire-and-forget)
        guide                                    |
  |                                              v
  | JSON back over Wi-Fi                 laptop: coordinator + dashboard
  v                                              hazards, health, exports
phone: speak, draw                               optional snapshot locator
```

The round trip in the BEFORE column is the product's fatal flaw. Removing it from
the *walking loop* is the point of the build. Removing the laptop *entirely* was
never necessary, and doing so would cost a working dashboard for nothing.

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
- **MUST NOT** advertise a capability the deployed models cannot produce. If the
  segmentation gate (§6.2) fails, the system does not claim wall or stairs
  semantics. If `door` is unreachable from the deployed detector (Appendix B),
  the system does not claim door detection.
- **MUST NOT** present itself as a replacement for a white cane, a guide dog,
  mobility training, or human judgement. It is an assistive prototype.

### 3.2 What the system must always do

- **MUST** prefer `STOP` or `PAUSE_UNCLEAR` over a confident wrong answer. For
  this user, a confident wrong answer is the most dangerous possible output.
- **MUST** carry every state in more than one channel. Colour is never the only
  signal — every state also has a spoken word, an icon shape, and a distinct
  spatial-audio character. A blind user receives nothing from colour at all.
- **MUST** let safety guidance preempt everything else. If Walk guidance and a
  target-tracking cue or a scene answer contend for the speech channel, safety
  wins immediately and the other is dropped, not queued. Target spatial audio is
  muted whenever the risk action is not `CLEAR`.
- **MUST** degrade loudly. If segmentation is unavailable, the user is told the
  system is running reduced, not left to assume full capability.

### 3.3 Privacy and independence invariants

- **MUST NOT** store frames. Frames live in a small reused buffer set and are
  overwritten. Nothing is written to disk in the walking path.
- **MUST NOT** perform facial recognition, identity tracking, or route-history
  logging.
- A hazard evidence JPEG leaves the device **only** after an explicit per-report
  consent gesture, and only to the paired coordinator.
- **MUST NOT** let continuous safety depend on the laptop, a VLM, Office Kit, or
  a network round trip. Loss of the coordinator, of Wi-Fi, or of the dashboard
  cannot stop or alter phone guidance. This is structurally enforceable in a way
  it never was before: there is no client in the loop left to fail.
- Telemetry is **fire-and-forget over a bounded queue that drops on overflow**.
  Nothing in the guidance loop reads from it, waits on it, or checks whether it
  is connected.

### 3.4 Why this section is first

Every shortcut available under time pressure trades against one of these.
Writing them at the top makes the trade visible when someone at hour 22 suggests
"just say it's clear if we don't detect anything." That suggestion is a §3.1
violation and the answer is no.

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
> - Initialises with NPU backend → it is a candidate for OCR and the optional VLM.
> - Initialises, CPU only → do not use it. CPU inference anywhere near the walk
>   loop is a thermal and latency disaster. Fall back to the Qualcomm AI Hub /
>   QNN path.
> - Does not initialise → AI Hub / QNN path only, and the phone VLM drops to the
>   optional tier of §6.4 with the laptop locator as its fallback.

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
| Detection (YOLO11n, quantized) | **Always** | 120 – 180 MB |
| Segmentation (SegFormer-B0 ADE20K, quantized) | **Always, if §6.2 passes** | 150 – 250 MB |
| Tracking, spatial, risk, guidance, target memory | Always | < 20 MB, pure Kotlin |
| **Walk loop total** | | **~300 – 450 MB** |
| OCR (on demand) | Load / unload | 150 – 250 MB peak |
| Phone VLM (Qwen3-VL-2B INT4, optional, on demand) | Load / unload | 2.0 – 2.7 GB peak |
| Reasoning LLM (Qwen3-4B INT4) | **Not on device** | 3.0 – 3.5 GB peak |

The Walk loop is comfortable. Everything else is a spike.

### 4.5 The single most important memory rule

> **MUST: no two on-demand models are ever co-resident, with each other or with
> anything else large.** On 12 GB, `2.5 GB + 3.2 GB` is over the ceiling and the
> process dies.

The parent project already solved this and the pattern transfers exactly: the
Moondream2 integration loads the model only for an explicit snapshot request,
holds a single non-queueing worker, guards on free memory before loading, and
**unloads before the response is returned**. Target tracking only starts after
the unload, on CPU state.

Reimplement that pattern verbatim. It was designed for an 8 GB VRAM budget and it
is exactly what a 12 GB phone needs.

> **MUST NOT** assume that dropping a reference frees native memory. A native
> runtime may retain arenas, contexts, and graph memory after the Kotlin or
> Python object is gone. Call the runtime's explicit close/release API, then
> **measure reclaimed memory**. If a runtime cannot be proved to release, host the
> optional VLM in a separate killable process and reclaim by killing it.

---

## 5. Memory architecture

### 5.1 Three residency classes

**Class A — resident for the session.**
Detection, and segmentation if it ships. Loaded when Walk Mode starts, unloaded
when it ends. Never unloaded mid-walk, because a reload stall is a gap in safety
coverage.

**Class B — load on demand, single occupancy, unload before return.**
OCR and the optional phone VLM. At most one Class B model is in memory at any
instant. The sequence is strictly:

```
check free memory  →  refuse if below floor
       ↓
load model
       ↓
run exactly one inference
       ↓
unload model, release native buffers, verify reclaim
       ↓
return the result to the caller
```

**MUST:** the result is returned *after* the unload, not before. Returning first
and unloading asynchronously creates a window where a second request can arrive
and double the footprint.

**MUST:** Class B invocation is explicit, one-shot, and cancellable with a
deterministic timeout. There is no continuous Class B execution under any
circumstance.

**Class C — never on device.**
The reasoning LLM. On a 12 GB unit the honest answer is no.

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
- **MUST** hold at most one in-flight inference and one replaceable pending frame.
  Latest frame wins; anything older is dropped, not queued (§8.3).

---

## 6. Model selection and gates

Every stage has a gate and a fallback. **MUST:** never skip straight to the
bottom rung to save time — each rung down costs real capability that the demo
depends on. And **MUST NOT** change a model without a measured reason: model
drift plus platform drift at the same time makes failures impossible to isolate.

### 6.1 Detection — YOLO11 first

The current backend, its labels, its canonicalization, its tests, and its target
memory are all written against **YOLO11n** semantics. Replacing it with YOLOv8
before measuring anything discards that compatibility for nothing.

| Rung | Model | Notes |
|---|---|---|
| 1 | **YOLO11 detection** | First choice. Matches the current backend's post-processing and label semantics exactly. |
| 2 | **YOLOv8 detection** (AI Hub) | Only if YOLO11's phone export is materially less reliable or slower on the actual loaner. Costs a post-processing review. |
| 3 | **YOLOX** (AI Hub) | Only if both fail *and* an official sample proves it end to end on the device. |
| 4 | Any of the above on GPU | Slower, hotter, still acceptable. |
| 5 | CPU | **Demo-only fallback.** Announce it as degraded. |

Qualcomm AI Hub publishes a
[YOLO11 detection NPU profile](https://aihub.qualcomm.com/jobs/jpev1d9v5) on a
Snapdragon 8 Elite Gen 5 reference device. That is **feasibility evidence, not an
iQOO 15 application benchmark.** Only measurements on the loaner justify a final
FPS, latency, memory, or NPU claim.

**Output required:** boxes in normalized coordinates, native COCO class label,
confidence — and then **two filtered views from that one inference** (§9.3).

### 6.2 Segmentation — preserve the semantics or omit the capability

The current backend uses **SegFormer-B0 ADE20K** for a specific reason: ADE20K
exposes the indoor semantics DRISHTI reasons about — floor, wall, door, stairs,
furniture. Substituting an arbitrary segmentation model silently deletes those
capabilities while appearing to work.

> **MUST NOT** substitute a Cityscapes checkpoint. Road-oriented classes do not
> provide indoor floor, wall, door, and stairs semantics. It is not an equivalent
> model; it is a different capability wearing the same word.

**The gate, in order:**

1. Compile the ADE20K checkpoint for the selected phone runtime.
2. Prove output-label ordering and the preprocessing pipeline against the Python
   implementation on identical inputs.
3. Verify the camera-to-mask coordinate transform (§10).
4. Measure sustained NPU execution, not a cold single shot.
5. Replay the existing indoor semantic fixtures in
   `backend/tests/fixtures/indoor/`.

> **Implementation result — 12 September 2026.** The deployed ADE20K graph is
> portable w8a16 QDQ with float public IO. A provider profile isolated two
> constant classifier dequantizers as the only CPU nodes; folding exactly those
> constants made the session create with CPU fallback disabled. The 12 GB iQOO
> probe measured 11.33 ms guarded HTP versus 150.25 ms CPU. A public indoor
> fixture retained 99.91% safety-surface agreement, identical corridor threshold
> states, zero hazard-flag differences, and 0.00391 maximum corridor-ratio drift.
> This passes runtime placement and integration parity; fixture replay and field
> validation remain separate safety gates.

Qualcomm's
[SegFormer-B0 ADE20K page](https://aihub.qualcomm.com/iot/models/segformer_base)
documents the correct 150-class checkpoint, but does not by itself prove support
on this mobile target.

**If the gate fails:** ship detector-based corridor reasoning with explicit
uncertainty, set `degraded_modules = ["segmentation"]`, and tell the user. Most
frames will then resolve to `PAUSE_UNCLEAR` — which is the correct behaviour, and
is exactly what `PAUSE_UNCLEAR` exists for. **MUST NOT** advertise wall or stairs
semantics the deployed model cannot produce.

**Label mapping required.** The existing logic needs these classes:

| Existing `SurfaceKind` | ADE20K sources |
|---|---|
| `WALKABLE` | floor, sidewalk, path, rug, grass, earth, field, sand, land, dirt track |
| `ROAD` | road (kept separate — road is walkable-but-dangerous) |
| `NON_WALKABLE` | wall, building, furniture, appliances, vehicles, people, plants, stairs, water, fence |
| `UNKNOWN` | everything else, and low-confidence pixels |

The mapping has to cover the vocabulary, not a sample of it. The first version
named 36 of ADE20K's 150 classes, so 114 fell through to `UNKNOWN` — including
`swivel chair`, `coffee table`, `counter`, `bench`, `refrigerator` and `river`.
`UNKNOWN` is charged at `surface_cost_unknown_weight` 0.10 against a 0.40 block
threshold, so a corridor filled wall-to-wall by an office chair cost 0.10 and the
path read **clear**. `UNKNOWN` is the right default for a label nobody has
audited; it is the wrong answer for a class the model names confidently and a
walker would collide with. Overhead classes (`ceiling`, `chandelier`, `lamp`,
`awning`, `canopy`, `sky`) stay out of `NON_WALKABLE` deliberately: they are
above head height, and blocking on them would stop the user under every lit
corridor.

### 6.3 OCR

| Rung | Model | Notes |
|---|---|---|
| 1 | **PaddleOCR** via NexaSDK | Depends on gate 4.2.1. |
| 2 | AI Hub OCR / text-detection model | Fallback if Nexa is unavailable. |
| **3 — selected** | **ML Kit text recognition** (bundled, on-device, Google) | Boring, reliable, genuinely on-device, no NPU claim. |
| 4 | Cut Explore Mode | It is a Should, not a Must. |

> Rung 3 is worth naming clearly: ML Kit runs on-device and will work. It does
> not run on the NPU, so it does not strengthen the on-device-AI story, but it
> keeps the feature alive. Use it rather than losing Explore Mode entirely, and
> be honest about what it is if a judge asks.

This selected rung now ships. The Latin recognizer is bundled in the APK, is
created for one explicit still and closed before returning, and never sends the
JPEG or result to a service. The 12 GB iQOO device test recognizes `BUS 42A` and
extracts `42A` using the production reader.

### 6.4 The locator and Scene VLM — optional proof, never a prerequisite

**A VLM is never in the continuous loop, and no headline claim depends on one.**

Qwen3-VL-2B is not a safe headline dependency until the exact package, operators,
quantization, memory use, and accelerator execution are proved on the loaner.
Qualcomm's
[Qwen3-VL-2B-Instruct page](https://aihub.qualcomm.com/models/qwen3_vl_2b_instruct)
does not establish support for this exact retail phone configuration.

**Required gate for a phone VLM:**

- explicit one-shot invocation only;
- bounded input resolution;
- no concurrent camera-frame backlog;
- measured load, first-answer, repeated-answer, peak-memory, and thermal figures;
- verified accelerator execution rather than a silent CPU fallback;
- deterministic cancellation and timeout behaviour; and
- the safety loop stays responsive, or is explicitly and audibly paused for the
  duration.

| Rung | Path | Notes |
|---|---|---|
| 1 | **Qwen3-VL-2B-Instruct on the phone** | Only after the gate above passes and every earlier gate is stable. |
| 2 | **Laptop Moondream2 `/api/v1/vlm/locate`** | The existing, working snapshot locator. Explicit single snapshot in, normalized box out. Advertised as *laptop-assisted target localization* — never as on-device AI. |
| 3 | Detection-derived scene summary | Compose a sentence from the detection list. No VLM at all. |
| 4 | Cut Scene Mode | Nice-to-have tier. |

> **How this resolved.** Neither rung 1 nor rung 2 is what shipped, and the two
> halves of this ladder separated:
>
> - **Scene description** runs a VLM on the phone, but not Qwen3-VL-2B.
>   Measurement inverted the tier order — the 2B and 4B spend 13 – 16 s in
>   initialization *per call*, which no quantization fixes — so
>   **LFM2.5-VL-450M** ships, answering in 1.3 – 1.8 s
>   ([`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md) §4.3, §8).
> - **Target locating** uses **no VLM at all**, and rung 2 was never ported.
>   Find resolves from landmark memory and the live detector view (§14.1), so
>   the laptop is not in the path and `/api/v1/vlm/locate` has been deleted
>   from the client. The detection-derived summary of rung 3 was built, tested
>   and then deliberately removed: with a real VLM answering, a
>   template sentence dressed as scene understanding is the kind of claim §3.1
>   forbids.

> **Do not plan around Qwen3-VL-4B.** The parent project's research already
> caught its AI Hub page simultaneously listing 8 Elite Gen 5 as supported and
> stating *"This model is currently not supported on any Mobile chipset."* That
> contradiction is exactly the trap this ladder exists to avoid.

Rung 3 deserves respect. "A person ahead on the left, a chair to the right, a
doorway centre" composed from detections is genuinely useful, costs no memory,
and cannot fail.

> **MUST:** locator confidence is **nullable**. Moondream2 returns a box without
> a calibrated probability. Preserve `confidence: null` and test the box itself.
> Fabricating `0.85` for presentation is a §3.1 violation dressed as a field.

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
Attractive for OCR and the optional VLM in one dependency. **Gated on 4.2.1 (the
16 GB floor).**

> **SHOULD:** vision on Path A, on-demand models on Path B if the gate passes.
> **MUST NOT** make the walk loop depend on Path B.

### 7.3 Proving the NPU is actually being used

This matters for scoring — the on-device-AI credit is real, and a judge may
reasonably ask whether it is the NPU or a CPU fallback. It matters more for
engineering, because a silent CPU fallback will pass functional testing and then
melt the phone twenty hours later.

> **MUST:** build an on-screen diagnostics panel early, showing per stage: the
> backend actually in use (NPU / GPU / CPU), inference milliseconds, rolling FPS,
> resident memory, and the current thermal status. It is also the best demo prop
> available — toggling backends live and watching the millisecond count collapse
> argues technical depth better than any slide.

> **MUST NOT** infer NPU execution from speed alone. Fast execution does not
> prove which backend ran. Capture runtime or profiler evidence from the
> supported Qualcomm toolchain and record it alongside the measurement.

> **MEASURE 7.3.1** — Does inference time drop by roughly an order of magnitude
> when the NPU backend is selected versus CPU, *and* does the runtime report the
> accelerator? Both, or the claim is not made.

> **Measured:** yes for both resident models on the 12 GB iQOO 15. YOLO11n is
> 3.30 ms guarded HTP versus 27.35 ms CPU; SegFormer-B0 is 11.33 ms guarded HTP
> versus 150.25 ms CPU. In both cases
> `session.disable_cpu_ep_fallback=1` turns any CPU-assigned node into a session
> creation failure, so the placement claim does not depend on timing alone.

> **Built.** The panel is in the Walk screen, reached by a **two-finger swipe
> down**, and shows the backend actually in use, detection and segmentation
> milliseconds, total frame time, rolling FPS, thermal status and free RAM.
> Its **"Compare on CPU"** control runs the same detector model on the CPU and
> back without restarting the session, so the order-of-magnitude claim above
> can be demonstrated live rather than quoted. The backend it names is read
> from the detector that actually executed the frame, not from a setting.

### 7.4 Build traceability

Every installed APK **MUST** be traceable to a Git commit and build variant.
Record together, for each measurement that will be quoted:

| Field | Why |
|---|---|
| Git commit hash | Which code produced this |
| APK checksum | Which binary is actually on the phone |
| Model asset checksum | Which weights, which quantization |
| Device serial | Which loaner |
| Office Kit mirroring active? | Mirroring adds display, encode, network, and thermal load (§20.2) |
| Measured result | The number itself |

Without this, an apparently successful demo can be attributed to code or model
assets that are not the ones running.

---

## 8. Pipeline architecture

### 8.1 The walking loop

**One accelerator, one scheduler.** The diagram below is a data-flow diagram, not
a concurrency diagram: detection and segmentation do **not** submit concurrently.
Concurrent submissions to a single NPU serialize unpredictably or exhaust shared
memory, and the failure is intermittent.

```
CameraX ImageAnalysis  (backpressure: KEEP_ONLY_LATEST)
        │
        ▼
  Frame ring buffer ── latest wins, stale frames dropped
        │
        ▼
  Orientation correction  ──►  FrameGeometry
        │
        ▼
  ┌─────────────────────────────────────────┐
  │  ACCELERATOR SCHEDULER — serialized     │
  │  priority 1: detection    (every frame) │
  │  priority 2: segmentation (every Nth)   │
  │  priority 3: on-demand Class B (never   │
  │              concurrent with a walk     │
  │              frame in flight)           │
  └─────────────────────────────────────────┘
        │                        │
        ▼                        ▼
  one detector invocation   SurfaceRegion[]
        │
        ├──► full COCO view ──► landmark memory (TTL) ──► §14
        │
        └──► aliased risk view (19 classes)
                 │
                 ▼
         Tracker  (CPU, session-scoped)
                 │  motion, approach state
                 ▼
         Spatial reasoning  (CPU)  ◄── SurfaceRegion[]
                 │  proximity bands, corridor costs
                 ▼
         Risk engine  (CPU)
                 │  weighted per-detection score, frame decision
                 ▼
         Guidance state machine  (CPU)
                 │  persistence, hysteresis, preemption
                 ▼
      ┌──────────┼───────────────┬──────────────┐
      ▼          ▼               ▼              ▼
   Speech   Spatial audio    Overlay      Telemetry queue
                             (preview      (bounded, drops
                              transform)    on overflow → §19)
```

### 8.2 Hard runtime rules

- **MUST** hold one active inference and at most one replaceable pending frame.
- **MUST** reject stale outputs. Latest frame wins.
- **MUST** keep the detector and safety path long-lived and prioritized.
- **MUST** keep VLM and OCR work explicit and one-shot, never continuous.
- **MUST** suppress target speech and target spatial audio when the risk action
  is actionable (anything other than `CLEAR`).
- **MUST** keep phone safety processing running when laptop telemetry is lost.
- **MUST** send the dashboard structured telemetry, never the camera stream.
- **MUST** release the optional locator's model resources after each request.

### 8.3 What changed versus the current system

The stage structure is identical, and that is the point — the pipeline is proven,
and mainly its execution location moves. Four things are genuinely different:

1. **The JPEG encode is gone.** No compression, no multipart, no decode. The
   camera buffer goes to the model. This alone removes 20–40 ms per frame.
2. **The freshness gate changes meaning.** It exists today because a response
   could arrive describing a frame from 800 ms ago. With no transport, staleness
   comes only from processing overrun. Keep the gate — it now guards against
   thermal slowdown instead of network lag.
3. **Segmentation cadence becomes a tunable.** On a laptop GPU both models ran
   every frame. On the phone, decoupling them is the main thermal lever (§17.3).
4. **Accelerator access is explicitly scheduled.** On CUDA this was free. On one
   NPU it is not.

### 8.4 Frame lifecycle

| Step | Owner | Budget |
|---|---|---|
| Acquire + convert YUV | Camera thread | 10 – 20 ms |
| Preprocess (rotate, letterbox, normalize) | Inference thread | 5 – 15 ms |
| Detection inference | NPU | **MEASURE**, target < 20 ms |
| Segmentation inference (every Nth frame) | NPU | **MEASURE**, target < 40 ms |
| Track + spatial + risk + guidance | Compute thread | 5 – 15 ms |
| Emit outputs | Main / audio | < 5 ms |
| **Total** | | **target ≤ 120 ms → ≥ 8 fps** |

> **MEASURE 8.4.1** — sustained **camera-to-guidance** frame time over a
> ten-minute walk, not a cold single-shot benchmark and not a published model
> profile. A raw model profile excludes preprocessing, tensor copies,
> postprocessing, camera conversion, audio, and thermal throttling; the cold
> number will look excellent and is not the number that matters.
>
> **Decision rule:** sustained ≥ 8 fps → ship as designed. 4–8 fps → drop
> segmentation to every 3rd frame. < 4 fps → drop segmentation entirely per §6.2
> and announce the degradation.

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

### 9.3 Detection — one inference, two views

This is the design to port, and it is not "run the 80-class model." It is:

```
one phone detector invocation
    ├─► full native COCO detections ──► TTL landmark memory      (§14)
    └─► aliased, allow-listed detections ──► tracker → spatial → risk
```

**The full view.** Native COCO names, no aliasing, above the confidence floor.
Feeds landmark memory only, so a user asking for a `bottle`, `clock`, `book`,
`laptop`, `cell phone`, or `cup` is answered from something the detector already
saw, at no extra inference cost. Governed by `landmark_full_coco`,
`landmark_min_confidence` (0.45), `landmark_min_sightings` (2),
`landmark_memory_ttl_seconds` (45), `landmark_memory_max` (40), and
`landmark_allow_person = false`.

**The risk view.** The 19 canonical labels of Appendix B, with aliases applied:
`backpack` and `handbag` → `bag`; `dining table` and `table` → `desk`. Feeds
tracking, spatial analysis, risk scoring, overlays, and public walk detections.

> **MUST NOT** feed all 80 classes into the safety engine. A COCO label says an
> object is present; it does not establish that the object obstructs the walking
> corridor. Widening the audited set widens the surface over which the system can
> confidently say something wrong.

Detector parameters, ported as-is:

- Confidence floor: 0.35 (`detector_confidence_threshold`). **MEASURE** on device.
- Model input size: 640 (`detector_image_size`).
- NMS IoU: 0.45.
- Boxes **MUST** be emitted in `ORIENTED_CAPTURE_NORMALIZED` space — normalized
  against the full oriented capture, **not** the letterboxed tensor. Un-letterbox
  before normalizing. This is the single most common source of overlay
  misalignment and it is silent: boxes are drawn slightly wrong in a way that
  looks like camera jitter.

### 9.4 Segmentation

Runs only if §6.2 passed. **Input:** oriented frame at the model input size
(512×512 in the current configuration). **Output:** `SurfaceRegion[]` with
`NormalizedPolygon` geometry.

- Apply the label map from §6.2.
- Contour extraction: threshold the class mask, find contours, simplify with
  Douglas–Peucker, emit as normalized polygons.
- **MUST** cap polygon vertex count at 40. An unsimplified mask contour can carry
  thousands of points, and that will stall the overlay renderer.
- Low-confidence pixels map to `UNKNOWN`, not to `WALKABLE`. **Defaulting
  uncertainty to walkable is a §3 violation** — it manufactures confidence the
  model did not express.

### 9.5 Tracking

Port of the existing session-scoped tracker. Deterministic, cheap, CPU-only.

- Associate detections across frames by IoU + class identity.
  `track_iou_threshold` 0.20, `track_centre_distance_threshold` 0.12,
  `track_max_age_frames` 3.
- Maintain per track: id, class, box history, first-seen, last-seen.
- Derive `MotionVector` from the box-centre trajectory.
- Derive `ApproachState` from box-area growth, `approach_change_threshold` 0.05:
  - area growing beyond the threshold → `APPROACHING`
  - area shrinking → `RECEDING`
  - within the deadband → `STATIONARY`
  - insufficient history → `UNKNOWN`
- **MUST** run on CPU. There is no reason to involve an accelerator.
- **MUST NOT** replace it with a "nearest object" heuristic during the port.

### 9.6 Spatial reasoning

- **Proximity band** from box geometry — `proximity_area_weight` 0.55,
  `proximity_area_scale` 0.50, thresholds at 0.35 / 0.55 / 0.78 for
  `FAR` / `MEDIUM` / `NEAR`. Purely relative. **MUST NOT** be converted into or
  presented as a metric distance.
- **Direction** — `LEFT` / `CENTRE` / `RIGHT` from the box centre against the
  corridor geometry; `UNKNOWN` when it straddles a boundary ambiguously.
- **Corridor geometry** — a trapezoid, not thirds: `corridor_horizon_y` 0.38,
  `corridor_top_half_width` 0.08, `corridor_bottom_half_width` 0.42. Port the
  geometry exactly; it encodes the camera's perspective.
- **Corridor costs** — for each of left/centre/right, accumulate cost from
  blocking detections weighted by proximity band and path overlap, plus
  non-walkable surface fraction from segmentation
  (`surface_cost_unknown_weight` 0.10, `surface_cost_road_weight` 0.0).

Emitted as `CorridorCosts { left_cost, centre_cost, right_cost }`.

---

## 10. The coordinate transform contract

> This section describes something already solved and working. It is here so the
> migration does not break it. **Read it before touching the overlay.**

### 10.1 The three coordinate spaces

1. **Model tensor space** — letterboxed, padded, model-specific. Never leaves the
   inference stage.
2. **`ORIENTED_CAPTURE_NORMALIZED`** — the full orientation-corrected capture,
   normalized to `[0,1]`. **This is the contract.** Everything downstream speaks
   it, on the phone and on the wire.
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
- **MUST** apply the same rules to segmentation masks. A mask transform error is
  harder to see than a box error and corrupts corridor costs directly.

### 10.3 The test that catches it

Draw a box at exactly `(0.0, 0.0, 1.0, 1.0)` in normalized space. It **MUST**
trace the full oriented capture. Under a `COVER` preview it will extend beyond
the visible viewport on the cropped axis — that is correct and expected. If it
sits neatly inside the screen edges, the crop has leaked backwards and every
detection is wrong by the same factor.

Run this test in portrait *and* landscape before trusting any overlay.
`PreviewTransformTest.kt` already exists; keep it green.

---

## 11. Spatial reasoning port

### 11.1 Corridor occupancy

The frame carries a perspective trapezoid split into left, centre, and right
corridors (§9.6). For each, cost accumulates from:

| Source | Contribution |
|---|---|
| Detection in corridor, `IMMEDIATE` | very high |
| Detection in corridor, `NEAR` | high |
| Detection in corridor, `MEDIUM` | moderate |
| Detection in corridor, `FAR` | low |
| `APPROACHING` state | multiplier on the above |
| Path overlap with the corridor polygon | proportional |
| Non-walkable surface fraction | proportional |
| `UNKNOWN` surface fraction | **raises uncertainty, not cost** |

That last row is the subtle one and it is a §3 requirement. Unknown surface does
not make a corridor *blocked*; it makes the frame *unclear*. A high unknown
fraction resolves to `PAUSE_UNCLEAR`, not to a confident pick of whichever
corridor scored least.

### 11.2 Preferred corridor

```
if all three corridors exceed the block threshold        → STOP
if the centre corridor's surface is uncertain            → PAUSE_UNCLEAR
if centre is clear                                       → CENTRE (prefer straight)
if exactly one side is clearly better by decision_margin → that side
otherwise                                                → PAUSE_UNCLEAR
```

Thresholds, ported as-is: `risk_centre_block_threshold` 0.40,
`risk_side_block_threshold` 0.40, `decision_margin` 0.15,
`direction_min_free_extent` 0.35, `corridor_clear_margin` 0.10.

A side is only chosen when it is walkable, not marked uncertain, has enough free
floor extent, and its wall ratio is below `wall_side_ratio_threshold` (0.20).
Being merely *cheaper* than the other side is not sufficient.

**MUST:** prefer `CENTRE` when it is viable. Steering a blind user sideways
without cause is disorienting and erodes trust in the system.

### 11.3 Indoor structure detection

Available **only if segmentation ships** (§6.2). Without it these outputs do not
exist and **MUST NOT** be claimed.

- **Frontal wall / dead end** — high non-walkable fraction across the frame width
  at the lower-middle band with collapsed floor extent
  (`wall_centre_ratio_threshold` 0.35, `freespace_dead_end_max` 0.12) →
  `WALL_OR_DEAD_END_AHEAD`.
- **Stairs / level change** — the ADE20K stairs class in the centre corridor
  above `stairs_centre_ratio_threshold` (0.08) →
  `STAIRS_OR_LEVEL_CHANGE_AHEAD`.
- **Side wall with open forward path** — high non-walkable on one side but a
  viable centre → do **not** stop; guide centre.

> **MUST:** both stop conditions stabilize before they are announced. A
> single-frame segmentation flicker announcing "stairs ahead" to a walking blind
> user is exactly the confident wrong answer §3.2 forbids. Stabilization is the
> state machine's job (§13.3), not an ad-hoc check.

---

## 12. Risk engine port

### 12.1 Per-detection risk is a weighted score, not a maximum

**MUST** port the configured weighted combination exactly. It sums to 1.0 and the
sum is validated at load:

| Component | Weight |
|---|---|
| Path overlap with the corridor polygon | **0.30** |
| Relative proximity | **0.25** |
| Approach rate | **0.20** |
| Class severity (Appendix B) | **0.15** |
| Detector confidence | **0.10** |

> **MUST NOT** replace this with `max(confidence, proximity, …)`, a nearest-object
> rule, or any other "equivalent-looking" simplification during the port.
> Threshold and weight changes are tuning changes: they must be measured,
> versioned, and compared against the golden vectors — never silently rewritten
> while the platform is also changing.

The score maps to `RiskLevel` through hysteresis bands: `risk_watch_enter` 0.25,
`risk_warn_enter` 0.65, `risk_warn_exit` 0.50, `risk_high_enter` 0.80.

### 12.2 Frame-level decision is a rule cascade, not an aggregate

The frame decision is produced by an ordered rule cascade, and the order is the
behaviour. Port it in this sequence:

1. **Critical approaching vehicle in the corridor** — a `bicycle`, `motorcycle`,
   `car`, `bus`, `truck` or `train` above `risk_critical_path_overlap` 0.60,
   `risk_critical_proximity` 0.70, and `risk_critical_approach` 0.15 →
   `STOP` / `CRITICAL` / `APPROACHING_VEHICLE_CENTRE`, carrying the offending
   track ids.
2. **Wall or dead end** → `STOP` / `HIGH` / `WALL_OR_DEAD_END_AHEAD`.
3. **Stairs or level change** → `STOP` / `HIGH` / `STAIRS_OR_LEVEL_CHANGE_AHEAD`.
4. **All corridors blocked** → `STOP`, `CRITICAL` if an `IMMEDIATE` detection sits
   in the centre, otherwise `HIGH` / `ALL_CORRIDORS_BLOCKED`.
5. **Centre blocked, one side clearly better and genuinely walkable** →
   `MOVE_LEFT` or `MOVE_RIGHT` / `HIGH` / `CENTRE_BLOCKED_CLEARER_SIDE`.
6. **Centre blocked, no defensible side** → `PAUSE_UNCLEAR` / `WARN` /
   `CENTRE_BLOCKED_DIRECTION_UNCLEAR`.
7. **Centre surface uncertain** → `PAUSE_UNCLEAR` / `WARN` /
   `CENTRE_SURFACE_UNCERTAIN`.
8. **Highest-scoring detection at `WARN` or `HIGH`** → `CAUTION` / `WARN` /
   `OBSTACLE_NEARBY`.
9. Otherwise → `CLEAR` / `PATH_CLEAR`.

### 12.2.1 What "blocked" measures

A corridor counts as blocked when any of three things is true, and each exists
because the other two miss a real case.

**Obstruction by a detection.** `max(intersection / bbox_area,
intersection / corridor_area)`. Containment alone — the original
`intersection / bbox_area` — inverts the signal for exactly the obstacles that
matter most. The corridor covers ~31% of the frame, so a box spanning the whole
frame scores 0.31 while a bag the size of a fist inside the corridor scores 1.00:
the nearer and larger the hazard, the *lower* its measured overlap. Measured on
the reported failures, a chair filling 91% of the frame at arm's length produced
a centre cost of **0.068** against a 0.40 block threshold. Occlusion answers the
complementary question — how much of the path this object covers — and saturates
precisely where containment collapses. Taking the larger of the two can only
raise a value, never lower one, so nothing that used to be reported becomes
invisible.

**Non-walkable surface.** `non_walkable_ratio` plus the weighted road and unknown
ratios, as before, now over a taxonomy that covers the whole ADE20K vocabulary
(§6.2).

**No floor running ahead.** `floor_extent` at or below `freespace_blocked_max`
0.20. Free space is the one blocking signal that does not depend on *naming* the
obstacle, and it used to be read by a single rule — `wall_dead_end` — gated on
`wall_ratio >= 0.35`. A chair, a desk, a parked car or a crowd could reduce the
floor ahead to nothing and the cascade had no branch to take, because none of
them is a wall. This feeds the existing left/centre/right blocked flags rather
than adding a tenth rule: no branch moves, and the cascade already knows what to
do with a blocked corridor. It is gated on segmentation actually having run —
without it the extents are zeros, and reading those as "no floor ahead" would
stop the user on an empty pavement.

The green "safe corridor" overlay is gated on the same free-space measure. The
corridor is a perspective wedge whose pixel budget is dominated by the metre of
ground at the user's feet, so a walkable *ratio* stays high with an obstacle
filling everything above ankle height. Green has to mean floor continuing ahead,
not floor somewhere in the wedge.

### 12.2.2 When detection and segmentation disagree

A SegFormer-B0 argmax calls a wooden desktop viewed along its length `floor`.
Measured on a captured Walk Mode frame the centre corridor came back 82%
WALKABLE with a desk filling it and a floor extent of 1.00. YOLO saw the same
desk as `dining table`. The two models disagree about the same pixels, and the
detector is the one holding positive evidence.

So pixels under a risk-view detection box no longer count as walkable floor.
Nothing in the audited label set is a surface a person can walk on. The downgrade
is to `UNKNOWN`, not `NON_WALKABLE`: a box says something is THERE, not what the
surface behind it is, and boxes are rectangles — a standing person's box contains
the floor around their legs. The effect is that the floor ahead stops being
*claimed* from the obstacle onward, which is what the free-space extent is
supposed to measure anyway.

Surface evidence is therefore rebuilt on every frame while the segmentation map
itself is still cached across the stride: surfaces change slowly, the boxes in
front of them do not.

### 12.2.3 Two detector gates, two costs of error

`detector_confidence_threshold` 0.45 gates the Find/landmark view, where a false
positive sends someone walking towards a chair that is not there.
`risk_confidence_threshold` 0.35 gates the safety view, where a miss is a
collision and a false positive is a needless pause — and where corridor cost
already multiplies by confidence, so a marginal box contributes marginally.

The split is not a preference. On real frames YOLO11n scored the desk the user
was standing at as `dining table` 0.36–0.45, straddling the old single gate,
which is precisely why the desk was sometimes seen and sometimes not. 0.35 is the
decoder's own floor, so nothing below it exists to admit.

### 12.2.4 Condemning an escape route costs more than flagging the path

`risk_side_block_threshold` 0.55 sits above `risk_centre_block_threshold` 0.40.
Calling the centre blocked costs a pause; calling a side blocked removes an
escape route, and once all three are gone the only verdict left is `STOP`.
Corridor cost compounds as a noisy-OR, so moderate contributions add up fast: on
a captured frame a chair at 0.227, a person at 0.199 and a surface cost of 0.178
combined to 0.491 on a left corridor that segmentation still read as 68% walkable
with 64% clear floor ahead. At a shared gate that frame said "path blocked on
every side" with a clear route visible in it; it now says `MOVE_LEFT`.

The wider gate is bought from segmentation being able to show a side is open, so
when surfaces are absent it narrows back to the centre's — the same reason the
free-space rule is disabled in that case.

### 12.2.5 The furniture is invisible; what stands on it is not

Sixteen CONSECUTIVE Walk Mode frames of a phone on a desk: YOLO11n reported
`dining table` on **one** of them, and `laptop` on **all sixteen** at 0.76-0.92.
A 6% hit rate is not a flicker that tracking can bridge — the desk is simply not
detectable in that pose, and SegFormer calls its wooden top `floor`.

So `SURFACE_WITNESS_LABELS` — `laptop`, `keyboard`, `microwave`, `oven`,
`toaster` — carry a second meaning. None of them is ever found sitting on a
walking floor, so the base of one is a measurement: the plane it stands on is a
worktop, and everything nearer than it in the same columns is that same worktop.
A witness therefore withdraws the walkable claim from its base down to the bottom
of the frame, across its own x-range only. How far the desk extends sideways is
not measured, so it is not claimed.

Two gates keep it honest. The witness must be MEDIUM proximity or nearer — a
laptop on a desk across the room stands on a different surface with floor in
between, and shadowing below it would condemn that floor. And `tv` is excluded
from the family: it is the one member routinely mounted on a wall, with no
surface under it at all.

Measured on captured sequences, the witness costs nothing on an open office
aisle (identical verdicts) and is what turns the desk scene from mostly CLEAR
into mostly blocked.

**What this does not solve.** When the centre of the path is bare desktop with
nothing standing on it, there is no evidence left: the pixels are a wooden plane
receding to a vanishing point, which is what a wooden floor also is. That case
needs depth, or a segmentation model that can tell a worktop from a floor at a
grazing angle. SegFormer-B0 cannot.

### 12.2.6 Free depth is measured across a corridor, not up a sliver of it

Three live screenshots showed one office chair ahead, plainly clear carpet beside
it, and a full-screen `STOP` reading "blocked on every side". The chair was found
and measured correctly. What was wrong was the question asked of the floor.

Free depth used to be measured COLUMN-wise: walk up each corridor column until
the floor stops, take the median over the columns tall enough to be worth
measuring. A corridor third is a slanted trapezoid, so hardly any of its columns
run its full depth, and the ones that do all lie against its INNER edge. On the
shipped geometry over a 128-px map the LEFT third covers columns 24..60 and only
**45..53** were measured — a nine-pixel strip pressed against the centre. "Is
there room to my left?" was answered by looking at the strip immediately beside
whatever was blocking the centre, so one wide object in the middle of the path
zeroed all three corridors at once. Over ten consecutive captured office frames
the column measure read 0.000 for at least one side on seven of them, including
one whose corridor costs were 0.05 / 0.10 / 0.19 — nothing in the way at all.

It is now measured ROW-wise: from the bottom of the frame upward, across the
region's whole width, the depth carries on while a row is at least
`ROW_WALKABLE_MIN` = 0.55 floor. A desk filling a corridor still takes a row from
floor to nothing; an object hugging one edge of a third no longer condemns it.
Swept at 0.45 / 0.55 / 0.65 over three captured sets: 0.45 and 0.55 give
identical decisions, and 0.65 starts clipping the centre extent on frames with
open floor ahead.

`direction_min_free_extent` was re-anchored from 0.35 to 0.30 at the same time,
because the quantity underneath it changed. It is now one and a half times
`freespace_blocked_max`, leaving a real band between "not blocked" and "good
enough to walk into". Across 44 captured frames the move changes one decision.

**And cost is no longer the only thing that can choose a side.** When neither
side is cheaper than the other by `decision_margin`, the old answer was
`PAUSE_UNCLEAR` — "something is in the way, work it out yourself", which is the
least useful thing to say to a blind person mid-stride. Corridor cost is a
noisy-OR over NAMED obstacles and says nothing about what was never detected, so
free depth now breaks that tie when it can separate the sides by
`direction_free_extent_margin` = 0.20. The winner still has to pass the
walkable / free-extent / wall-ratio test before anyone is steered into it, and
genuinely equal sides still refuse to invent a direction.

Measured over the three captured sets: no frame changed between CLEAR and
non-CLEAR — the verdict "is something in the way" is untouched — and of the
blocked frames that previously carried no direction, nine of eleven now do. The
desk sequence went from seven `ALL_CORRIDORS_BLOCKED` to none.

### 12.2.7 A name is a guess; the object is what persists

"Chair shows suitcase?" — two live screenshots of the same office chairs. At
walking distance YOLO11n called them `chair` at 0.64 and 0.80. With a chair back
filling the lens, a large dark rounded rectangle, it called it `suitcase` at 0.37
and 0.41. Both clear the 0.35 safety gate, which is the right gate for "something
is there" and far too low for "and it is a suitcase".

The tracker made this worse rather than absorbing it. Association was gated on
the NAME (`tracking.json`'s `new_id_when_label_differs`), so the moment the name
changed, the entire history was discarded and a fresh track began — with the
wrong name and no way back — at exactly the moment the obstacle mattered most.

Two changes, both divergences from the Python, both parity-gated so the vectors
still hold:

- **Association is geometric.** A box that overlaps an existing track by
  `cross_label_iou_threshold` = 0.60 joins it even under a different name.
  Same-name matches are taken first, so an overlapping person and chair still
  end up as two tracks; a differently-named box has to be essentially the same
  box before it is treated as the same thing.
- **The name is a vote**, summed over the track's life and weighted by
  confidence. One confident early look does not outrank a dozen consistent later
  ones, so a genuine misread on the approach is not permanent.

`TrackedDetection.label_confidence` carries the best evidence there has ever been
for the name being reported, which is a different quantity from the box's
confidence on this frame: the first decides whether the name is worth SAYING,
the second is what a corridor cost is built from. `blocking_label` will not speak
a name supported below 0.50 — between the 0.35 gate that decides an obstacle
exists and the 0.64-0.80 the detector produces when it actually recognises
something. Below that the verdict is unnamed, not wrong.

### 12.2.7.1 The detector does not get the name wrong; it goes quiet

The live 300-frame capture never reproduced the suitcase misread — 529 chair
detections, none of them a suitcase — because the walk never got close enough. So
the missing part of the approach was built out of real pixels: progressively
tighter 3:4 crops centred on a chair the detector is sure about, each resized back
to 960x1280, so its apparent size grows as it would if you kept walking. Same
sensor, same optics, same chair, losing context and gaining scale.

Four approaches, and all four say the same thing. The detector does not become
confidently wrong as an object fills the lens; it **fades out**:

    chair 0.93 -> 0.90 -> 0.71 -> 0.46 -> nothing -> nothing -> nothing

The wrong name arrives out of that silence, several frames after the object was
last seen: `toilet` at 0.43 on one approach, `bowl` at 0.39 on another. At a
three-frame memory the chair's track is long gone by then, so there is no history
for the vote to work with — which is why §12.2.7's voting, on its own, never
fired on any of the four.

`track_max_age_frames` is therefore 10 rather than 3: half a second instead of
150 ms. It costs nothing on its own, because with `track_coast_frames` at 0 an
unmatched track produces NO output — a longer memory holds an identity and a
name, never a phantom obstacle. Over the 300-frame capture, maxAge 3, 10 and 20
give byte-identical decisions on every frame. On the approaches it turns the
`bowl` back into a `chair` (IoU 0.775 against where the chair had been). The
`toilet` case stays wrong at IoU 0.541, under the 0.60 cross-name gate, and is
caught by the other defence instead: at 0.43 it is under the naming gate, so it
is never spoken. Across all four approaches the app never says a wrong name.

One correctness guard comes with the longer memory. Approach rate and the motion
vector are PER-FRAME quantities derived from two observations, and straddling a
ten-frame gap overstates them by an order of magnitude —
`APPROACHING_VEHICLE_CENTRE`, the one branch that bypasses the alert cooldown,
reads exactly that field. Beyond `track_motion_max_gap_frames` = 3 motion is
reported as unknown, the same rule coasting already followed. At the Python's
max age of 3 no gap can reach it, so the vectors never see it.

### 12.2.8 Containment collapses exactly when an obstacle gets dangerous

Found by auditing a 300-frame live capture: an office chair two metres dead
ahead, `chair` at 0.90, proximity IMMEDIATE, filling a third of the corridor —
and the banner read WALKING. Nine of that capture's 180 CLEAR frames had
something near and dead ahead.

Four signals landed just under their thresholds, and they are not independent
accidents:

| signal | value | gate |
| --- | --- | --- |
| `path_overlap` (containment) | 0.366 | 0.25 to count as centre |
| risk score | 0.518 (WATCH) | 0.65 for WARN |
| corridor centre cost | 0.362 | 0.40 |
| centre free floor | 0.557 | 0.20 |

The root cause is the first row. `path_overlap` is CONTAINMENT — the share of the
BOX that falls inside the corridor — and it is the heaviest term in the risk
score at 0.30. A large obstacle close to the lens overflows the corridor on every
side, so its containment **collapses as it becomes dangerous**. §12.2.1 gave the
corridor cost a separate obstruction measure for exactly this reason; the risk
score still carries the Python one, because `spatial.json` pins it. The free
floor reading of 0.557 has its own cause worth remembering: a mesh chair back
lets the carpet through, so segmentation genuinely sees floor where the chair is.

So the CAUTION branch was widened, not moved: an obstacle at IMMEDIATE proximity,
inside the centre path, above the watch band, is worth a word even when its score
has not reached WARN. CAUTION rather than a blocked-centre verdict is deliberate
— IMMEDIATE is estimated from apparent size and base height and reads a chair at
two metres as immediate, and a band that eager must not be able to stop someone
dead. The containment gate of 0.25 is untouched, so golden vector
`centre_object_below_overlap_gate_not_blocking` still holds.

Measured over the same 300 frames: five frames move from CLEAR to CAUTION and
nothing else changes.

### 12.3 Why the cascade, and why uncertainty is not danger

Rules 1–3 are *evidence-specific* and bypass ordinary scoring because an
approaching vehicle, a wall, and a level change are not the same kind of fact as
a chair being nearby. A single aggregate score cannot express that.

Rules 6 and 7 exist because **uncertainty is not danger.** Escalating every
unclear frame to a warning makes the system cry wolf until the user stops
listening; resolving it into a confident direction is a §3.1 violation.
`PAUSE_UNCLEAR` is the correct third answer and it is not a failure state.

---

## 13. Guidance state machine

### 13.1 The action vocabulary

Frozen in `packages/contracts`. **MUST NOT** be extended without also updating
the dashboard and the speech strings.

| Action | Meaning |
|---|---|
| `CLEAR` | Path ahead is viable. Minimal or no speech. |
| `CAUTION` | Something is present and worth knowing about. Continue. |
| `MOVE_LEFT` | Centre is blocked, left is viable. |
| `MOVE_RIGHT` | Centre is blocked, right is viable. |
| `STOP` | No viable path, or an immediate hazard. |
| `PAUSE_UNCLEAR` | Evidence is weak or contradictory. **Not a failure state.** |

`RiskLevel` is `CLEAR | WATCH | WARN | HIGH | CRITICAL`. The reason code carries
the *why* (Appendix C).

### 13.2 Output channels

Each action carries a spoken string, an icon shape, a colour, and a spatial-audio
character. Colour is never alone (§3.2). `haptic_pattern` remains in the contract
and the client still populates it, but haptics are outside this build's scope
(§2.5) and no acceptance test depends on them.

### 13.3 Persistence, hysteresis, and the four pending codes

Raw per-frame output flickers. Speaking every flicker is unusable. Port
`AlertStateMachine` exactly, including the states it emits *while* waiting:

- **`CRITICAL` commits immediately** and bypasses the cooldown. Never delay a
  critical stop for persistence, hysteresis, or a speech timer.
- **`PAUSE_UNCLEAR` commits immediately.** Admitting uncertainty is never delayed.
- **A repeat of the current decision commits immediately.**
- **A new non-critical decision needs `alert_persistence_frames` (2)** consecutive
  frames. While it is pending:
  - if the current action is `MOVE_LEFT` or `MOVE_RIGHT`, emit `PAUSE_UNCLEAR` /
    `WARN` / `DIRECTION_CHANGE_PENDING` — the previous direction is no longer
    trustworthy and continuing to assert it would be worse than admitting the
    gap;
  - otherwise emit a silent `CLEAR` / `WATCH` / `ALERT_PERSISTENCE_PENDING`.
- **De-escalation to `CLEAR` requires `alert_clear_frames` (3)** consecutive clear
  frames. While decaying, emit silent `CAUTION` / `WATCH` / `RISK_DECAY_PENDING`;
  if the evidence score is still above `risk_warn_exit` (0.50), emit silent
  `CAUTION` / `WATCH` / `RISK_HYSTERESIS_ACTIVE` and do not count the frame.
- **Speech cooldown:** `alert_cooldown_seconds` 3.0, bypassed by `CRITICAL`.
  **MEASURE** on a real walk — too long feels unresponsive, too short is chatter.

The asymmetry between escalation and de-escalation is the whole design: fast to
warn, slow to reassure.

> The four pending codes are not debug noise. They are how the machine stays
> honest during the frames when it does not yet know, and the golden vectors
> (§22, R3) assert them.

### 13.4 Preemption

```
priority 1  Walk safety guidance      (STOP, PAUSE_UNCLEAR, MOVE_*, CAUTION)
priority 2  Target guidance           (Ask → Lock → Guide)
priority 3  Scene answers, OCR results
priority 4  Ambient / status
```

**MUST:** priority 1 interrupts anything below it mid-utterance, using audio
focus and queue interruption. Do not wait for a scene description to finish
before saying "stop."

**MUST:** target spatial audio is muted whenever the risk action is not `CLEAR`.
Two directional cues competing for a blind user's attention is worse than one.

---

## 14. Target guidance — Ask, Lock, Guide

The user asks for something by name; the system locks it and guides them to it.
This is the second-most-valuable behaviour in the product and it **MUST NOT**
depend on a VLM.

### 14.1 Resolution order

```
spoken target
    │
    ▼
1. reject `person` targets outright                    → refuse, spoken
    │
    ▼
2. normalize the spoken word without destroying
   valid COCO nouns                                    → canonical form
    │
    ▼
3. search the multi-frame full-COCO landmark memory    → hit? go to 5
    │
    ▼
4. the live full-COCO view of the last walk frame      → box, or refuse
    │
    ▼
5. hand the normalized box to the on-device tracker    → GUIDING
```

> **As built.** Step 4 is the last frame's own detections, not a locator
> model: something that has just entered view but has not yet earned its
> second sighting is still findable, and a miss is spoken as a miss. **No VLM
> and no network are in this path**, which is stronger than the original
> design allowed for — the laptop snapshot locator described below was never
> ported, and `/vlm/locate` has been deleted from the client. The consequence
> is the one stated in step 4's original rationale: targets COCO cannot
> express (`registration desk`, `exit sign`, `door handle`) are refused aloud
> rather than guessed at. Scene Mode's VLM can describe such a thing when
> asked, but it does not lock or guide to it.

Step 3 answers the common case at zero extra inference cost, because the landmark
memory was populated by the same detector pass the walk loop already ran. A
`bottle`, `clock`, `book`, `laptop`, `cell phone`, `cup`, or `backpack` should
never pay VLM latency or memory.

What COCO cannot express — `registration desk` when no desk or table was seen,
`exit sign`, `light switch`, `door handle`, or a compositional request like
`an empty chair` — is therefore **refused**, out loud. That is the deliberate
trade: a locator good enough to find those is also good enough to invent them,
and guiding someone toward an invented box is the failure this section exists
to prevent.

> **MUST:** a locator miss is spoken as a miss. "I can't find that" is a correct
> answer. Guiding toward a guess is not.

### 14.2 States

`IDLE`, `SEEKING`, `GUIDING`, `ARRIVED`, `LOST`. These match the accepted target
guidance redesign and the client's existing DTOs.

> **MUST NOT** revive the older `LOCATING` / `LOCKED_TRACKING` names. The client,
> the contracts, and the tests use the five above.

### 14.3 Guidance behaviour

Ported parameters: `walk_camera_hfov_degrees` 67.0,
`target_turn_threshold_degrees` 25.0, `target_face_tolerance_degrees` 10.0,
`target_reacquire_timeout_seconds` 8.0, `target_arrived_dwell_seconds` 2.0,
`target_speech_interval_seconds` 4.0,
`target_tracking_confidence_threshold` 0.25.

- Relative bearing comes from the target's normalized horizontal coordinate
  against the camera field of view, or from the world bearing and device heading
  when both are available.
- Spatial audio pans from that same bearing through the existing
  `SpatialAudioEngine` and `SonarMapping`.
- Loss of the target produces a stop-and-rescan prompt **only** when no
  higher-priority safety prompt is active. After
  `target_reacquire_timeout_seconds`, the state becomes `LOST`.
- `ARRIVED` requires the dwell period, not a single frame.

---

## 15. Output layer — speech and spatial audio

All of this already exists in `apps/android/.../feedback/` and carries over. What
changes: it is driven by an in-process guidance object instead of a parsed HTTP
response. The interface is identical.

- **`SpeechEngine`** — TTS, tri-lingual (English, Hindi, Tamil), with
  `SpokenLanguage` selection. Strings in `GuidanceStrings.kt` and the `values-*`
  resource files. The selection is persistent and is exposed in Settings.
- **`SpatialAudioEngine`** + **`SonarMapping`** — directional cue rendering for
  left / centre / right and continuous target panning. This is the directional
  channel for this build (§2.5).
- **`AudioFocusManager`** — **MUST** duck rather than stop the user's own audio.
  Blind users very often have music or a podcast running; killing it is hostile.
  Critical speech uses focus and queue interruption (§13.4).
- **`GyroSteering`** — interpolates directional cues between inference results.
  With a faster on-device loop this matters less than it did, but it still
  smooths the experience and it is already written.
- **`HapticEngine`** — retained, still wired, not on the acceptance path (§2.5).

> **MUST:** these run on their own thread and never block the inference loop.

---

## 16. Threading and the single-accelerator scheduler

| Thread | Responsibility | Rules |
|---|---|---|
| Main / UI | Compose, overlay draw | Never blocks. Receives immutable snapshots. |
| Camera | `ImageAnalysis` callback | Converts, hands off, closes the proxy. Fast. |
| Accelerator | All NPU submissions | **Single scheduler. Serialized. Priority-ordered.** |
| Compute | Track, spatial, risk, guidance, target | Pure Kotlin, no I/O. |
| Output | Speech, spatial audio | Own thread. Isolated from everything. |
| Telemetry | Coordinator sync | **Fully detached.** Fire-and-forget. |

### 16.1 Rules

- **MUST** route every accelerator submission through one scheduler with the
  priority order of §8.1. Two concurrent submissions on one NPU will at best
  serialize internally and at worst fault or exhaust shared memory — and the
  failure is intermittent, which means it will appear during the demo and not
  during testing.
- **MUST NOT** submit a Class B model while a walk frame is in flight. Either
  wait for the in-flight frame, or explicitly and audibly pause the walk loop.
- **MUST NOT** allow the telemetry thread to block anything. If the laptop is
  gone, the walk continues without noticing. Enforce with a bounded queue that
  drops on overflow and never blocks on enqueue.
- **MUST** pass immutable snapshots between stages. A shared mutable detection
  list being read by the overlay while the tracker mutates it is a crash that
  will only appear under load — which is to say, during the demo.

---

## 17. Thermal and sustained performance

### 17.1 Why this is a first-class concern

The demo happens at the end, on a phone that has been running inference all
weekend, in a crowded warm room. Cold benchmarks are irrelevant. Sustained
thermal behaviour is what a live demonstration actually exercises.

### 17.2 Monitoring

```kotlin
val status = powerManager.currentThermalStatus
// THERMAL_STATUS_NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN
```

Poll every 5 s. Surface it in the diagnostics panel from §7.3.

### 17.3 The response ladder

| Thermal status | Response |
|---|---|
| `NONE`, `LIGHT` | Full rate. Detection every frame, segmentation every 2nd. |
| `MODERATE` | Segmentation every 4th frame. Cap capture at 5 fps. |
| `SEVERE` | Segmentation off. Announce degradation. Detection only. |
| `CRITICAL`+ | Suspend Walk Mode. **Tell the user out loud.** Do not fail silently. |

> **MUST:** the `CRITICAL` path speaks. A blind user walking with a phone that
> has quietly stopped analysing is in a materially more dangerous position than
> one who knows to stop and rely on their cane.

### 17.4 Practical mitigations

- Do not charge while demoing. Charging and sustained NPU load together throttle
  much faster.
- Screen brightness is a real thermal contributor. Walk Mode is screen-on for
  this build (§2.5), so keep brightness low during long soaks.
- Pause Office Kit screen mirroring for any measurement that will be quoted, and
  label the measurement accordingly (§20.2).
- **MEASURE 17.4.1** — run a 20-minute continuous walk and record frame time at
  minutes 1, 5, 10, and 20. The delta between minute 1 and minute 20 is the
  number that determines whether the demo holds up.

---

## 18. On-demand modes — Explore and Scene

Both are **Class B** (§5.1): check memory, load, one inference, unload, then
return. Both are explicit, gesture-triggered, and never continuous.

### 18.1 Explore Mode — read a sign

- Trigger: an explicit gesture. Never automatic, never in the walk loop.
- Capture one frame, run OCR, extract text and any route-number token
  (`BUS 42A CENTRAL` → route `42A`).
- Output `ReadTextResponse`, including an `OcrConfidenceQualification` of
  `HIGH | LOW | NONE`.
- **MUST** speak the qualification, not just the text. *"Low confidence: bus four
  two A"* is honest; reading it flatly implies a certainty the model did not have.
- **MUST NOT** block Walk Mode. Walk guidance continues throughout.

Current implementation: bundled ML Kit Latin text recognition on the phone CPU,
plus its bundled Latin + Devanagari model when Hindi is selected. Confidence
qualification and route announcements use the selected spoken language, while
recognized sign text is preserved verbatim. Tamil-script recognition is not a
shipping capability. The old `/explore` JPEG upload and retry path is deleted.
`READING` is a UI and speech state only; camera analysis, guarded NPU
detection/segmentation, risk and safety feedback remain active, and safety
speech may pre-empt the OCR readout.

### 18.2 Scene Mode — ask about what is in front

- Trigger: explicit gesture, then a spoken question.
- Memory check (§5.2) → load → one inference → unload → answer, via whichever
  rung of §6.4 is live.
- **MUST** refuse gracefully and audibly when memory is insufficient.
- **MUST** be preempted by Walk safety guidance mid-answer (§13.4).
- **MUST** cancel deterministically. Timeout: **MEASURE**, start at 15 s. On
  timeout, unload and say so.

> **MUST NOT** put a VLM in the continuous loop under any circumstance. This is
> both a memory rule and a §3 rule — a 2 B VLM's latency is far too high to
> produce safety guidance, and any architecture that lets it try will eventually
> speak a stale answer about a scene the user has already walked past.

---

## 19. The laptop coordinator and the dashboard

The laptop is not deleted. It is moved out of the walking path and given a
narrow, honest job.

### 19.1 What the coordinator keeps

| Responsibility | Endpoint / store |
|---|---|
| Coordinator health | `GET /api/v1/health` |
| One phone's activity, battery, GPS and safety facts | `POST /api/v1/monitor/telemetry` |
| The one live participant read by Monitor | `GET /api/v1/monitor/live-person` |

The current event Wi-Fi default is `http://172.26.252.170:8000`; it remains
editable/build-configurable because private addresses change with the network.
Both data endpoints require the same key from ignored local configuration. The
coordinator is **monitoring output**, not a dependency of Walk Mode.

### 19.2 The rule that defines it

> **MUST:** the walking experience is fully functional with the laptop absent,
> powered off, or out of range. Nothing in the guidance loop reads from the
> coordinator, waits on it, or checks whether it is connected.

Build it detached from day one. Retrofitting independence is much harder than
starting with it, and this property is the demo's whole argument.

### 19.3 What the coordinator must never become

- **MUST NOT** receive the CameraX frame stream. The dashboard gets bounded
  structured telemetry and, after explicit consent, confirmed hazard evidence.
  Streaming frames adds bandwidth and couples monitoring to the safety loop.
- **MUST NOT** be a fallback path for guidance. If the on-device pipeline
  degrades, the response is §21's ladder, not a request to the laptop.
  Reintroducing a network dependency under failure conditions restores precisely
  the coupling this migration exists to remove, at the moment the user is least
  able to tolerate it.
- **MUST NOT** be described as part of the on-device safety claim. If the
  optional locator is used, it is *laptop-assisted target localization*, and it
  hands back one normalized box; the phone owns the tracker and all subsequent
  high-rate guidance.

### 19.4 Implementation

- Bounded queue, capacity ~50, **drop-oldest** on overflow.
- Sync attempts on a detached coroutine with a short timeout.
- Failure is logged to the diagnostics panel, never surfaced as a user-facing
  error mid-walk.
- Reconnection **MUST NOT** replay stale safety instructions. Telemetry is a
  record of what already happened, not a command channel.
- The dashboard's health and model panels distinguish phone NPU execution from
  laptop CUDA execution (Appendix A), so a phone inference is never displayed as
  consuming laptop VRAM.

### 19.5 The monitor moves to a phone

The React dashboard in `apps/dashboard/` is being retired. Its replacement is a
second Android app, `apps/dashboard-android/` — *DRISHTI Monitor* — built for
the same reader and the same four questions, on the device that reader actually
carries.

The reason is not taste. The web dashboard is an HTTP client: it cannot be
opened without the FastAPI service, a laptop to run it on, and a network both
ends share. That is an acceptable dependency for an engineer at a bench and an
unacceptable one for a coordinator in a field tent, who is the person the
screen was written for. A monitoring tool that is unavailable exactly when the
programme is out walking is not a monitoring tool.

What carries over is the reading order — who is out and are they alright, who
needs me now, what is broken in the street — and the discipline behind it.
Three things are stated more strictly than the web version stated them:

- **Status is derived, never stored.** Four states (`Help required`,
  `Needs attention`, `No signal`, `Safe`) are computed from reported facts and
  the current time. Silence is never read as safety: ninety seconds without a
  frame is `No signal` whatever the last frame said, and it outranks a cheerful
  last reading.
- **Acknowledging is not resolving.** Taking a help request marks that somebody
  is on it so a second operator does not ring the same person. Only marking
  safe ends it. Those two being one control is how a desk loses somebody.
- **A hazard is counted once per person.** Corroboration means a second
  *person* independently walked into the same thing, not a second report; one
  person passing twice a day does not make a pothole more real. Confirmed and
  unconfirmed are kept visually distinct, because a works list that blurs them
  is a works list that gets discounted whole.

The app now ships a mixed repository behind the original `MonitorRepository`
seam. `DemoMonitorRepository` continues to own every sample person and hazard;
`MixedMonitorRepository` polls one access-key-protected live participant and
pins that card first. The masthead says *1 live + sample*—never simply *Live*—so
the origin of the rest of the wall stays explicit.

The walking app sends a compact envelope every two seconds through a capacity-1
drop-oldest channel: activity, battery, the latest foreground-only GPS fix, and
the current on-device guidance/obstacle fact. The coordinator turns a changed
non-clear verdict into an `OBSTACLE_DETECTED` event and suppresses repeats for
30 seconds. It receives no pixels. If Wi-Fi or the coordinator disappears, the
queue retains at most the newest observation and the safety loop continues
unchanged.

The web dashboard and the FastAPI service stay in the tree until the Android
one has a feed behind it. Deleting a working monitor before its replacement can
receive data would leave the programme with neither.

---

## 20. Office Kit — development control plane

> **Office Kit is how the team operates the machines. It is not a product
> transport.** Its documented capabilities are screen mirroring, remote input,
> shared clipboard, file transfer, and Remote PC — user-facing desktop features,
> not an inference or telemetry API.

### 20.1 What each capability is used for

| Capability | Legitimate DRISHTI use |
|---|---|
| Screen mirror | Run the phone UI, inspect camera overlays, reproduce accessibility states, show the live phone during development and demos. |
| Remote control | Operate the **phone** efficiently from the laptop keyboard and pointer. |
| Shared clipboard | Move prompts, short logs, target names, model hashes, benchmark results. |
| File transfer | Move APKs, compiled model assets, controlled fixtures, exported benchmark files. |

> **MUST NOT** send camera frames, inference results, or safety decisions through
> Office Kit. DRISHTI's optional phone-to-coordinator traffic uses its typed
> local HTTP/WebSocket contracts and nothing else. Remote-desktop pixels and
> input events are not a typed contract.

### 20.2 Measurement discipline

Mirroring and remote input add display, encoding, network, and thermal load. A
benchmark captured with Office Kit active is **not** comparable to one captured
without it.

**MUST** record whether mirroring was active for every latency, temperature,
power, or sustained-FPS measurement (§7.4). If the mirror materially distorts a
measurement, pause it for that labelled measurement only, then reconnect.

### 20.3 The Remote PC development loop

The recommended control plane: the code, Android SDK, Gradle cache, Git
checkout, coding-agent process, and build artifacts stay on the **laptop**; the
iQOO is the device from which the team operates that environment.

```text
iQOO phone
    |
    v
Office Kit Remote PC
    |
    v
Laptop terminal
    |
    +--> coding agent (Codex CLI / Claude Code)
    +--> Git
    +--> Android command-line tools
           +--> adb
           +--> gradle / gradlew
           +--> logcat
           +--> install / replace / uninstall APK
```

The build and validation cycle:

```text
agent changes repository code on laptop
              |
              v
      ./gradlew assembleDebug
              |
              v
         adb devices                (verify the target)
              |
              v
      adb install -r <apk-path>     (replace on the iQOO)
              |
              v
        run app on the iQOO
              |
              v
     Office Kit screen mirroring    (inspect UI, camera, behaviour)
              |
              v
     adb logcat --pid=<drishti-pid> (capture filtered logs)
              |
              v
     agent diagnoses and fixes ─────┐
              ^                     |
              └─────────────────────┘
```

This keeps one authoritative laptop checkout and toolchain, and makes Office Kit
a genuine continuous part of development rather than a feature opened only for
judging.

### 20.4 The enablement gate

Before relying on this workflow, prove on the event-supplied iQOO that:

- Office Kit exposes Remote PC for that exact phone and OriginOS build;
- the saved laptop can be reached and controlled for a sustained session;
- the terminal accepts keyboard shortcuts and multiline commands correctly;
- `adb devices` continues to show the same iQOO while Remote PC is active;
- the chosen USB or wireless-debugging transport survives APK replacement;
- `adb install -r` does not terminate the Office Kit control session;
- `adb logcat` can be captured while the DRISHTI application is foreground; and
- a failed build or crashed app leaves Remote PC usable for recovery.

**If the gate fails:** fall back to Office Kit screen mirroring and input plus a
supported phone-browser cloud task. **MUST NOT** rebuild the Android toolchain
inside Termux.

### 20.5 Workflows to reject

| Proposed workflow | Verdict |
|---|---|
| Coding agent running directly on Android / Termux | Unsupported host. Native dependencies, credentials, Gradle, and long-running process stability add avoidable failure modes. |
| Third-party relay exposing a local agent | Redundant when Remote PC works; adds credentials, relay availability, and supply-chain risk. |
| A cloud agent as the final build validator | Useful for repository checks, insufficient as proof: it has no loaner, no device runtime, no ADB, no profiler. |
| Multiple unsynchronized checkouts | Creates merge drift and makes it unclear which commit produced the installed APK. |
| Presenting Remote PC development as on-device AI | It is not. The application's NPU execution is separate evidence and is the evidence that counts. |

---

## 21. Degradation ladder

The order in which capability is surrendered under time or thermal pressure.
**Descend in order. Never skip.**

| # | State | User told? | Still useful? |
|---|---|---|---|
| 0 | Everything: detection, segmentation, OCR, locator | — | Full product |
| 1 | Drop Scene Mode / phone VLM | On request only | Yes |
| 2 | Drop OCR Explore Mode | On request only | Yes |
| 3 | Segmentation every 4th frame | No | Yes, slightly coarser |
| 4 | Segmentation off, detector-only corridor reasoning | **Yes, spoken** | Yes, more `PAUSE_UNCLEAR` |
| 5 | Detection on GPU instead of NPU | No | Yes, hotter |
| 6 | Detection on CPU | **Yes, spoken** | Barely — demo only |
| 7 | Walk Mode suspended | **Yes, spoken, insistent** | No |

> **MUST:** every level from 4 down is announced. The user's safety decisions
> depend on knowing what the system can currently see, and a silently degraded
> assistant is worse than an honestly absent one.

Losing the coordinator is **not** on this ladder. It costs the dashboard and the
optional locator, and nothing else.

---

## 22. Build plan — dependency gates

Dependency-ordered, not clock-ordered. **Stop at the first failed gate and take
the stated fallback.** A gate is passed by evidence, not by opinion.

> Each gate below is expanded into an executable task card — files to touch,
> commands to run, acceptance checks, and what to do when it fails — in
> [`BUILD_PLAN.md`](BUILD_PLAN.md).

### R0 — Freeze behaviour and evidence

- Tag or branch the known-working laptop implementation.
- Export golden JSON vectors from the existing tests: detector canonicalization
  and aliasing, spatial geometry, risk scoring, the frame rule cascade, state
  machine persistence and hysteresis including the four pending codes, safety
  preemption, and target state transitions.
- Record the current dashboard contracts and the Kotlin baseline tests.

**Gate:** existing Python, dashboard, and Kotlin tests pass; golden fixtures are
committed; no accepted behaviour is ambiguous.

### R1 — Prove the phone inference runtime

- Run the smallest official Qualcomm object-detection sample on the loaner.
- Confirm camera tensor format, quantization, output layout, and the accelerator
  actually used.
- Profile YOLO11 first; YOLOv8 only if YOLO11 measurably fails (§6.1).

**Gate:** repeated NPU execution verified on the physical device, with runtime or
profiler evidence, and an acceptable measured camera-to-box latency. Otherwise
use the best proven official detector sample and reduce model scope.

### R2 — Replace the network inference seam

- Introduce an `OnDeviceDetector` boundary under the existing CameraX pipeline.
- Keep latest-frame-wins and the freshness rejection.
- Produce **both** detection views from one inference (§9.3).
- Render aligned boxes through the existing preview transform.

**Gate:** ten repeated controlled frames produce aligned, fresh detections with no
unbounded queue and no FastAPI dependency in the walking path.

### R3 — Port deterministic safety behaviour

- Port tracking, normalized corridor geometry, relative proximity, weighted
  scoring, the critical-override cascade, persistence, hysteresis, and
  uncertainty handling.
- Run the R0 golden vectors against the Kotlin implementation.

**Gate:** cross-language parity on **decision, reason code, preferred corridor,
and critical override** for every golden success *and* failure case; an
actionable risk always preempts target guidance.

### R4 — Finish phone-owned accessible output

- Drive the existing speech and audio-focus components from the in-process
  guidance object.
- Refine `SpatialAudioEngine` and `SonarMapping` for left / centre / right and
  target panning.
- Haptics are out of scope for acceptance (§2.5).

**Gate:** visible guidance, spoken action, and spatial audio agree on the same
frame; a critical warning interrupts target audio immediately.

### R5 — Add segmentation only after proof

- Attempt the exact ADE20K semantic model on the selected runtime (§6.2).
- Validate class mapping, mask transform, and sustained resource use.

**Gate:** the existing indoor floor/wall/stairs fixtures pass on the phone. If
not, omit segmentation, announce the degradation, and report detector-only
uncertainty honestly.

### R6 — Port Ask → Lock → Guide

- Populate the bounded full-COCO landmark memory from the same detector pass.
- Resolve eligible requests from memory and initialize the on-device tracker.
- Drive target state and spatial audio from live phone frames.

**Gate:** detector-memory target lock, tracking, loss, rescan, and safety
preemption all pass **without a VLM in the path**.

### R7 — Connect the coordinator and the existing dashboard

- Send structured telemetry and confirmed hazard events only.
- Adapt health and model panels to distinguish phone NPU from laptop CUDA.
- Preserve SQLite and the exports.

**Gate:** dashboard loss or coordinator failure cannot affect phone guidance, and
reconnection does not replay stale safety instructions.

### R8 — Optional locator fallback

- Attempt a phone VLM only if every earlier gate is stable (§6.4).
- If it fails, enable the existing laptop Moondream2 snapshot locator.
- Hand only the returned normalized box and label to the phone tracker.

**Gate:** bounded memory, timeout, cancellation, target handoff, and safety
preemption all pass; no continuous VLM invocation exists anywhere in the build.

> **Met, by a different route.** Locating never needed a VLM: it resolves from
> landmark memory and the live detector view, so there is no locator fallback
> to enable and the laptop rung was not ported. The gate's other clauses are
> satisfied by Scene Mode, which is the only VLM in the build: it is loaded for
> exactly one question and freed before the call returns, it declines when free
> memory is short, and cancellation is real — `llama_set_abort_callback` polls
> between graph nodes, so a cancel lands mid-prefill and the caller waits for
> the native call to unwind rather than abandoning it. A device test cancels
> 500 ms into a call and gets a full answer from the next one. Safety
> preemption is enforced in the walk loop, which never yields a frame to the
> VLM. No continuous VLM invocation exists anywhere in the build.

---

## 23. Verification plan

### 23.1 The test that matters

> **Walk a real corridor with the coordinator powered off. Guidance continues,
> correctly, for ten minutes.**

If this passes, the architecture holds: the phone owns the loop. Run it at R3 and
again after the feature freeze.

### 23.2 Functional checks

| # | Test | Pass condition |
|---|---|---|
| 1 | Clear hall | Reaches `CLEAR` / `PATH_CLEAR`; safe polygon on the floor |
| 2 | Frontal wall | Stabilizes to `WALL_OR_DEAD_END_AHEAD`, no movement cue into it |
| 3 | Side wall, open centre | Guides `CENTRE`, does **not** stop |
| 4 | Stairs | Stabilizes to `STAIRS_OR_LEVEL_CHANGE_AHEAD` |
| 5 | Person approaching | Escalates as they close; `APPROACHING` set |
| 6 | Obstacle centre, left clear | `MOVE_LEFT` / `CENTRE_BLOCKED_CLEARER_SIDE` |
| 7 | Obstacle centre, neither side defensible | `PAUSE_UNCLEAR` / `CENTRE_BLOCKED_DIRECTION_UNCLEAR` |
| 8 | Camera covered | `PAUSE_UNCLEAR`, never a confident direction |
| 9 | Lens smeared / low light | `PAUSE_UNCLEAR`, degradation announced |
| 10 | Direction change under persistence | Emits `DIRECTION_CHANGE_PENDING`, not a stale side cue |
| 11 | Overlay alignment | `(0,0,1,1)` box traces the full oriented capture, both orientations |
| 12 | Coordinator powered off mid-walk | No interruption whatsoever, no retry storm |
| 13 | Wi-Fi lost mid-walk | Identical behaviour to test 12 |
| 14 | 20-minute soak | Frame time at minute 20 within 2× of minute 1 |
| 15 | Target from landmark memory | Locks and guides with no VLM invoked |
| 16 | Target request during a `STOP` | Safety speech wins; target audio muted |
| 17 | Scene Mode under memory pressure | Refuses audibly, walk continues |
| 18 | Golden vector parity | Kotlin matches Python on decision, reason code, corridor, override |

> Tests 8, 9, and 10 are the ones most likely to be skipped and are the most
> important in the set. They verify the system admits uncertainty rather than
> inventing confidence, which is the §3 behaviour that separates this from a demo
> toy.

### 23.3 Physical testing safety

**MUST NOT** test blindfolded. The parent project's rules are explicit: physical
testing uses controlled environments and never involves unsafe blindfolded
walking. A sighted tester holds the phone and evaluates whether the guidance
*would have been* correct and sufficient.

---

## 24. Unknowns to resolve on-site, in order

Work top to bottom. Each answer changes what is worth attempting below it.
**Nothing else starts until items 1–4 are answered.**

| # | Question | If the answer is bad |
|---|---|---|
| 1 | RAM variant — 12 or 16 GB? | 12 GB → this document as written. |
| 2 | Can we install a custom APK? | No → the entire plan is void. Escalate to organisers immediately. |
| 3 | Can custom model files be loaded? | No → AI Hub prebuilt only, no custom quantization. |
| 4 | Is the NPU reachable from a third-party app? | No → GPU path, and the on-device-AI claim weakens sharply. Say so plainly rather than implying NPU. |
| 5 | Which SDK/runtime do organisers provide? | Shapes §7 entirely. |
| 6 | Is internet available during setup? | No → models must be pre-staged on the laptop before arrival. |
| 7 | Does Office Kit Remote PC work on this firmware? | No → §20.4 fallback; do not rebuild the toolchain on the phone. |
| 8 | Does ADB survive alongside Remote PC? | No → mirror-and-input workflow only, with builds driven at the laptop directly. |
| 9 | Does NexaSDK initialise on 12 GB? | No → §6.3 rung 2/3, and the phone VLM drops to §6.4 rung 2. |
| 10 | Is AI Hub reachable during the event? | No → pre-download every candidate model beforehand. |
| 11 | Can accelerator telemetry be shown to judges? | No → the diagnostics panel is the only evidence, and it must be honest about what it can and cannot prove. |

> **Item 6 deserves emphasis: pre-stage every candidate model on the laptop
> before travelling to Chennai.** Downloading multi-gigabyte model files over
> venue Wi-Fi shared with a thousand builders is a way to lose four hours at
> exactly the moment they are worth the most. Bring them on disk. This is
> preparation, not pre-built code, and it is entirely within the rules.

---

## Appendix A — contract types and proposed amendments

From `packages/contracts/src/index.ts`. The pipeline **MUST** produce these
shapes. The dashboard and the Android client are already written against them and
neither is being rebuilt.

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

`haptic_pattern` stays in the contract and the client keeps populating it. It is
simply not on this build's acceptance path (§2.5).

### Proposed amendments

Moving execution to the phone changes more than one enum value. Execution
location, timing ownership, model state, and telemetry direction all change, and
the dashboard must not be left implying that a phone inference consumed laptop
VRAM.

**These are proposals.** They are not in force until recorded in
`docs/DECISIONS.md`, with tests in Python, TypeScript, and Kotlin.

| # | Amendment | Why |
|---|---|---|
| 1 | `ComputeDevice` becomes `"NPU" \| "GPU" \| "CUDA" \| "CPU" \| "NONE"` | Adds phone execution while keeping `CUDA` valid for the coordinator and the optional laptop locator. |
| 2 | Model status carries an execution owner: `"PHONE" \| "LAPTOP"` | So the dashboard never displays phone NPU inference as laptop VRAM consumption. |
| 3 | A phone-to-coordinator telemetry envelope | Frame id and time, phone-measured stage timings, detector and segmenter state, guidance action, reason code, target state, and safety override. **No image bytes.** |
| 4 | Normalized box/point coordinates and target state names are unchanged | `ORIENTED_CAPTURE_NORMALIZED` and `IDLE`/`SEEKING`/`GUIDING`/`ARRIVED`/`LOST` stay exactly as they are. |
| 5 | Locator confidence becomes nullable | Moondream2 returns a box without a calibrated probability. `null` is the truthful value; a fabricated `0.85` is not. |
| 6 | A compatibility adapter for the dashboard | It keeps working against the current shapes while it learns phone-owned telemetry. |
| 7 | `http://172.26.252.170:8000` is the current event-LAN coordinator default | Build-time configuration for observational Monitor telemetry only; **not** a Walk Mode dependency. |

---

## Appendix B — the 19-class risk set

The audited allowlist applied to the risk view after inference (§9.3), with the
class severities that feed the 0.15-weighted severity term of §12.1. These are
the values in the working implementation; **MUST** port them as-is and change
them only as a measured, versioned tuning decision.

| Canonical class | Severity | Note |
|---|---:|---|
| `motorcycle` | 1.00 | Fast and common in Indian street contexts |
| `bus` | 1.00 | |
| `car` | 0.95 | |
| `refrigerator` | 0.85 | Large fixed obstruction |
| `desk` | 0.80 | Low edges, hard to see. Alias of `dining table`, `table` |
| `bicycle` | 0.80 | Fast, quiet, often unnoticed |
| `bed` | 0.80 | |
| `door` | 0.80 | **Not reachable from stock COCO weights — see below** |
| `chair` | 0.75 | The most common indoor obstacle |
| `couch` | 0.75 | |
| `toilet` | 0.75 | |
| `potted plant` | 0.70 | Very common in Indian corridors and lobbies |
| `bench` | 0.65 | Static trip hazard |
| `suitcase` | 0.65 | |
| `sink` | 0.65 | |
| `person` | 0.55 | Highest when `APPROACHING`. Never identified, only detected |
| `umbrella` | 0.55 | |
| `bag` | 0.45 | Floor clutter. Alias of `backpack`, `handbag` |
| `tv` | 0.45 | |

**Aliases applied to the risk view only:** `backpack` → `bag`, `handbag` → `bag`,
`dining table` → `desk`, `table` → `desk`. The full-COCO landmark view keeps
native names and applies **no** aliases, so a user who asks for a "backpack" is
answered with a backpack.

### The `door` trap

Stock COCO weights have no `door` class (Appendix D). Therefore:

- the allowlist contains 19 names;
- only **18** are reachable from a stock YOLO detector, aliases included;
- `door` cannot be emitted by the stock detector at all; and
- current door evidence comes from **ADE20K segmentation**, not from YOLO.

The forward-compatible `door` entry is harmless. **A claim that the detector finds
doors is false** unless a custom detector is trained and measured. If segmentation
does not ship (§6.2), door evidence does not exist and **MUST NOT** be advertised.

### On severity and the classes that are absent

`traffic light` and `stop sign` are not in the risk set, and adding them is the
most tempting §3.1 violation available in this codebase. If they are ever added,
they are context only: the system may report that a traffic light is present. It
never reports what it means.

---

## Appendix C — reason codes

`GuidanceContract.reason_code` carries the *why* for the dashboard, for the
golden vectors, and for debugging. Not spoken verbatim. These are the codes the
working implementation emits; the Kotlin port **MUST** produce the same code for
the same input (§22, R3).

### Committed decisions

| Code | Trigger | Action / level |
|---|---|---|
| `PATH_CLEAR` | No blocking evidence | `CLEAR` |
| `OBSTACLE_NEARBY` | Highest-scoring detection at `WARN` or `HIGH` | `CAUTION` / `WARN` |
| `CENTRE_BLOCKED_CLEARER_SIDE` | Centre blocked, one side clearly better and genuinely walkable | `MOVE_LEFT` or `MOVE_RIGHT` / `HIGH` |
| `CENTRE_BLOCKED_DIRECTION_UNCLEAR` | Centre blocked, no side defensible by `decision_margin` | `PAUSE_UNCLEAR` / `WARN` |
| `CENTRE_SURFACE_UNCERTAIN` | Centre corridor surface evidence insufficient | `PAUSE_UNCLEAR` / `WARN` |
| `ALL_CORRIDORS_BLOCKED` | Every corridor over threshold | `STOP` / `HIGH`, `CRITICAL` if `IMMEDIATE` in centre |
| `APPROACHING_VEHICLE_CENTRE` | Vehicle above the critical overlap, proximity, and approach thresholds | `STOP` / `CRITICAL`, bypasses cooldown |
| `WALL_OR_DEAD_END_AHEAD` | Stabilized frontal wall (segmentation) | `STOP` / `HIGH` |
| `STAIRS_OR_LEVEL_CHANGE_AHEAD` | Stabilized stairs or level change (segmentation) | `STOP` / `HIGH` |

### Pending and hysteresis states

Emitted while the state machine is waiting. All four are silent — they change the
displayed state without speaking.

| Code | Trigger | Emitted as |
|---|---|---|
| `ALERT_PERSISTENCE_PENDING` | A new decision has not yet held for `alert_persistence_frames` | silent `CLEAR` / `WATCH` |
| `DIRECTION_CHANGE_PENDING` | A pending change while the current action is `MOVE_LEFT` or `MOVE_RIGHT` | `PAUSE_UNCLEAR` / `WARN` |
| `RISK_DECAY_PENDING` | Decaying toward `CLEAR`, not yet `alert_clear_frames` | silent `CAUTION` / `WATCH` |
| `RISK_HYSTERESIS_ACTIVE` | Evidence still above `risk_warn_exit` while decaying | silent `CAUTION` / `WATCH` |

> A port that drops these four will look correct in a demo and fail the golden
> vectors, because they are the difference between a system that flickers and a
> system that holds its tongue until it knows.

---

## Appendix D — the COCO label list

The 80 names used by stock YOLO COCO weights, for reference when deciding whether
a spoken target can be answered from landmark memory (§14.1) or needs the
locator:

`person`, `bicycle`, `car`, `motorcycle`, `airplane`, `bus`, `train`, `truck`,
`boat`, `traffic light`, `fire hydrant`, `stop sign`, `parking meter`, `bench`,
`bird`, `cat`, `dog`, `horse`, `sheep`, `cow`, `elephant`, `bear`, `zebra`,
`giraffe`, `backpack`, `umbrella`, `handbag`, `tie`, `suitcase`, `frisbee`,
`skis`, `snowboard`, `sports ball`, `kite`, `baseball bat`, `baseball glove`,
`skateboard`, `surfboard`, `tennis racket`, `bottle`, `wine glass`, `cup`,
`fork`, `knife`, `spoon`, `bowl`, `banana`, `apple`, `sandwich`, `orange`,
`broccoli`, `carrot`, `hot dog`, `pizza`, `donut`, `cake`, `chair`, `couch`,
`potted plant`, `bed`, `dining table`, `toilet`, `tv`, `laptop`, `mouse`,
`remote`, `keyboard`, `cell phone`, `microwave`, `oven`, `toaster`, `sink`,
`refrigerator`, `book`, `clock`, `vase`, `scissors`, `teddy bear`, `hair drier`,
`toothbrush`.

"COCO 80" refers to this common model label list. The underlying COCO dataset's
category identifiers are **not** a contiguous 0–79 sequence, so never index one
by the other.

---

## Note on scope

The models are the tractable part of this migration: each has a listed, supported
counterpart in Qualcomm's catalogue and a documented conversion path, and each
has a gate in §6 that says what to do when the path does not hold.

The behaviour is the hard part — knowing when to report uncertainty, refusing to
convert weak evidence into a confident direction, and announcing degradation
audibly. Those behaviours already exist, tested, in Python. The work is moving
them to Kotlin **without changing them**, which is why §22 puts golden vectors
before features and why every threshold in this document is quoted from the
implementation rather than invented here.

The most defensible build is not a rewrite. It is one detector invocation on the
phone feeding an audited risk view and a full-COCO target memory, the existing
safety logic ported under parity, the existing Kotlin client refined at one seam,
and a laptop that keeps the dashboard without ever standing between the user and
the ground in front of them.
