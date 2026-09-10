# DRISHTI iQOO Phone-First Architecture Revisions

**Status:** Proposed technical revision for team approval  
**Scope:** Hackathon architecture, implementation order, model/runtime gates, Office Kit development workflow, and compatibility with the current repository  
**Review basis:** The proposed replacement architecture in [`ARCHITECTURE.md`](https://github.com/madhavsk-programs/drishti-edge/blob/main/ARCHITECTURE.md), the current repository implementation, and the public iQOO/Qualcomm/OpenAI/Anthropic documentation linked below

This document proposes changes; it does not silently supersede
`docs/DECISIONS.md`, `docs/API_CONTRACTS.md`, or
`docs/IMPLEMENTATION_PLAN.md`. Accepted architecture and wire-contract changes
must be recorded separately after approval.

## 1. Executive decision

The smartphone-first re-architecture is the correct direction for this
hackathon, but the linked plan should not be implemented literally.

The revised product boundary should be:

- The iQOO phone owns the live camera, on-device object detection, tracking,
  spatial reasoning, safety decisions, speech, spatial audio, and the active
  Ask -> Lock -> Guide state.
- The existing Kotlin application is refined rather than discarded. It already
  has CameraX processing, frame freshness, capture pacing, preview transforms,
  gestures, speech, spatial audio, sonar mapping, API DTOs, and target-location
  seams.
- The existing dashboard is retained.
- The laptop keeps only a small FastAPI coordinator for dashboard telemetry,
  hazard persistence, health/model statistics, and report export. It is not in
  the critical walking-frame path.
- The current laptop Moondream2 locator remains available only as a last-resort,
  explicit snapshot service if an acceptable phone VLM cannot be proved.
- Continuous safety must never depend on a VLM, the laptop, Office Kit, or a
  network round trip.

This gives the judges a genuine on-device safety loop without throwing away the
two mature parts of the repository: Kotlin and the dashboard.

## 2. Deliberate scope for this revision

The following are not build requirements for this hackathon revision:

- screen-off CameraX operation;
- flight-mode or public-network-disconnection demonstrations;
- eligibility, team-bucket, or end-user-validation planning;
- haptic guidance;
- a schedule copied from the event agenda; and
- rebuilding the dashboard.

Directional output is spatial audio plus concise speech. The build plan in this
document is dependency-ordered and gate-driven, not tied to event clock times.

## 3. Office Kit: use it throughout, but understand what it is

### 3.1 Recommendation

Keep Office Kit connected and use it genuinely during both Red and Green Light.
This is technically sensible because the event page says HackTracker measures
Office Kit **counts and durations**, and Office Kit contributes 10% of the total
score. The public rules do not publish a formula proving that every additional
minute produces a linear score increase, so continuous use improves the evidence
trail but does not guarantee a particular mark.

Use all four visible capabilities:

| Office Kit capability | Legitimate DRISHTI use |
|---|---|
| Screen mirror | Run the phone UI, inspect camera overlays, reproduce accessibility states, and show the live phone during development. |
| Remote control | Use the laptop keyboard and pointer to operate the **phone** efficiently. |
| Shared clipboard | Move prompts, short logs, target names, model hashes, and benchmark results. |
| File transfer | Move APKs, compiled model assets, controlled fixtures, and exported benchmark files. |

During Green Light, leave the phone in the loop even while Android Studio,
profilers, and the laptop coordinator are available. This produces honest Office
Kit usage and prevents the phone from becoming a passive camera peripheral.

For controlled latency, temperature, power, or sustained-FPS measurements,
record whether screen mirroring is active. Mirroring and remote input add display,
encoding, network, and thermal load; a benchmark captured with Office Kit active
is not directly comparable to one captured without it. If the mirror materially
distorts a measurement, pause it only for that labelled measurement and reconnect
afterward.

Official basis: the [iQOO hackathon page](https://iqoo.reskilll.com/) describes
the 55/45 Red/Green split, phone-first use, the Office Kit score, and counts and
durations; the [vivo Office Kit page](https://pc.vivo.com/) documents Remote PC,
mirroring, clipboard, file transfer, and keyboard/mouse coordination.

### 3.2 Recommended Office Kit Remote PC development path

Use Office Kit Remote PC as the primary control plane in both Red and Green
Light. The code, Android SDK, Gradle cache, local repository, Codex/Claude Code
process, and build artifacts stay on the laptop. The iQOO is the device from
which the team operates that laptop environment.

```text
iQOO phone
    |
    v
Office Kit Remote PC
    |
    v
Laptop terminal
    |
    +--> Codex CLI or Claude Code
    +--> Git
    +--> Android command-line tools
           +--> adb
           +--> gradle / gradlew
           +--> logcat
           +--> install / replace / uninstall APK
```

This is the preferred approach because it keeps one mature laptop checkout and
toolchain instead of attempting to reproduce Android development inside Termux.
It also makes Office Kit an authentic, continuous part of development rather
than a feature opened only for judging.

Office Kit Remote PC remains a **development control plane**, not a DRISHTI
runtime data plane. The product must not send camera frames or safety decisions
through Office Kit APIs. DRISHTI's optional phone-to-dashboard traffic continues
to use its typed local HTTP/WebSocket contracts.

#### Required enablement gate

Before relying on this workflow, prove on the event-supplied iQOO that:

- Office Kit exposes Remote PC for the exact phone/OriginOS build;
- the saved laptop can be reached and controlled for a sustained session;
- the terminal accepts keyboard shortcuts and multiline commands correctly;
- `adb devices` continues to show the same iQOO while Remote PC is active;
- the chosen USB or wireless-debugging transport survives APK replacement;
- `adb install -r` does not terminate the Office Kit control session;
- `adb logcat` can be captured while the DRISHTI application is foreground; and
- a failed build or crashed app leaves Remote PC usable for recovery.

Remote PC availability is an official Office Kit capability, but compatibility
must still be verified on the exact loaner firmware. If the feature is missing
or unstable, fall back to Office Kit screen mirroring/input plus a supported
phone-browser cloud task; do not rebuild the Android toolchain inside Termux.

### 3.3 Codex and Claude Code through Remote PC

Codex CLI and Claude Code run on the laptop, inside the real repository. The
iQOO controls their terminal through Office Kit Remote PC.

Recommended working pattern:

1. Open Office Kit Remote PC on the iQOO and connect to the saved laptop.
2. Open the repository terminal on the laptop.
3. Start Codex CLI or Claude Code in `C:\\Drishti AI`.
4. Give it one bounded implementation or diagnosis task.
5. Review the diff and test output through the Remote PC session.
6. Run the relevant Gradle and Python/dashboard checks.
7. Install the APK onto the same iQOO through ADB.
8. Switch to Office Kit screen mirroring for visual and behavioral validation.
9. Capture filtered logcat output and return to the coding agent for the next
   correction.

This avoids unsupported Android hosting: the
[Codex CLI](https://developers.openai.com/codex/cli) and
[Claude Code](https://docs.anthropic.com/en/docs/claude-code/getting-started)
processes stay on their supported laptop environment. The iQOO supplies the user
interaction and Office Kit activity, while the actual APK is always validated on
the physical target device.

[Codex cloud](https://developers.openai.com/codex/cloud) remains a secondary
fallback for phone-origin repository tasks if Remote PC is temporarily
unavailable. It is not the primary loop because a cloud worker cannot access the
loaner's ADB connection, proprietary runtime, signing state, or local NPU
profiler.

#### Workflows to reject

| Proposed workflow | Technical verdict |
|---|---|
| Codex CLI directly on Android/Termux | Unsupported host; native dependencies, credentials, Gradle, and long-running process stability add avoidable failure modes. |
| Claude Code directly on Android/Termux | Android is outside the documented host matrix. Keep Claude Code on the laptop through Remote PC. |
| Third-party relay exposing a local agent | Redundant when Office Kit Remote PC works; adds credentials, relay availability, and supply-chain risk. |
| Cloud agent as the final build validator | Useful for repository checks, but insufficient because it lacks the loaner, device runtime, ADB, and profiler. |
| Editing through multiple unsynchronized checkouts | Creates merge drift and makes it unclear which commit produced the installed APK. Use one authoritative laptop checkout. |

### 3.4 Remote build and validation loop

```text
Codex / Claude Code changes repository code on laptop
                         |
                         v
              Build APK on laptop
            ./gradlew assembleDebug
                         |
                         v
               Verify ADB target
                  adb devices
                         |
                         v
        Install/replace APK on the iQOO
          adb install -r <apk-path>
                         |
                         v
              Run app on the iQOO
                         |
                         v
          Office Kit screen mirroring
                         |
                         v
      Inspect UI, camera, crashes, and behavior
                         |
                         v
              Capture filtered logcat
        adb logcat --pid=<drishti-pid>
                         |
                         v
      Codex / Claude Code diagnoses and fixes
                         |
                         +------> rebuild and repeat
```

Each installed APK should be traceable to a Git commit and build variant. Save
the commit hash, APK checksum, model checksum, device serial, and measured test
result together. This prevents an apparently successful phone demo from being
mistakenly attributed to older code or model assets.

### 3.5 What produces HackTracker value

The public rubric supports these conclusions:

- Office Kit points come from actual bridge activity recorded as counts and
  durations.
- Creative phone-use points come from the product using the phone camera, voice,
  and on-device AI.
- There is no published evidence that Codex or Claude usage receives a separate
  HackTracker bonus.
- Operating the laptop terminal, Codex/Claude Code, Gradle, ADB, and logcat from
  the iQOO through Remote PC produces genuine Office Kit usage. The public
  scoring material does not disclose whether particular laptop applications are
  distinguished, so do not claim agent-specific points.
- Remote PC development should not be presented as on-device AI in the DRISHTI
  product. The application's phone NPU execution is separate evidence.
- The strongest technical evidence remains sustained camera use, verified NPU
  inference, phone-owned safety logic, spatial audio, and a live device demo.

The safe optimization is sustained, purposeful Office Kit use—not artificial
reconnects or activity intended only to inflate counters.

## 4. Corrected target architecture

### 4.1 Red Light development architecture

```text
                         RED LIGHT

                            iQOO
                     +----------------+
                     |  DRISHTI app   |
                     +--------+-------+
                              |
                +-------------+-------------+
                |                           |
                v                           v
       Office Kit screen mirror     Office Kit Remote PC
                |                           |
                v                           v
       inspect app visually                laptop
       and exercise controls                |
                                  +---------+----------+
                                  |         |          |
                                  v         v          v
                              Codex /    Android     Gradle
                            Claude Code    CLI       wrapper
                                              |
                                      +-------+-------+
                                      |               |
                                      v               v
                                     ADB            logcat
                                      |
                                      v
                              install APK on iQOO
                                      |
                                      +----> repeat validation loop
```

The phone is the operator surface for the complete loop. Office Kit Remote PC
drives the laptop toolchain; screen mirroring validates the installed application
on the same iQOO. ADB closes the loop between the laptop build output and the
physical phone.

### 4.2 Product runtime architecture

```text
                         iQOO 15 PHONE

CameraX (screen on)
      |
      v
latest-frame slot --> preprocess/rotate/letterbox
      |                         |
      |                         v
      |                 phone NPU detector
      |                         |
      |                  +------+------+
      |                  |             |
      |                  v             v
      |          audited risk view   full COCO view
      |                  |             |
      |                  v             v
      |       tracker + corridor     target memory
      |       + proximity + risk         |
      |                  |               +--> Ask -> Lock -> Guide
      |                  v
      |          safety state machine
      |                  |
      +------------------+--> speech + spatial audio
                                 |
                   safety always preempts target audio

                       optional, non-critical telemetry
                                 |
                                 v
                    MINIMAL LAPTOP FASTAPI
             health + hazards + dashboard feed + SQLite
                                 |
                                 v
                     EXISTING REACT DASHBOARD

Last-resort only:
explicit current snapshot --> laptop Moondream2 /locate --> box --> phone tracker
```

### Hard runtime rules

- One active phone inference and at most one replaceable pending frame.
- Latest frame wins; stale outputs are rejected.
- The detector and safety path are long-lived and prioritized.
- VLM/OCR work is explicit and one-shot, never continuous.
- An actionable safety decision suppresses target speech and spatial-audio cues.
- Loss of laptop telemetry cannot stop phone safety processing.
- The dashboard receives structured telemetry, not the continuous camera stream.
- The laptop VLM fallback accepts only an explicit bounded snapshot and releases
  its model resources after the request.

## 5. COCO classes: current implementation versus the proposed phone port

The belief that the repository has “80-class COCO” is correct, but only for one
branch of the current detector output.

### 5.1 What the current backend actually does

`backend/app/perception/detector.py` performs **one** YOLO11n inference and
returns a `DetectionSet` with two filtered views:

| View | Classes | Consumer | Purpose |
|---|---:|---|---|
| `all` | All above-threshold native YOLO COCO classes (normally 80) | Landmark memory only | Remember targets such as bottle, clock, book, laptop, and cup without a second model pass. |
| `risk` | 19 configured canonical labels | Tracker, spatial analysis, risk scoring, overlays, and public walk detections | Keep safety behavior narrow and auditable. |

The full-COCO branch is enabled by default through
`landmark_full_coco = True`. It preserves native names and does not apply aliases.
The risk branch applies these aliases:

- `backpack` and `handbag` -> `bag`
- `dining table` and `table` -> `desk`

The configured risk set is:

`person`, `chair`, `bag`, `desk`, `bicycle`, `motorcycle`, `car`, `bus`,
`bench`, `door`, `suitcase`, `umbrella`, `potted plant`, `couch`, `bed`, `tv`,
`refrigerator`, `sink`, and `toilet`.

### 5.2 The `door` trap

Stock Ultralytics COCO weights use the common 80-class COCO label set. `door` is
not one of those classes. Consequently:

- the allowlist contains 19 names;
- only 18 canonical names are reachable from stock YOLO11n, including aliases;
- `door` cannot be emitted by the stock detector; and
- current door evidence comes from ADE20K segmentation, not YOLO.

The forward-compatible `door` entry is harmless, but any claim that YOLO detects
doors is false unless a custom detector is trained and measured.

### 5.3 Correct phone behavior

Port the **two-view design**, not merely a list of 80 labels:

```text
one phone detector invocation
    -> full native COCO detections -> TTL target memory
    -> aliased audited detections  -> tracker/spatial/risk
```

Do not send all 80 classes into the safety engine. A COCO label describes an
object; it does not establish that the object obstructs the walking corridor.
The safety path must still use position, corridor overlap, relative proximity,
approach, confidence, class severity, and uncertainty.

For an Ask request:

1. reject person targets;
2. normalize the spoken target without destroying valid COCO nouns;
3. search recent multi-frame full-COCO memory;
4. lock a matching box and hand it to the phone tracker;
5. use a one-shot VLM only when the requested target is not represented by COCO
   or is absent from memory.

Examples such as `bottle`, `clock`, `book`, `laptop`, `cell phone`, `cup`, and
`backpack` should use detector memory. Examples such as `registration desk`
when no table/desk match is present, `exit sign`, `light switch`, `door handle`,
and `empty chair` as a compositional request need a locator/VLM path.

### 5.4 The standard 80 names used by YOLO COCO weights

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
and `toothbrush`.

“COCO 80” refers to the common model label list; the underlying COCO dataset's
category identifiers are not a simple contiguous 0-to-79 sequence.

## 6. Model and runtime decisions

### 6.1 Detector: YOLO11 first, YOLOv8 fallback

The linked plan proposes replacing the existing YOLO11n with YOLOv8 without a
measured reason. That creates unnecessary model and post-processing drift.

Revised order:

1. Export/profile YOLO11 detection on the exact iQOO runtime because the current
   backend, labels, tests, and target memory already use YOLO11n semantics.
2. Use YOLOv8 detection only if its phone export is materially more reliable or
   faster on the actual loaner.
3. Use YOLOX only if both fail and an official sample proves it end to end.

Qualcomm AI Hub shows a [YOLO11 Detection NPU profile](https://aihub.qualcomm.com/jobs/jpev1d9v5)
on a Snapdragon 8 Elite Gen 5 reference device. That is feasibility evidence,
not an iQOO 15 application benchmark. Only measurements on the loaner justify
the final FPS, latency, memory, and NPU claims.

### 6.2 Segmentation: preserve semantics or omit it

The current backend uses SegFormer-B0 ADE20K because it exposes indoor semantics
such as floor, wall, door, stairs, and furniture. Replacing it with an arbitrary
segmentation model can silently remove those capabilities.

The exact phone gate is:

- compile the ADE20K checkpoint for the selected phone runtime;
- prove output-label ordering and preprocessing;
- verify camera-to-mask transforms;
- measure sustained NPU execution; and
- replay the existing indoor semantic tests.

Qualcomm's [SegFormer-B0 ADE20K page](https://aihub.qualcomm.com/iot/models/segformer_base)
currently documents the correct 150-class checkpoint but does not by itself
prove support on the iQOO mobile target. A Cityscapes substitute is not an
acceptable equivalent: road-oriented classes do not provide the indoor floor,
wall, door, and stairs semantics DRISHTI expects.

If this gate fails, ship detector-based corridor reasoning with explicit
uncertainty. Do not advertise wall/stairs semantics that the deployed model
cannot produce.

### 6.3 Tracking and risk: deterministic Kotlin, behavior-compatible

Port the current IoU/centroid tracking and risk behavior to Kotlin. Do not
replace the existing score with `max(confidence, proximity, ...)` or a generic
“nearest object” rule.

The current score is a configurable weighted combination:

- path overlap: 0.30;
- relative proximity: 0.25;
- approach rate: 0.20;
- class severity: 0.15; and
- detector confidence: 0.10.

The action stage then applies explicit critical approaching-vehicle, wall/dead
end, stairs, all-corridors-blocked, directional-evidence, and uncertainty rules.
Critical paths bypass ordinary persistence/cooldown behavior.

Create shared golden JSON vectors from the Python implementation and require the
Kotlin port to produce the same decision, reason code, corridor, and override
for each vector. Threshold changes are tuning changes and must be measured,
versioned, and compared—not silently rewritten during the port.

### 6.4 Ask -> Lock -> Guide

Use the phone detector memory first. A successful match initializes an on-device
tracker immediately and avoids VLM latency entirely. Guidance states remain
`IDLE`, `SEEKING`, `GUIDING`, `ARRIVED`, and `LOST` to match the current accepted
redesign rather than reviving older `LOCATING`/`LOCKED_TRACKING` names.

Use spatial audio from the target's normalized horizontal coordinate. Target
audio is muted whenever the risk action is not `CLEAR`; critical speech uses
audio-focus/queue interruption. Target loss produces a stop-and-rescan prompt
only when no higher-priority safety prompt is active.

### 6.5 Phone VLM: optional proof, never a prerequisite

Qwen3-VL-2B is not a safe headline dependency until the exact package, operators,
quantization, memory use, and NPU execution are proved on the loaner. Qualcomm's
[current Qwen3-VL-2B-Instruct page](https://aihub.qualcomm.com/models/qwen3_vl_2b_instruct)
does not establish support for this exact retail phone configuration.

Required gate:

- explicit one-shot invocation only;
- bounded input resolution;
- no concurrent camera-frame backlog;
- measured load, first-answer, repeated-answer, peak-memory, and thermal data;
- verified accelerator rather than silent CPU fallback;
- deterministic cancellation/timeout behavior; and
- safety loop remains responsive or is explicitly paused while locating.

If the gate fails, retain the existing laptop Moondream2 FastAPI locator as the
last-resort option. Its response must preserve `confidence: null` when the model
does not provide calibrated confidence. Never fabricate `0.85` for presentation.

## 7. Technical faults in the linked architecture and their revisions

| Fault | Why it fails technically | Required revision |
|---|---|---|
| Office Kit treated as a structured app-data bridge | Its documented features are user-facing mirror/input/clipboard/file transfer, not an inference API. | Use HTTP/WebSocket only for DRISHTI telemetry; use Office Kit for development and device operation. |
| Remote PC assumed without a device gate | The workflow fails if the event phone firmware lacks the feature or the saved-PC session is unstable. | Verify Remote PC on the exact iQOO/OriginOS build before making it the primary loop. |
| ADB assumed to coexist with Remote PC | APK replacement, USB mode changes, wireless-debugging ports, or app focus can break the control/validation loop. | Prove `adb devices`, `adb install -r`, and filtered logcat while Remote PC remains usable. |
| Remote PC confused with product transport | Remote desktop pixels and input are not typed inference or telemetry contracts. | Use Remote PC for development only; DRISHTI runtime data stays on HTTP/WebSocket. |
| Immediate switch from YOLO11 to YOLOv8 | It discards current model behavior and test compatibility before measuring the phone. | Attempt YOLO11 NPU export first; fallback only on evidence. |
| “COCO classes” treated as one undifferentiated set | The current system intentionally separates full target memory from audited risk classes. | Preserve two views from one inference. |
| Risk allowlist implies door detection | Stock COCO YOLO has no `door`. | Obtain door from verified segmentation/custom detector or mark unavailable. |
| Segmentation model described only as “NPU segmentation” | Model choice determines whether floor/wall/stairs semantics exist. | Gate the exact ADE20K model; omit the capability if unsupported. |
| Parallel pipeline diagram conflicts with single-NPU reality | Concurrent detector/segmenter/VLM submissions can serialize unpredictably or exhaust shared memory. | One accelerator scheduler with safety priority; VLM explicit and one-shot. |
| Raw model profile treated as application FPS | Preprocess, tensor copies, postprocess, camera conversion, audio, and thermal throttling are excluded. | Measure camera-to-guidance and sustained FPS on the iQOO. |
| NPU proof inferred from speed | Fast execution does not prove the selected backend. | Capture runtime/profiler evidence from the supported Qualcomm toolchain. |
| Existing risk logic rewritten during platform port | Changes both platform and safety behavior simultaneously, making failures hard to isolate. | Port with golden vectors first; tune only after parity. |
| Target guidance depends on VLM | It makes common COCO targets pay unnecessary latency/memory cost. | Full-COCO TTL memory first, conventional tracker second, VLM only on a miss. |
| Arbitrary VLM confidence returned as a number | Some locator models provide a box without calibrated probability. | Use nullable confidence and test the box itself. |
| VLM “unload” assumed to be immediate | Native runtimes may retain arenas/context memory after object deletion. | Use explicit close APIs; if necessary isolate optional VLM in a killable service/process and measure reclaimed memory. |
| Laptop removed completely | The retained dashboard and SQLite still need a local data owner. | Keep a minimal coordinator, outside the walking critical path. |
| Contract change limited to swapping `CUDA` for `NPU` | Execution location, timing ownership, model states, and telemetry direction also change. | Propose a versioned contract amendment and retain compatibility adapters during migration. |
| Rebuild Kotlin from zero | Existing camera, transforms, pacing, feedback, and tests would be thrown away. | Refine existing Kotlin and replace the network inference seam with a phone inference interface. |
| Dashboard receives live walking frames | Adds bandwidth and couples monitoring to the safety loop. | Send bounded structured telemetry and optional confirmed evidence only. |

## 8. Minimal laptop backend after rephasing

Keep the current FastAPI repository intact until the phone path passes its gates.
Then narrow its hackathon runtime responsibilities to:

- `/api/v1/health` for coordinator/database/dashboard health;
- ingestion of structured phone telemetry and model/runtime measurements;
- hazard report CRUD, recurrence, accessibility scoring, SQLite, CSV/JSON export;
- the existing dashboard's REST and bounded live telemetry feed; and
- optional `/api/v1/vlm/locate` for an explicit snapshot only when phone VLM is
  unavailable.

The laptop backend should not receive every CameraX frame. If VLM fallback is
enabled, advertise it as **laptop-assisted target localization**, not as part of
the on-device safety claim. The phone keeps the target tracker and all subsequent
high-rate guidance after the returned box is handed off.

## 9. Contract revisions requiring approval

Before code changes, propose and review these wire changes:

1. Add `NPU` to compute-device reporting while retaining `CUDA`, `CPU`, and
   `NONE` for the coordinator/fallback.
2. Add an execution owner (`PHONE` or `LAPTOP`) to model status so the dashboard
   does not imply that phone inference consumes laptop VRAM.
3. Define a phone-to-coordinator telemetry envelope containing frame ID/time,
   phone-measured stage timings, detector/segmenter state, guidance action,
   target state, and safety override—without image bytes.
4. Keep normalized box/point coordinates and current target state names.
5. Make locator confidence nullable.
6. Preserve a compatibility adapter for the existing dashboard while it learns
   phone-owned telemetry.
7. Preserve `http://10.111.36.200:8000` as the fixed coordinator URL for the
   retained dashboard/optional fallback. It remains configuration for those
   features, not a dependency of Walk Mode.

No accepted contract should be changed until these proposals are recorded in
`docs/DECISIONS.md` and corresponding Python, TypeScript, and Kotlin tests exist.

## 10. Corrected build plan

This replaces schedule-based planning with dependency gates. Stop at the first
failed gate and use the stated fallback.

### R0 — Freeze behavior and evidence

- Tag or branch the known-working laptop implementation.
- Export golden detector canonicalization, spatial, risk, safety-preemption, and
  target-state vectors from existing tests.
- Record current dashboard contracts and Kotlin baseline tests.

**Gate:** Existing Python, dashboard, and Kotlin unit tests pass; golden fixtures
are committed; no accepted behavior is ambiguous.

### R1 — Prove the phone inference runtime

- Run the smallest official Qualcomm object-detection sample on the loaner.
- Confirm camera tensor format, quantization, output layout, and accelerator.
- Profile one YOLO11 candidate and, only if needed, YOLOv8.

**Gate:** Repeated NPU execution is verified on the physical device and measured
camera-to-box latency is acceptable. Otherwise use the best proven official
detector sample and reduce model scope.

### R2 — Replace network inference with a Kotlin interface

- Introduce an `OnDeviceDetector` boundary under the current CameraX pipeline.
- Keep latest-frame-wins and freshness rejection.
- Produce the two detection views from one inference.
- Render aligned boxes using the existing preview transform.

**Gate:** Ten repeated controlled frames produce aligned, fresh detections with
no unbounded queue and no FastAPI dependency.

### R3 — Port deterministic safety behavior

- Port tracking, normalized corridor geometry, relative proximity, scoring,
  critical overrides, persistence, hysteresis, and uncertainty.
- Run Python/Kotlin golden vectors against identical inputs.

**Gate:** Cross-language parity holds for all golden success and failure cases;
an actionable risk always preempts target guidance.

### R4 — Finish phone-owned accessible output

- Use the existing speech/audio-focus components.
- Refine `SpatialAudioEngine` and `SonarMapping` for left/centre/right and target
  panning.
- Remove haptic output from the demo acceptance path.

**Gate:** Visible guidance, spoken action, and spatial audio agree; a critical
warning interrupts target audio immediately.

### R5 — Add segmentation only after proof

- Attempt the exact ADE20K semantic model on the selected runtime.
- Validate class mapping, mask transform, and sustained resource use.

**Gate:** Existing indoor floor/wall/stairs fixtures pass on phone. If not, omit
segmentation and report detector-only uncertainty honestly.

### R6 — Port Ask -> Lock -> Guide

- Populate bounded full-COCO target memory from the same detector pass.
- Resolve eligible requests from memory and initialize the on-device tracker.
- Drive target state and spatial audio from live phone frames.

**Gate:** Detector-memory target lock, tracking, loss, rescan, and safety
preemption all pass without a VLM.

### R7 — Connect the minimal coordinator and existing dashboard

- Send structured phone telemetry and confirmed hazard events only.
- Adapt health/model panels to distinguish phone NPU from laptop CUDA.
- Preserve SQLite and exports.

**Gate:** Dashboard loss or coordinator failure cannot affect phone guidance;
reconnection does not replay stale safety instructions.

### R8 — Optional locator fallback

- Attempt a phone VLM only if every earlier gate is stable.
- If it fails, enable the existing laptop Moondream2 snapshot locator.
- Hand only the returned normalized box/label to the phone tracker.

**Gate:** Bounded memory, timeout, cancellation, target handoff, and safety
preemption pass; no continuous VLM invocation exists.

## 11. Final technical recommendation

Use Office Kit throughout both light modes for genuine phone operation and a
strong HackTracker activity record, except when a labelled clean benchmark needs
it paused. Office Kit Remote PC is the recommended remote development control
plane: the iQOO operates Codex/Claude Code, Gradle, ADB, and logcat on the laptop,
then Office Kit screen mirroring validates the resulting APK on the iQOO.

Keep one authoritative laptop checkout and make every installed APK traceable to
its commit. Phone-browser cloud coding tasks remain a secondary fallback, while
device builds, runtime integration, NPU verification, and final proof stay on the
physical iQOO. Neither Remote PC nor a cloud agent is part of DRISHTI's product
runtime or a substitute for on-device AI evidence.

The most defensible implementation is not a total rewrite. It is a controlled
migration of the current tested safety behavior into the existing Kotlin client,
with one phone detector invocation feeding both an audited risk view and a full
COCO target-memory view. The existing dashboard remains useful, and FastAPI is
reduced to a coordinator plus an explicit laptop VLM fallback rather than the
owner of every walking frame.

## 12. Sources

- [iQOO hackathon rules, scoring, Red/Green format, and Office Kit usage](https://iqoo.reskilll.com/)
- [vivo Office Kit capabilities](https://pc.vivo.com/)
- [OpenAI Codex cloud](https://developers.openai.com/codex/cloud)
- [OpenAI Codex CLI](https://developers.openai.com/codex/cli)
- [Anthropic Claude Code setup requirements](https://docs.anthropic.com/en/docs/claude-code/getting-started)
- [Qualcomm AI Hub YOLO11 Detection profile](https://aihub.qualcomm.com/jobs/jpev1d9v5)
- [Qualcomm AI Hub SegFormer-B0 ADE20K](https://aihub.qualcomm.com/iot/models/segformer_base)
- [Qualcomm AI Hub Qwen3-VL-2B-Instruct](https://aihub.qualcomm.com/models/qwen3_vl_2b_instruct)
