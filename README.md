# DRISHTI Edge

**A walking assistant for blind and low-vision users — being moved onto the phone.**

The camera watches the path ahead. The user hears what matters: an obstacle
closing in, a wall or stairs ahead, which way the floor is actually open. Haptics
carry the urgent cues when speech is too slow.

---

## Read this first — what this repository is

This repository holds the two surfaces that carry into the iQOO Hackathon Chennai
build, plus the specification for the part being rebuilt there.

**It is not a submission of finished hackathon work, and nothing here is claimed
as event-window code.** It is prior work, published openly so that what we bring
to the event and what we build at the event are separable by anyone who looks.

| What | Where | Status |
|---|---|---|
| Coordinator dashboard (React) | `apps/dashboard/` | **Prior work.** Built before the event. |
| Android client (Kotlin, Compose) | `apps/android/` | **Prior work.** Built before the event. |
| Typed API contracts | `packages/contracts/` | **Prior work.** Frozen spec. |
| On-device rebuild architecture | [`ARCHITECTURE.md`](ARCHITECTURE.md) | **Prior work.** A plan, not an implementation. |
| The on-device perception pipeline | *(not here)* | **To be written at the event.** |

The perception and guidance stack is deliberately **absent** from this repository.
That is the work being done in the 30-hour window, and it will live in its own
repository with commit history inside the event window.

---

## The problem we are bringing to Chennai

DRISHTI works. Over ten phases we built and proved the full pipeline: object
detection, semantic segmentation of walkable surface, session-scoped tracking,
corridor geometry, a risk and guidance engine, OCR for signs, and a
vision-language model for scene questions — driving a native Kotlin client with
speech, haptics and spatial audio.

It has exactly one thing wrong with it.

**All of that runs on a laptop GPU, and the phone must stay within Wi-Fi range of
it.** Every frame is JPEG-encoded, sent over the LAN to an RTX 4060, and the
guidance comes back over the same link.

For someone actually walking outside, that is useless. You cannot carry a gaming
laptop. You cannot wait on a network round-trip to be told to stop. And you should
not be streaming a live camera feed of everything you see to anything at all.

```
TODAY                                   TARGET
-----                                   ------
phone: capture, encode, POST            phone: capture, infer, reason, speak
  |                                       |
  | JPEG over Wi-Fi, every frame          | (nothing)
  v                                       |
laptop: detect, segment, track,           v
        reason, score, guide            laptop: dashboard only, optional
  |
  | JSON back over Wi-Fi
  v
phone: speak, vibrate, draw
```

At Chennai we delete the left-hand column. Every model moves onto the iQOO 15's
Hexagon NPU. The demo is the proof: **we close the laptop mid-walk and the
guidance keeps coming.**

---

## The port

Every model DRISHTI runs today has a counterpart Qualcomm lists for Snapdragon 8
Elite Gen 5. We are not inventing a pipeline — we are re-hosting a proven one.

| Job | Runs today (laptop CUDA) | Chennai target (Hexagon NPU) |
|---|---|---|
| Obstacle detection | YOLO11n | YOLOv8-det / YOLOX |
| Walkable surface | SegFormer-B0 ADE20K | AI Hub segmentation |
| Sign reading | Tesseract 5 (CPU) | PaddleOCR / on-device OCR |
| Scene questions | Moondream2 | Qwen3-VL-2B-Instruct |
| Risk + guidance | Python backend | Kotlin, on-device |

Planned for the **12 GB** iQOO 15 variant. Nothing on the critical path assumes
16 GB. See [`ARCHITECTURE.md` §4](ARCHITECTURE.md#4-device-budget--12-gb-iqoo-15)
for the memory budget and [§6](ARCHITECTURE.md#6-model-selection-and-fallback-ladders)
for the fallback ladder behind every row above.

---

## Safety rules we do not break

DRISHTI is an **assistive prototype**. It does not replace a white cane, a guide
dog, mobility training, or human judgement.

- It never says a road or crossing is safe. It reports what it detects; it never
  certifies.
- It never states distance in metres. A single camera cannot measure it. Only
  relative bands: `FAR`, `MEDIUM`, `NEAR`, `IMMEDIATE`, `UNKNOWN`.
- When evidence is weak or contradictory it says so — `PAUSE_UNCLEAR` — instead of
  inventing a direction. **For a blind user, a confident wrong answer is the most
  dangerous possible output.**
- Colour is never the only signal. Every state also carries a word, an icon shape,
  and a haptic pattern.
- No frame storage, no facial recognition, no identity tracking, no route history.
  Evidence images leave the device only after an explicit per-report consent
  gesture.

The full set is [`ARCHITECTURE.md` §3](ARCHITECTURE.md#3-the-non-negotiable-safety-contract).

---

## What is in here

```
apps/
  android/        Kotlin + Jetpack Compose client
                  CameraX capture, capture-to-preview transform, stale-frame
                  rejection, tri-lingual TTS, haptics, spatial audio, gesture
                  input, screen-off foreground service
  dashboard/      React + Vite coordinator dashboard
                  system readiness, live walkers, route monitor, hazard queue
packages/
  contracts/      Frozen TypeScript API contracts — the shapes the rebuilt
                  pipeline must produce
docs/
  SAFETY_RULES.md         The safety contract, standalone
  DEVICE_BUDGET.md        12 GB memory arithmetic
ARCHITECTURE.md           1,200-line on-device rebuild specification
```

### Running the dashboard

```bash
npm install
npm run dev --workspace apps/dashboard
```

It expects a backend on `http://127.0.0.1:8000`. Without one it reports the
system as unreachable, which is the correct behaviour rather than fabricated data.

### Building the Android client

Open `apps/android/` in Android Studio, or:

```bash
cd apps/android && ./gradlew installDebug
```

Set the backend address in the app's settings screen. **Note:** this client
currently expects the laptop backend. Removing that dependency is the event work.

---

## Prior work and disclosure

Built by the team over ten phases before this hackathon:

- **80 numbered design decisions** with rationale, in the parent project's
  `DECISIONS.md`
- A frozen, typed `/api/v1` contract
- Real-model integration tests running with outbound HTTP denied
- Phase-gated acceptance criteria, including controlled physical hall testing

The parent project — including the FastAPI backend, the CUDA perception pipeline,
SQLite persistence, and the accessibility scoring engine — is at
[SparkleYR/DRISHTI](https://github.com/SparkleYR/DRISHTI).

We are stating this plainly because the hackathon rules require original work
written inside the event window, with pre-existing components disclosed. The
perception rebuild is that original work. Everything in this repository predates
the event and is labelled as such.

---

## Team

| | |
|---|---|
| **Madhav Khurana** | LLM systems, ML pipelines. [github.com/madhavsk-programs](https://github.com/madhavsk-programs) |
| **Yash Raj** | Android / Kotlin. [github.com/SparkleYR](https://github.com/SparkleYR) |

---

*DRISHTI is an assistive prototype. It is not a medical device, not a mobility
aid replacement, and not a safety certification. It never claims a crossing is
safe.*
