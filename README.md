# DRISHTI Edge

On-device walking guidance for blind and low-vision users.

The camera reads the ground ahead. The user hears what matters — an obstacle
closing in, a level change or a wall ahead, which side the ground is open.

The guidance loop runs entirely on the phone. No cloud, no server, and no network
call between the camera and the user's ear. A laptop coordinator carries the
optional live Monitor feed; losing it costs monitoring and nothing else.

---

## Contents

| Path | What |
|---|---|
| `apps/android/` | Kotlin + Jetpack Compose client |
| [`apps/dashboard-android/`](apps/dashboard-android/README.md) | DRISHTI Monitor — the Android app an NGO desk watches the programme from |
| [`apps/coordinator/`](apps/coordinator/README.md) | Local-LAN FastAPI service for the single live Monitor participant |
| `apps/dashboard/` | React + Vite coordinator dashboard. Being retired in favour of the Android one. |
| `packages/contracts/` | TypeScript API contracts |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Perception and guidance pipeline specification |
| [`BUILD_PLAN.md`](BUILD_PLAN.md) | Execution plan — laptop preparation, agent task cards, device bring-up, demo runbook |
| [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md) | Safety contract |
| [`docs/DEVICE_BUDGET.md`](docs/DEVICE_BUDGET.md) | Memory budget |
| [`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md) | Scene Mode vision-language model — selection, tuning, and the measured account of the one bug that blocked it |
| `scripts/bootstrap_llama.sh` | Fetches the pinned llama.cpp revision Scene Mode builds against |
| `tools/` | Offline model and vector tooling — golden-vector export, SegFormer float-IO surgery |

---

## Capabilities

**Walk** — continuous guidance from the camera. One detector invocation per
frame; tracking, corridor geometry and a weighted risk engine resolve each frame
into one of six guidance actions, delivered as speech and spatial audio.

**Find** — session-scoped landmark memory. The user asks for something DRISHTI
has seen during the walk and is guided to it, turn by turn. Answered from the
same detector pass the walk loop already ran, so it costs no extra inference and
no network call. A target the detector cannot name is refused out loud rather
than guessed at. Nothing is retained after the session ends.

**Read** — bundled on-device ML Kit OCR for signs, boards and route numbers.
English uses the Latin recognizer; Hindi uses the Latin + Devanagari recognizer.
It is one-shot, works without a connection, and runs on CPU rather than the NPU.

**Ask** — an English or Hindi spoken question about the scene ahead, answered on
the phone by a vision-language model in **1.3 – 1.8 s**. On-demand only, never
in the walking loop: the model is loaded for exactly one question and freed
before the answer is spoken. The shipping 450M model does not reliably
understand Tamil questions, so Tamil mode refuses Scene explicitly instead of
speaking a plausible but unrelated answer.

**Diagnostics** — a two-finger swipe down shows which accelerator actually ran
the last frame, its millisecond cost, rolling FPS, thermal status and free RAM,
and can run the same detector on the CPU for comparison.

**Monitor** — a second Android app, [`apps/dashboard-android/`](apps/dashboard-android/README.md),
for the NGO desk rather than the walker: who is out and how they are, who has
asked for help and how to reach them, each person's safety events, and the
street hazards that more than one person has walked into. One access-key-protected
participant is live and pinned first: Call uses the configured real number,
location comes from foreground-only GPS, and obstacle events come from the
on-device safety verdict. The other people and all aggregate hazards remain
explicit sample data; the badge says **1 live + sample**.

---

## Architecture

```
camera
   │
   ▼
one detector invocation (NPU)
   │
   ├── full COCO view ──────► landmark memory ──► Find
   │
   └── audited 19-class view
           │
           ▼
   tracking · corridor geometry · spatial reasoning   ◄── segmentation (NPU)
           │
           ▼
   weighted risk engine
           │
           ▼
   guidance state machine
           │
           ▼
   speech · spatial audio · overlay
           │
           └──► bounded telemetry ──► laptop coordinator ──► dashboard
                (optional, fire-and-forget, never in the loop)
```

One inference produces two views: an audited allowlist that feeds safety, and the
full native COCO output that feeds landmark memory. A label says an object is
present; it does not establish that the object obstructs the walking corridor, so
the safety path narrows deliberately while target memory stays wide.

Resident models run continuously. On-demand models check free memory, load, run
once, unload, and only then return — so no two are ever in memory together.

| Stage | Model | Residency |
|---|---|---|
| Detection | YOLO11, Hexagon NPU | Resident |
| Surface segmentation | SegFormer-B0 ADE20K | Resident, if its gate passes |
| Tracking, spatial, risk, guidance | Kotlin | Always |
| OCR | Bundled ML Kit text recognition, CPU | On demand |
| Target locating and guidance | Kotlin, from landmark memory | Always |
| Scene questions | LFM2.5-VL-450M, llama.cpp, CPU | On demand |

The walking loop above **runs on the phone today** — detection, segmentation,
tracking, risk and guidance all on-device, with no backend and no network in
the path. Both YOLO11n and SegFormer now run on guarded QNN HTP sessions, with
CPU fallback disabled. A warmed live frame on the 12 GB iQOO measured **57.96
ms total**; the standalone probes measured **3.30 ms** for YOLO and **11.33
ms** for SegFormer (`BUILD_PLAN.md` §3.5).

Explore Mode is also local now: its former JPEG upload/retry path has been
deleted. A device instrumented test on the 12 GB iQOO reads a generated
`BUS 42A` sign and extracts route `42A`; walking safety inference remains active
during the one-shot read. Scene and OCR stills are centre-cropped to the same
`FILL_CENTER` viewport shown on screen, so both features analyse what the user
actually aimed at rather than off-screen sensor pixels.

The main app's spoken-language setting is selectable and persistent. English,
Hindi and Tamil cover static guidance, prompts, Find cues and OCR qualification;
Hindi additionally selects the bundled Devanagari OCR model. Scene answers in
English and Hindi. Tamil Scene is deliberately unavailable for the measured
model limitation above, and Tamil-script OCR is not claimed; OCR text is read
verbatim rather than translated.

**Find and Ask are local too.** Target locating resolves from landmark memory
and the live detector view with no model call of its own, and Scene questions
run LFM2.5-VL-450M through llama.cpp on the CPU — measured at 1.3 – 1.8 s per
answer on device, with the model freed before the call returns. The `/vlm/*`,
`/walk/analyze` and `/explore` endpoints have been **deleted from the client**,
not disabled: there is no longer a code path from a user gesture to the
network.

The general Scene question is grounded for the small model: it checks for a
visible person first, reports only clearly visible objects/text, and says when
it is uncertain. A real-image device regression covers three people scenes,
including a dark off-centre subject, in addition to the sign and cancellation
tests.

Memory is budgeted for the **12 GB** device variant and has now been exercised
on both 12 GB and 16 GB iQOO 15 units. Every model choice has a
gate and a fallback in
[`ARCHITECTURE.md` §6](ARCHITECTURE.md#6-model-selection-and-gates); a gate that
fails removes a capability rather than downgrading it silently.

---

## Safety contract

DRISHTI is an assistive prototype. It does not replace a white cane, a guide dog,
mobility training, or human judgement.

- Never states or implies that a road or crossing is safe.
- Never states distance in absolute units. Relative bands only: `FAR`, `MEDIUM`,
  `NEAR`, `IMMEDIATE`, `UNKNOWN`.
- Emits `PAUSE_UNCLEAR` when evidence is weak or contradictory, rather than
  inventing a direction. Uncertainty and danger are different answers.
- Never advertises a capability the deployed models cannot produce.
- Every state carries a spoken word, an icon shape, and a distinct spatial-audio
  character. Colour is never the only signal.
- No frame storage, no facial recognition, no identity tracking, no route
  history. Evidence images leave the device only after an explicit per-report
  consent gesture.
- Monitor location is sampled only while Walk Mode's foreground service is
  active. The optional feed contains coordinates, battery, activity and safety
  facts—not camera frames—and requires a shared local access key.
- Continuous safety never depends on the laptop, a vision-language model, or a
  network round trip.

Full contract: [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md).

---

## Build

### Monitor (Android)

```bash
cd apps/dashboard-android && ./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest    # 28 tests
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

A separate Gradle build from `apps/android`, so a coordinator's laptop never
needs the NDK or the llama.cpp tree to build the thing they actually use.
Details in [`apps/dashboard-android/README.md`](apps/dashboard-android/README.md).

### Dashboard (web, being retired)

```bash
npm install
npm run dev --workspace apps/dashboard
```

Serves on `http://127.0.0.1:5173`.

```bash
npm run build       # production bundle
npm test            # vitest
npm run typecheck
```

### Android client

Scene Mode builds llama.cpp from a pinned revision, which is fetched rather
than vendored. The bootstrap also stages llama.cpp's pinned KleidiAI 1.24.0
release after verifying its archive digest, avoiding a hidden download during
CMake configuration:

```bash
./scripts/bootstrap_llama.sh
```

```bash
cd apps/android
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The build works without that step — it logs that Scene Mode is being skipped
and leaves the rest of the app intact.

To install, prefer `adb install` over `./gradlew installDebug`: Gradle
uninstalls first, and **that wipes the app's external files directory**, taking
the staged model files with it.

```bash
adb install -r -t -d apps/android/app/build/outputs/apk/debug/app-debug.apk
```

Models load from that external files directory, so swapping one needs no
rebuild. Note the `.debug` suffix on debug builds:

```bash
adb push models/staging/yolo11n_qdq.onnx models/staging/yolo11n_fp32_nchw.onnx models/staging/segformer_float.onnx models/staging/ade20k_config.json /sdcard/Android/data/com.drishti.app.debug/files/
```

minSdk 31, target/compile 36. Kotlin 2.3, AGP 9, Gradle 9.1. The native build
is forced to `Release` in every variant — the debug default compiles ggml's C
kernels at `-O0`, which is slow enough to look like a hang
([`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md) §8).

---

## Contracts

`packages/contracts/` is the wire format. The pipeline produces these shapes; the
dashboard and the Android client are written against them and neither is being
rebuilt.

```typescript
type RiskLevel     = "CLEAR" | "WATCH" | "WARN" | "HIGH" | "CRITICAL";
type ProximityBand = "FAR" | "MEDIUM" | "NEAR" | "IMMEDIATE" | "UNKNOWN";
type SurfaceKind   = "WALKABLE" | "ROAD" | "NON_WALKABLE" | "UNKNOWN";

interface GuidanceContract {
  level: RiskLevel;
  action: "CLEAR" | "CAUTION" | "MOVE_LEFT" | "MOVE_RIGHT" | "STOP" | "PAUSE_UNCLEAR";
  speech: string;
  haptic_pattern: "NONE" | "CAUTION_SHORT" | "WARNING_DOUBLE" | "CRITICAL_RAPID" | "UNCLEAR_LONG";
  speak: boolean;
  reason_code: string;
}
```

Moving execution to the phone requires amendments — an `NPU` compute device, an
execution owner so the dashboard never reports phone inference as laptop VRAM, a
telemetry envelope carrying no image bytes, and a nullable locator confidence.
They are **proposals** until recorded with tests in Python, TypeScript, and
Kotlin:
[`ARCHITECTURE.md` Appendix A](ARCHITECTURE.md#appendix-a--contract-types-and-proposed-amendments).

---

## Licence

Not yet licensed. All rights reserved pending a decision.
