# DRISHTI Edge

On-device walking guidance for blind and low-vision users.

The camera reads the ground ahead. The user hears what matters — an obstacle
closing in, a level change or a wall ahead, which side the ground is open.
Haptics carry the urgent cues when speech is too slow.

Everything runs on the phone. No cloud, no server, no network call in the
guidance loop.

---

## Contents

| Path | What |
|---|---|
| `apps/android/` | Kotlin + Jetpack Compose client |
| `apps/dashboard/` | React + Vite coordinator dashboard |
| `packages/contracts/` | Frozen TypeScript API contracts |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | Perception and guidance pipeline specification |
| [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md) | Safety contract |
| [`docs/DEVICE_BUDGET.md`](docs/DEVICE_BUDGET.md) | 12 GB memory budget |

The perception and guidance pipeline is specified in
[`ARCHITECTURE.md`](ARCHITECTURE.md) and implemented separately.

---

## Capabilities

**Walk** — continuous guidance from the camera. Detection and segmentation run
on every frame; tracking, corridor geometry and a risk engine resolve each frame
into one of six guidance actions, delivered as speech, haptics and spatial audio.

**Ask** — a spoken question about the scene ahead, answered by an on-device
vision-language model.

**Read** — on-demand OCR for signs, boards and route numbers.

**Find** — session-scoped landmark memory. The user asks for something DRISHTI
has seen during the walk and is guided to it with clock-face directions. Nothing
is retained after the session ends.

---

## Architecture

```
camera
   │
   ├── detection (NPU) ── every frame
   ├── segmentation (NPU) ── every Nth frame
   │
   ▼
tracking · corridor geometry · spatial reasoning
   │
   ▼
risk engine
   │
   ▼
guidance state machine
   │
   ▼
speech · haptics · spatial audio · overlay
```

Resident models run continuously. On-demand models load, run once, and unload
before returning, so no two are in memory at the same time.

| Stage | Model | Residency |
|---|---|---|
| Detection | YOLOv8-det / YOLOX | Resident |
| Surface segmentation | AI Hub segmentation | Resident |
| OCR | PaddleOCR / on-device OCR | On demand |
| Scene questions | Qwen3-VL-2B-Instruct | On demand |
| Risk + guidance | Kotlin | Always |

Memory is budgeted for the **12 GB** device variant. Every stage has a fallback
ladder in [`ARCHITECTURE.md` §6](ARCHITECTURE.md#6-model-selection-and-fallback-ladders).

---

## Safety contract

DRISHTI is an assistive prototype. It does not replace a white cane, a guide dog,
mobility training, or human judgement.

- Never states or implies that a road or crossing is safe.
- Never states distance in absolute units. Relative bands only: `FAR`, `MEDIUM`,
  `NEAR`, `IMMEDIATE`, `UNKNOWN`.
- Emits `PAUSE_UNCLEAR` when evidence is weak or contradictory, rather than
  inventing a direction.
- Every state carries a word, an icon shape, and a haptic pattern. Colour is
  never the only signal.
- No frame storage, no facial recognition, no identity tracking, no route
  history. Evidence images leave the device only after an explicit per-report
  consent gesture.

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

The client requires the pipeline specified in
[`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Contracts

`packages/contracts/` is the frozen wire format. The pipeline produces these
shapes; the dashboard and the Android client are written against them.

```typescript
type RiskLevel     = "CLEAR" | "WATCH" | "WARN" | "HIGH" | "CRITICAL";
type ProximityBand = "FAR" | "MEDIUM" | "NEAR" | "IMMEDIATE" | "UNKNOWN";
type SurfaceKind   = "WALKABLE" | "ROAD" | "NON_WALKABLE" | "UNKNOWN";
type ComputeDevice = "NPU" | "GPU" | "CPU" | "NONE";

interface GuidanceContract {
  level: RiskLevel;
  action: "CLEAR" | "CAUTION" | "MOVE_LEFT" | "MOVE_RIGHT" | "STOP" | "PAUSE_UNCLEAR";
  speech: string;
  haptic_pattern: "NONE" | "CAUTION_SHORT" | "WARNING_DOUBLE" | "CRITICAL_RAPID" | "UNCLEAR_LONG";
  speak: boolean;
  reason_code: string;
}
```

---

## Licence

Not yet licensed. All rights reserved pending a decision.
