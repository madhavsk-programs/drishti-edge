# DRISHTI Edge

Assistive walking guidance for blind and low-vision users, being ported from a
laptop GPU onto a Snapdragon Hexagon NPU.

The camera reads the space ahead — corridors, doorways, stairs, walkable floor.
The user hears what matters: an obstacle closing in, a wall or level change
ahead, which way the floor is open. Haptics carry the urgent cues.

---

## Contents

| Path | What |
|---|---|
| `apps/android/` | Kotlin + Jetpack Compose client |
| `apps/dashboard/` | React + Vite coordinator dashboard |
| `packages/contracts/` | Frozen TypeScript API contracts |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | On-device rebuild specification (1,269 lines) |
| [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md) | Safety contract |
| [`docs/DEVICE_BUDGET.md`](docs/DEVICE_BUDGET.md) | 12 GB memory budget |

**The perception and guidance pipeline is not in this repository.** It currently
runs as a CUDA service in the parent project
([SparkleYR/DRISHTI](https://github.com/SparkleYR/DRISHTI)) and is being rebuilt
on-device. `ARCHITECTURE.md` is the specification for that rebuild; the
implementation will live in its own repository.

Everything committed here predates that rebuild.

---

## Architecture

Current, and the reason for the port:

```
phone: capture, JPEG encode, POST          ← requires the laptop on the same Wi-Fi
   │
   │  every frame over the LAN
   ▼
laptop: decode, detect, segment, track, reason, score, guide
   │
   │  JSON response
   ▼
phone: speak, vibrate, draw overlay
```

Target:

```
phone: capture → NPU detection + segmentation → risk → guidance → speech, haptics
laptop: coordinator dashboard only, optional, fire-and-forget
```

### Model port

| Stage | Current (laptop CUDA) | Target (Hexagon NPU) |
|---|---|---|
| Detection | YOLO11n | YOLOv8-det / YOLOX |
| Surface segmentation | SegFormer-B0 ADE20K | AI Hub segmentation |
| OCR | Tesseract 5 (CPU) | PaddleOCR / on-device OCR |
| Scene VLM | Moondream2 | Qwen3-VL-2B-Instruct |
| Risk + guidance | Python | Kotlin, on-device |

Every stage has a fallback ladder in
[`ARCHITECTURE.md` §6](ARCHITECTURE.md#6-model-selection-and-fallback-ladders).
Memory is budgeted for the **12 GB** device variant; nothing on the critical path
assumes 16 GB.

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

Serves on `http://127.0.0.1:5173`. Expects a backend on `http://127.0.0.1:8000`;
without one it reports the system unreachable rather than fabricating data.

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

Backend address is set in the app's settings screen and persisted. The default is
in `apps/android/app/src/main/java/com/drishti/app/settings/SettingsStore.kt`.

> This client currently requires the laptop backend. Removing that dependency is
> the work specified in `ARCHITECTURE.md`.

---

## Contracts

`packages/contracts/` is the frozen wire format. The rebuilt pipeline must
produce these shapes; the dashboard and the Android client are written against
them.

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

One change the port requires: `ComputeDevice` becomes
`"NPU" | "GPU" | "CPU" | "NONE"`. Everything else is frozen.

---

## Licence

Not yet licensed. All rights reserved pending a decision.
