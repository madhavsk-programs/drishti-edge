# DRISHTI Edge

On-device walking guidance for blind and low-vision users.

The camera reads the ground ahead. The user hears what matters — an obstacle
closing in, a level change or a wall ahead, which side the ground is open.

The guidance loop runs entirely on the phone. No cloud, no server, and no network
call between the camera and the user's ear. A laptop coordinator keeps the
dashboard and the hazard database; losing it costs the dashboard and nothing
else.

---

## Contents

| Path | What |
|---|---|
| `apps/android/` | Kotlin + Jetpack Compose client |
| `apps/dashboard/` | React + Vite coordinator dashboard |
| `packages/contracts/` | TypeScript API contracts |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Perception and guidance pipeline specification |
| [`BUILD_PLAN.md`](BUILD_PLAN.md) | Execution plan — laptop preparation, agent task cards, device bring-up, demo runbook |
| [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md) | Safety contract |
| [`docs/DEVICE_BUDGET.md`](docs/DEVICE_BUDGET.md) | Memory budget |
| [`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md) | Scene Mode vision-language model options |
| `tools/` | Offline model and vector tooling — golden-vector export, SegFormer float-IO surgery |

---

## Capabilities

**Walk** — continuous guidance from the camera. One detector invocation per
frame; tracking, corridor geometry and a weighted risk engine resolve each frame
into one of six guidance actions, delivered as speech and spatial audio.

**Find** — session-scoped landmark memory. The user asks for something DRISHTI
has seen during the walk and is guided to it. Answered from the same detector
pass the walk loop already ran, so the common case costs no extra inference.
Nothing is retained after the session ends.

**Read** — on-demand OCR for signs, boards and route numbers.

**Ask** — a spoken question about the scene ahead. On-demand only, never in the
walking loop.

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
   tracking · corridor geometry · spatial reasoning   ◄── segmentation (QNN + CPU today)
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
| OCR | On-device OCR | On demand |
| Scene questions and target locating | Optional, gated | On demand |

The walking loop above **runs on the phone today** — detection, segmentation,
tracking, risk and guidance all on-device, with no backend and no network in
the path. YOLO11n now runs on guarded QNN HTP (**3.30 ms measured**, CPU
fallback disabled). SegFormer is on-device and faster through QNN, but its
guard proves that some nodes still fall back to CPU; it is not represented as
fully NPU (`BUILD_PLAN.md` §3.5).

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
- Continuous safety never depends on the laptop, a vision-language model, or a
  network round trip.

Full contract: [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md).

---

## Build

### Dashboard

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

```bash
cd apps/android
./gradlew installDebug
```

minSdk 31, target/compile 36. Kotlin 2.3, AGP 9, Gradle 9.1.

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
