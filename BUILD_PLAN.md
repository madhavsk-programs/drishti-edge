# DRISHTI Edge — Build Plan

> **Companion to [`ARCHITECTURE.md`](ARCHITECTURE.md).** The architecture says
> *what* to build and *why*. This says *how*, *in what order*, *with which
> files*, and *what to type*.
>
> **Target:** iQOO 15, Snapdragon 8 Elite Gen 5 (Hexagon HTP **v81**), Android 16
> / OriginOS. RAM variant confirmed at handover — see §1.4.
> **Operator:** one person, driving coding agents on a Windows laptop.
> **Network:** full-speed internet throughout.
> **Repository:** `C:\drishti-edge` (specification + client + dashboard).
> The working Python implementation is at `C:\drishti-edge\entire-old-codebase`
> — untracked, local reference, and the source of every golden vector.

---

## Table of contents

**Part A — preparation**
1. [The fork: 12 GB vs 16 GB](#1-the-fork-12-gb-vs-16-gb)
2. [Laptop preparation](#2-laptop-preparation)
3. [Phone preparation](#3-phone-preparation)
4. [Model assets — what to fetch and from where](#4-model-assets--what-to-fetch-and-from-where)
5. [Runtime decision — LiteRT vs ONNX Runtime QNN](#5-runtime-decision--litert-vs-onnx-runtime-qnn)

**Part B — judgement**

6. [Are these models good enough for the demo?](#6-are-these-models-good-enough-for-the-demo)

**Part C — the build**

7. [How to run an agent task card](#7-how-to-run-an-agent-task-card)
8. [R0 — freeze behaviour, export golden vectors](#r0--freeze-behaviour-export-golden-vectors)
9. [R1 — prove the phone inference runtime](#r1--prove-the-phone-inference-runtime)
10. [R2 — replace the network inference seam](#r2--replace-the-network-inference-seam)
11. [R3 — port deterministic safety behaviour](#r3--port-deterministic-safety-behaviour)
12. [R4 — phone-owned accessible output](#r4--phone-owned-accessible-output)
13. [R5 — segmentation](#r5--segmentation)
14. [R6 — Ask, Lock, Guide](#r6--ask-lock-guide)
15. [R7 — coordinator and dashboard](#r7--coordinator-and-dashboard)
16. [R8 — optional locator and Scene Mode](#r8--optional-locator-and-scene-mode)

**Part D — operating it**

17. [Running it on the phone](#17-running-it-on-the-phone)
18. [The demo script](#18-the-demo-script)
19. [Failure playbook](#19-failure-playbook)

**Appendices**

- [A — command reference](#appendix-a--command-reference)
- [B — file map](#appendix-b--file-map)
- [C — verified links and checksums](#appendix-c--verified-links-and-checksums)
- [D — what to record for every measurement](#appendix-d--what-to-record-for-every-measurement)

---

# Part A — preparation

## 1. The fork: 12 GB vs 16 GB

**The walk loop is identical on both.** The detector and segmenter together are
under 20 MB of quantized weights and roughly 300–450 MB resident. There is no
12 GB variant of the safety product, and nothing in Parts C1–C7 branches.

The fork affects **only the on-demand Class B models** (§18 of ARCHITECTURE) —
Scene Mode and the target locator.

| | **12 GB** | **16 GB** |
|---|---|---|
| Walk loop | identical | identical |
| Headroom after OS + app | ~5.5 – 6.5 GB | ~9 – 10 GB |
| Class B ceiling (after the 800 MB margin) | ~2.7 GB | ~4.7 GB |
| OCR (Explore) | ML Kit, on-device, ~few MB | same |
| Scene Mode, first choice | detector-derived summary (free, cannot fail) | **4B-class VLM on device** |
| Scene Mode, if you want a real VLM | 2B-class, ~2.5 GB, tight but viable | 4B-class, ~3.7–4.4 GB, comfortable |
| Target locator | landmark memory → laptop Moondream2 | landmark memory → on-device VLM → laptop Moondream2 |

**What you do at handover.** You will know the variant within two minutes of
picking up the phone (§3.1). Then:

- **12 GB** → do not download a phone VLM. Scene Mode ships as the
  detector-derived summary, and the locator fallback is the laptop's Moondream2,
  which already works. Spend the saved hours on R3.
- **16 GB** → run the R8 download in the background from the first hour
  (it is ~3.7 GB and you have the bandwidth), and attempt the on-device VLM only
  after R7 is stable.

> **MUST NOT** let either variant change the walk loop, the risk logic, or the
> safety claims. A 16 GB phone does not earn a more confident demo; it earns one
> optional feature.

### 1.4 Recording the variant

The moment you have the phone, run this and paste the output into the agent's
context:

```bash
adb shell "cat /proc/meminfo | head -3; getprop ro.product.model; getprop ro.soc.model; getprop ro.build.version.release"
```

`MemTotal` around `11.x GB` means the 12 GB SKU; around `15.x GB` means 16 GB.
(Reported `MemTotal` is always below the marketing number — the difference is
carve-out for the modem, display, and secure world.)

---

## 2. Laptop preparation

You have ADB and the Android tooling. You need Python, the export tooling, and a
working build of the current client before anything else starts.

**Budget: 45–60 minutes, mostly unattended downloads.**

### 2.1 Verify what is already there

```bash
adb version && java -version && git --version
```

```bash
cd /c/drishti-edge/apps/android && ./gradlew --version
```

> **Gate 2.1.** Gradle prints a version and JDK 17+ is on the path. If
> `./gradlew` fails here it will fail at hour 6 with the phone in your hand and
> an agent waiting. Fix it now.

### 2.2 Build the client once, unchanged

```bash
cd /c/drishti-edge/apps/android && ./gradlew assembleDebug
```

The first run resolves the whole dependency graph and will take several minutes.

> **Gate 2.2.** `BUILD SUCCESSFUL` and an APK at
> `apps/android/app/build/outputs/apk/debug/app-debug.apk`. This is your
> known-good baseline — everything after this is a delta against it.

### 2.3 Python for the reference implementation and the exports

The Python side does three jobs: it is the behavioural oracle for the golden
vectors, it runs the LAN fallback backend, and it exports the detector.

```bash
cd /c/drishti-edge/entire-old-codebase && py -3.11 -m venv .venv && ./.venv/Scripts/python.exe -m pip install --upgrade pip
```

```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m pip install -r backend/requirements.txt
```

Then the export toolchain, in the same venv:

```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m pip install "ultralytics>=8.3" onnx onnxruntime
```

> **Python 3.11 is not optional if you intend to use the ONNX Runtime QNN export
> path** (§5, path B) — `onnxruntime-qnn` requires 3.11 or later on Windows x64.
> 3.11 also keeps the existing backend's pins happy.

### 2.4 Confirm the reference implementation still runs

```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m pytest backend/tests -q -x --ignore=backend/tests/integration
```

> **Gate 2.4.** The Python suite passes. These tests are the definition of
> correct behaviour for the whole port. If they do not pass, **stop** — every
> golden vector you export from here is worthless, and R3 has nothing to check
> itself against.

### 2.5 One terminal layout that works

Keep four terminals open for the whole build. The agent drives the first; you
own the rest.

| # | Working directory | Purpose |
|---|---|---|
| 1 | `C:\drishti-edge` | The coding agent. One bounded task at a time. |
| 2 | `C:\drishti-edge\apps\android` | `./gradlew` and `adb install` |
| 3 | anywhere | `adb logcat` (filtered, §17.4) |
| 4 | `C:\drishti-edge\entire-old-codebase` | The Python backend and the golden-vector exporter |

---

## 3. Phone preparation

**Budget: 20 minutes. Do all of it before writing any code.**

### 3.1 First five minutes

1. Skip every account sign-in you are allowed to skip.
2. **Settings → About phone → Software version.** Tap the build number seven
   times to enable Developer Options.
3. **Settings → System → Developer options**, then enable:
   - **USB debugging**
   - **Install via USB**
   - **Stay awake while charging** — you will be plugged in for most of the build
   - **Disable permission monitoring** if OriginOS offers it; it interferes with
     repeated installs
4. Connect USB, choose **File transfer / Android Auto** mode, accept the RSA
   fingerprint prompt on the phone.
5. Confirm and record the RAM variant:

```bash
adb devices -l && adb shell "cat /proc/meminfo | head -2"
```

> **GATE P1 — can you install a custom APK at all?**
>
> ```bash
> cd /c/drishti-edge/apps/android && adb install -r app/build/outputs/apk/debug/app-debug.apk
> ```
>
> If this fails, nothing else in this document matters. Escalate to the
> organisers immediately — you have the whole remaining window to pivot, but only
> if you find out now.

### 3.2 Make the phone a stable test bench

- **Battery → Power saving: off.** Battery optimisation for the DRISHTI app:
  **Don't optimise**. OriginOS is aggressive about background camera use.
- **Display → brightness: manual, low.** The screen is a real thermal
  contributor and this build runs screen-on (ARCHITECTURE §2.5).
- **Auto-rotate: off, portrait.** The overlay transform is orientation-aware and
  a surprise rotation mid-test wastes twenty minutes of confusion.
- **Do not charge during a measured soak.** Charging plus sustained NPU load
  throttles far faster than either alone.
- Close every other app. Background apps are the ~1 GB line in the RAM budget.

### 3.3 Office Kit

Pair Office Kit with the laptop now, and leave it connected for the whole build
(ARCHITECTURE §20). Then run the enablement gate — all eight checks in
ARCHITECTURE §20.4, but these three are the ones that actually break:

```bash
adb devices
```
…while Remote PC is active. Then:

```bash
cd /c/drishti-edge/apps/android && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell pm list packages | grep drishti
```
…and confirm the Office Kit session survived the install. Then:

```bash
adb logcat -c && adb logcat --pid=$(adb shell pidof -s com.drishti.app.debug)
```
…with the app in the foreground.

> **If Remote PC is unavailable or unstable on this firmware:** use screen
> mirroring plus laptop keyboard/mouse. Do not rebuild the Android toolchain on
> the phone. The build continues unchanged; only your input method differs.

> **Pause mirroring for any measurement you intend to quote.** Mirroring adds
> display, encode, network and thermal load (ARCHITECTURE §20.2), and a number
> captured with it running is not comparable to one captured without it.

---

## 4. Model assets — what to fetch and from where

Two models run in the walk loop. **Both are small enough to ship inside the
APK** — together under 20 MB — so there is no `adb push` step and no
external-storage permission for the safety path. Only the optional VLM is large
enough to need pushing.

### 4.1 Detector — YOLO11n

The repository's detector is `yolo11n.pt`, and every label, alias, test and
landmark-memory behaviour is written against its semantics. Start here
(ARCHITECTURE §6.1).

**Fetch the source weights** (the same file the Python backend uses):

```bash
mkdir -p /c/drishti-edge/entire-old-codebase/models/detector && curl -L -o /c/drishti-edge/entire-old-codebase/models/detector/yolo11n.pt https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt
```

**Verify it** — this SHA-256 is recorded in the parent project and is the one the
backend expects:

```bash
sha256sum /c/drishti-edge/entire-old-codebase/models/detector/yolo11n.pt
```
Expect `0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1`.

**Export for the phone — primary path (LiteRT / TFLite, INT8):**

```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/yolo.exe export model=models/detector/yolo11n.pt format=tflite int8=True imgsz=640 data=coco8.yaml
```

INT8 export needs a calibration set; `coco8.yaml` is an 8-image COCO sample that
Ultralytics downloads automatically and is adequate for a hackathon. The output
lands in `models/detector/yolo11n_saved_model/yolo11n_full_integer_quant.tflite`.

**Export for the phone — secondary path (ONNX Runtime QNN, HTP v81):**

```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m pip install onnxruntime-qnn
```

```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/yolo.exe export model=models/detector/yolo11n.pt format=qnn name=81 imgsz=640
```

`name=81` selects HTP **v81**, which is Snapdragon 8 Elite Gen 5 — the iQOO 15's
chip. This runs entirely on the laptop, needs no Qualcomm account, and emits a
self-contained `yolo11n_qnn.onnx` with an embedded QNN context binary, quantized
to INT8 weights / 16-bit activations. Windows x64 with Python 3.11+ is a
supported host.

> **The version trap.** A QNN context binary is tied to the QAIRT version that
> produced it. If the host `onnxruntime-qnn` and the device
> `onnxruntime-android-qnn` disagree, the model loads and then fails at run time
> with an unhelpful message. Record both versions (Appendix D), and if they
> cannot be matched, ship the plain quantized ONNX and let the device compile its
> own context binary on first launch (§5.3).

### 4.2 Segmenter — SegFormer-B0 ADE20K

**This is the single most important asset in the build, and it is available
pre-quantized for this exact chip.**

Qualcomm AI Hub publishes `Segformer-Base`, which is
`nvidia/segformer-b0-finetuned-ade-512-512` — **byte-for-byte the same checkpoint
the working Python backend uses**, 150 ADE20K classes, 512×512 input, 3.75 M
parameters, and its device list includes **Snapdragon 8 Elite Gen 5 Mobile**.

Direct downloads, no account, no auth (all verified to return HTTP 200):

| Variant | Size | URL |
|---|---|---|
| TFLite W8A8 | 3.90 MB | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-tflite-w8a8.zip` |
| TFLite float | 14.4 MB | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-tflite-float.zip` |
| ONNX W8A16 | 4.57 MB | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-onnx-w8a16.zip` |
| QNN DLC W8A16 | 4.57 MB | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-qnn_dlc-w8a16.zip` |

```bash
mkdir -p /c/drishti-edge/apps/android/app/src/main/assets/models && cd /c/drishti-edge/apps/android/app/src/main/assets/models && curl -L -O https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-tflite-w8a8.zip && curl -L -O https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-tflite-float.zip && unzip -o segformer_base-tflite-w8a8.zip && unzip -o segformer_base-tflite-float.zip && rm *.zip && ls -la
```

**Fetch both W8A8 and float.** W8A8 is four times smaller and faster; float is
your control when you need to answer "did quantization eat the floor boundary?"
in R5 without a long detour. Both fit in the APK.

> **Why this matters more than the detector.** The indoor behaviour that makes
> this product work — floor versus wall, dead end, stairs — comes from ADE20K
> semantics. The parent project measured a Cityscapes segmenter labelling both
> tiled floor *and* a blank wall as high-confidence `ROAD`, which made safe
> polygons unreachable and let side guidance be picked from noise. Replacing that
> with this exact ADE20K checkpoint is what fixed it. Getting the *same
> checkpoint* onto the phone is therefore not a convenience — it is the reason
> the indoor demo can work at all.

### 4.3 OCR — Explore Mode

ML Kit's bundled text recognizer. On-device, no NPU claim, no download at run
time, and it will simply work:

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.1")
```

### 4.4 Optional VLM — 16 GB only, and only after R7

Fetch in the background during R2–R5; it is large and there is no reason to wait
on it.

| Option | Format | Size | Runtime |
|---|---|---|---|
| `litert-community/gemma-4-E4B-it-litert-lm` | `.litertlm` | 3.66 GB | LiteRT-LM `0.17.0` |
| `google/gemma-3n-E4B-it-litert-lm` | `.task` | 4.41 GB | MediaPipe / LiteRT-LM |
| `litert-community/gemma-4-E2B-it-litert-lm` | `.litertlm` | 2.58 GB | LiteRT-LM — the 12 GB option, if you insist |

```bash
cd /c/drishti-edge && pip install -U "huggingface_hub[cli]" && hf download litert-community/gemma-4-E4B-it-litert-lm --local-dir models/vlm
```

Push to the phone (not into the APK):

```bash
adb push models/vlm/gemma-4-E4B-it.litertlm /sdcard/Android/data/com.drishti.app.debug/files/models/
```

> **Verify vision support before you build anything around it.** Several
> `.litertlm` bundles are text-only, with vision weights loaded separately. The
> R8 card checks this in ten minutes with a throwaway activity, before any
> integration work.

### 4.5 Optional locator fallback — already working, costs nothing

Moondream2 on the laptop, behind `/api/v1/vlm/locate`, is already implemented and
tested. On a 12 GB phone it **is** the answer for "find the registration desk".
Stage it exactly as the parent project documents in
`entire-old-codebase/models/vlm/README.md`. Advertise it as *laptop-assisted
target localization*, never as on-device AI (ARCHITECTURE §19.3).

---

## 5. Runtime decision — LiteRT vs ONNX Runtime QNN

Two credible ways to reach the Hexagon NPU from Kotlin. **Spike both in R1, in
that order, and stop at the first that works.** Budget 45 minutes total.

### 5.1 Path A — LiteRT `CompiledModel` (recommended first)

```kotlin
implementation("com.google.ai.edge.litert:litert:2.2.0")
```

```kotlin
val model = CompiledModel.create(
    context.assets,
    "models/yolo11n_full_integer_quant.tflite",
    CompiledModel.Options(Accelerator.NPU, Accelerator.GPU),
)
```

**Why first:**

- The accelerator list *is* the fallback ladder from ARCHITECTURE §6.1 — NPU,
  then GPU, then CPU — expressed in one constructor argument, with no separate
  code path to write for each rung.
- It JIT-compiles a plain `.tflite` on the device, so you need no vendor
  compiler on the laptop and no version handshake between host and phone.
- It takes `.tflite` directly, which is the format both of our models already
  have.
- NPU support requires **API 31+**; the app's `minSdk` is exactly 31.
- Qualcomm HTP runtime versions **v69 – v81** are covered, and v81 is our chip.

**The one piece of friction:** the NPU runtime libraries ship as
`litert_npu_runtime_libraries.zip` and are integrated into the Gradle
configuration manually, or delivered through Play Feature Delivery. Resolve this
in R1; if it resists for more than 20 minutes, the GPU accelerator still works
with the same code and is a legitimate rung 4.

### 5.2 Path B — ONNX Runtime with the QNN execution provider

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
```

Pairs with the Ultralytics `format=qnn` export from §4.1. Session options that
matter:

| Option | Value | Why |
|---|---|---|
| `backend_type` | `htp` | The NPU. `cpu`/`gpu` also valid for A/B tests. |
| `htp_performance_mode` | `burst` | For the walk loop. |
| `htp_graph_finalization_optimization_mode` | `3` | Slower first load, faster steady state. |
| `ep.context_enable` | `1` | Generate a context binary… |
| `ep.context_file_path` | app files dir | …and cache it next to the app. |

**Known constraints:** the HTP backend runs **quantized models only** — uint8
weights with uint16 activations is the recommended combination, which is exactly
what the Ultralytics `w8a16` export and the AI Hub `w8a16` assets produce. You
may also have to supply `libQnnHtp*.so` from the QAIRT SDK in `jniLibs` if the
AAR does not carry them; find out in R1, not at hour 14.

### 5.3 The rule that saves you

> If the host-generated context binary refuses to load on device, **ship the
> plain quantized model and generate the context binary on the phone at first
> launch**, caching it in the app's files directory. First launch costs seconds;
> every launch after that is fast, and you have removed an entire class of
> version-mismatch failure.

### 5.4 Do not do these

| Tempting | Why not |
|---|---|
| Chasing an AI Hub compile job for the detector | Ultralytics exports v81 locally in one command. A cloud compile job adds an account, a queue, and a device-name guess. |
| Swapping to YOLOv8 because a sample used it | Changes label semantics, post-processing, and test compatibility at the same time as the platform. ARCHITECTURE §6.1 forbids it without a measured reason. |
| A Cityscapes segmentation model because it profiled faster | It does not contain the indoor semantics this product reasons about. Measured, it broke the floor/wall distinction outright. |
| Running both models concurrently to "use the NPU better" | One accelerator, one scheduler (ARCHITECTURE §16.1). Concurrent submissions serialize unpredictably or fault. |

---

# Part B — judgement

## 6. Are these models good enough for the demo?

You asked the right question, and the answer is not uniform across the four
models. Taken one at a time, against what each demo scene actually requires.

### 6.1 Verdict table

| Stage | Model | Verdict | Confidence |
|---|---|---|---|
| Detection | YOLO11n INT8 | **Yes** — same weights, same labels, same thresholds as the working system | High |
| Segmentation | SegFormer-B0 ADE20K W8A8/W8A16 | **Yes, and this is the finding that de-risks the build** — AI Hub ships the identical checkpoint, quantized, listed for 8 Elite Gen 5 | High on availability, medium on quantization fidelity |
| OCR | ML Kit | **Yes** for reading a sign; it is not an NPU story | High |
| Scene VLM | 4B-class on 16 GB | **Yes but unnecessary** — nothing in the core demo needs it | Medium |

### 6.2 Segmentation — the one that decides the indoor demo

**The good news is specific.** The parent project's indoor accuracy was not
fixed by tuning; it was fixed by *replacing the segmentation model*. The measured
failure was a Cityscapes segmenter labelling both tiled floor and a blank wall as
high-confidence `ROAD`, which made safe polygons unreachable, inflated clear-floor
corridor costs to the blocking threshold, and let side guidance be selected from
small noisy cost differences. Swapping in `nvidia/segformer-b0-finetuned-ade-512-512`
is what produced working floor, wall, door and stairs semantics.

Qualcomm AI Hub publishes that same checkpoint, already quantized, with
Snapdragon 8 Elite Gen 5 Mobile in its device list. So the phone can run the
*exact model whose behaviour the whole indoor logic was tuned against* — not an
analogue, not a substitute. That removes the largest single unknown in the
architecture.

**Now the part that is not yet proved, and that R5 exists to settle.** Three
things can still go wrong between "the same weights" and "the same behaviour":

1. **Quantization fidelity.** W8A8 is 3.90 MB against 14.4 MB float. Class
   *identity* survives aggressive quantization well; *boundary precision* is what
   degrades. The logic here consumes per-corridor floor extent derived from the
   median contiguous floor run and a centre-wall ratio, so a boundary that moves
   by a few pixels shifts a cost, and a cost near `0.40` is near a decision. This
   is why §4.2 tells you to download the float build too — it converts a possible
   half-day of confusion into one A/B run.
2. **Preprocessing parity.** Resize behaviour, channel order, and normalisation
   constants must match what the Python pipeline feeds SegFormer. Mismatched
   normalisation produces a mask that looks *plausible* and is subtly wrong,
   which is the worst kind of wrong for this product.
3. **Mask transform.** The mask must land in `ORIENTED_CAPTURE_NORMALIZED` like
   everything else. A mask misalignment is harder to see than a box misalignment
   and corrupts corridor costs directly (ARCHITECTURE §10.2).

All three are checked by replaying the five controlled indoor fixtures
(`clear-corridor`, `blank-wall`, `door-wall-left`, `room-corner`, `stairs-ahead`)
phone-against-PC. That is the R5 gate and it is cheap.

> **A planning consequence worth stating plainly.** Four of the five indoor
> fixtures depend on segmentation. Without it, `WALL_OR_DEAD_END_AHEAD` and
> `STAIRS_OR_LEVEL_CHANGE_AHEAD` cannot be produced at all, safe floor polygons
> disappear, and most indoor frames resolve to `PAUSE_UNCLEAR`. That is *honest*
> — it is exactly what ARCHITECTURE §6.2 requires — but it is not a demo anyone
> remembers.
>
> ARCHITECTURE's ladder treats segmentation as droppable because, when it was
> written, phone availability was unknown. It is now known: a 3.9 MB pre-quantized
> TFLite of the right checkpoint. **So R1 should prove both models in the same
> spike** — it is one runtime question asked twice — while R5 keeps its place for
> the semantic validation that genuinely needs the ported spatial code.

### 6.3 Detection — low risk, with one thing to watch

YOLO11n INT8 on this chip is the least interesting risk in the build. The weights
are the ones the system was built on, the 19-class audited allowlist is applied
*after* inference, and the thresholds (confidence 0.35, NMS IoU 0.45, 640 input)
port unchanged.

Two real caveats:

- **Small-object recall drops first under INT8.** The audited risk set is
  dominated by large furniture — chair, desk, couch, bed, refrigerator — which is
  forgiving. `bag` and `umbrella` are the ones to spot-check.
- **Full-integer TFLite output is quantized.** Dequantize with the tensor's
  scale and zero-point before decoding boxes. Forgetting this produces boxes that
  are confidently, uniformly wrong — and it looks like a coordinate bug, so it
  eats hours. Check it on frame one.

`door` remains unreachable from COCO weights, exactly as Appendix B of
ARCHITECTURE states. Door evidence comes from ADE20K or does not exist.

### 6.4 What the numbers will probably look like — and why that is not the risk

SegFormer-B0 is 3.75 M parameters; YOLO11n is around 2.6 M. On an 8 Elite Gen 5
HTP these are small models, and the per-inference cost is very unlikely to be
what stops you.

**Expect the bottleneck to be the glue, not the NPU:** YUV_420_888 → RGB
conversion, letterboxing, the dequantize-and-decode pass, NMS, contour extraction
from the mask, and polygon simplification. All of that is CPU work, all of it is
per-frame, and none of it is accelerated by anything.

Budget accordingly (ARCHITECTURE §8.4), and measure **camera-to-guidance**, never
a model profile in isolation. A published per-layer profile excludes every one of
the costs listed above.

### 6.5 The VLM, honestly

Nothing in the core demo needs it:

- **Walk** needs detection and segmentation.
- **Find** is answered from the full-COCO landmark memory populated by the same
  detector pass — `bottle`, `clock`, `book`, `laptop`, `cup`, `backpack` all
  resolve with no VLM in the path (ARCHITECTURE §14.1).
- **Read** is ML Kit.
- **Ask** is the only mode that wants one, and its rung 3 — a sentence composed
  from the detection list — is genuinely useful and cannot fail.

On a **16 GB** phone a 4B-class model fits comfortably as a Class B load
(~3.7 GB against a ~4.7 GB ceiling) and is worth attempting **after R7**. On
**12 GB** the honest recommendation is to not ship a phone VLM at all and to use
the laptop Moondream2 for the compositional target cases, clearly labelled.

> One correction to carry forward. ARCHITECTURE §6.4 cites an AI Hub page that
> simultaneously listed a chipset as supported and stated *"This model is
> currently not supported on any Mobile chipset"*, and treats that as evidence
> against the model. That same sentence appears on the AI Hub pages for
> Segformer-Base and YOLOv11-Detection — models that are definitely supported and
> that this plan depends on. It is an artifact of the chipset-filter widget, not
> a statement about the model. **Do not use it as evidence either way; compile and
> measure instead.**

### 6.6 Risk, ranked

The models are not the top risk. This is:

| # | Risk | Why it is ranked here | Mitigation |
|---|---|---|---|
| 1 | **Porting the risk and spatial logic to Kotlin correctly** | It is the largest body of behaviour, it is subtle (a rule cascade plus four pending states), and a wrong port looks like a working app | R0 golden vectors before any porting; R3 parity gate |
| 2 | **Preprocessing / coordinate parity** | Silent, looks like camera jitter or model weakness | The `(0,0,1,1)` overlay test; fixture replay against PC outputs |
| 3 | **NPU reachability from a third-party app** | Outside your control | R1 spike, two paths, GPU rung is a real fallback |
| 4 | **Quantization shifting corridor costs near a threshold** | Decisions cluster near `0.40` | Float build downloaded and ready for A/B |
| 5 | **Thermal decay over the day** | Cold benchmarks lie | 20-minute soak, cadence ladder |
| 6 | VLM not landing | Optional by design | Detector-derived summary; laptop locator |

Everything above the line marked 3 is a software-correctness problem you control
entirely. Spend your hours there.

---

# Part C — the build

## 7. How to run an agent task card

Each card below is one bounded task. Give the agent **one card at a time** —
never two — and follow this loop (ARCHITECTURE §20.3):

1. Paste the card into the agent in terminal 1.
2. Let it work. Read the diff. Do not accept a diff you have not read.
3. Run the card's **Acceptance** commands yourself in terminal 2.
4. Install and exercise on the phone; watch terminal 3's logcat.
5. Commit with the card's message, so every APK traces to a commit
   (ARCHITECTURE §7.4).
6. Only then move to the next card.

Each card has the same shape:

> **Goal** · **Read first** · **Touch** · **Do** · **Acceptance** · **If it
> fails** · **Commit**

**A standing instruction to give the agent once, at the start of every session:**

> Read `ARCHITECTURE.md` §1–§3 and the section this card names before writing
> code. Do not change behaviour and platform in the same step. Do not invent
> thresholds — every constant comes from `entire-old-codebase/backend/app/config.py`
> and is quoted in the diff. Do not add a network call to the walking path. If a
> golden vector disagrees with your port, the golden vector is right. If you
> believe a ported behaviour is wrong, say so and stop; do not fix it silently.

---

## R0 — freeze behaviour, export golden vectors

**Goal.** Make the Python implementation's behaviour executable as test data, so
the Kotlin port has something to be wrong against.

**Read first.** ARCHITECTURE §22 R0, §12, §13.3, Appendix C.

**Touch.**
- new: `entire-old-codebase/backend/scripts/export_golden_vectors.py`
- new: `apps/android/app/src/test/resources/golden/*.json`

**Do.**

1. Tag the working reference:
   ```bash
   cd /c/drishti-edge/entire-old-codebase && git tag -f reference-baseline && git rev-parse --short HEAD
   ```
   (If that directory is not its own git repo, record the directory hash instead:
   `find . -name "*.py" -path "*/app/*" | sort | xargs sha256sum | sha256sum`.)

2. Write an exporter that walks synthetic and fixture inputs through the real
   Python stages and dumps input → output JSON for each of:

   | File | Covers |
   |---|---|
   | `detector_canonicalization.json` | Both views from one detection list: full-COCO names unaliased; risk view allow-listed and aliased (`backpack`/`handbag`→`bag`, `dining table`/`table`→`desk`) |
   | `proximity.json` | Box geometry → `ProximityBand`, across all four bands and `UNKNOWN` |
   | `corridor.json` | Trapezoid geometry, `direction_for_anchor`, `bbox_path_overlap`, per-corridor costs, floor extents, wall and stairs ratios |
   | `risk_scoring.json` | The weighted score (0.30/0.25/0.20/0.15/0.10) and `RiskLevel` banding at 0.25/0.65/0.50/0.80 |
   | `rules_cascade.json` | All nine outcomes of the ordered cascade, each with action, level, reason code, preferred corridor, critical track ids |
   | `state_machine.json` | Frame sequences exercising `CRITICAL` bypass, `PAUSE_UNCLEAR` immediacy, 2-frame persistence, `DIRECTION_CHANGE_PENDING`, 3-frame decay, `RISK_HYSTERESIS_ACTIVE`, the 3.0 s cooldown |
   | `target_guidance.json` | `IDLE`→`SEEKING`→`GUIDING`→`ARRIVED`/`LOST`, bearing from normalized x at 67° HFOV, turn threshold 25°, face tolerance 10° |
   | `landmark_memory.json` | Label normalization, person rejection, TTL 45 s, min sightings 2, min confidence 0.45, max 40 |

   Each case is `{"name": ..., "input": {...}, "expected": {...}}`. **Every case
   must be produced by calling the real Python functions** — never hand-written
   from reading the code.

3. Include the failure cases, not only the successes. A port that gets
   `PATH_CLEAR` right and `CENTRE_BLOCKED_DIRECTION_UNCLEAR` wrong is a dangerous
   port.

**Acceptance.**
```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe backend/scripts/export_golden_vectors.py --out ../apps/android/app/src/test/resources/golden && ls -la ../apps/android/app/src/test/resources/golden
```
```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m pytest backend/tests -q --ignore=backend/tests/integration
```

Eight JSON files exist, each with at least ten cases, and the Python suite is
still green.

**If it fails.** If a stage is hard to isolate, export at the boundary you *can*
isolate and note the gap in the file. A partial oracle beats none. Do not
proceed to R3 with fewer than the rules cascade and the state machine.

**Commit.** `R0: export golden behaviour vectors from the Python reference`

---

## R1 — prove the phone inference runtime

**Goal.** Answer one question with evidence: *can this app run these two models
on the NPU of this phone?* Nothing else.

**Read first.** ARCHITECTURE §7.3, §22 R1. This plan §5.

**Touch.**
- new module or activity: `apps/android/app/src/main/java/com/drishti/app/infer/spike/`
- `apps/android/app/build.gradle.kts`
- `apps/android/gradle/libs.versions.toml`

**Do.**

1. Add the LiteRT dependency and the model assets:
   ```kotlin
   // libs.versions.toml
   litert = "2.2.0"
   litert-core = { module = "com.google.ai.edge.litert:litert", version.ref = "litert" }
   ```
   ```kotlin
   // app/build.gradle.kts
   implementation(libs.litert.core)

   androidResources {
       noCompress += listOf("tflite", "onnx", "litertlm")
   }
   ```
   `noCompress` matters: a compressed asset cannot be memory-mapped, so the
   runtime copies it and you pay the RAM twice.

2. Write a **throwaway** spike activity that, for each of
   `yolo11n_full_integer_quant.tflite` and `segformer_base` W8A8:
   - loads it with `CompiledModel.Options(Accelerator.NPU, Accelerator.GPU)`,
   - reports **which accelerator was actually selected**,
   - runs 50 inferences on a fixed synthetic input,
   - prints min / median / p95 milliseconds and peak resident memory,
   - repeats the whole run pinned to `Accelerator.CPU` for comparison.

3. If the NPU runtime libraries are not resolved automatically, integrate
   `litert_npu_runtime_libraries.zip` per the LiteRT NPU documentation. **Give
   this 20 minutes, no more.**

4. If LiteRT does not reach the NPU, spike path B (§5.2) with the same
   measurements: ORT `1.29.0`, `backend_type=htp`, `htp_performance_mode=burst`,
   the Ultralytics `format=qnn name=81` export, and context-binary caching on
   device.

**Acceptance.**
```bash
cd /c/drishti-edge/apps/android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.drishti.app.debug/com.drishti.app.infer.spike.SpikeActivity
```
```bash
adb logcat -c && adb logcat -s DrishtiSpike:V
```

> **GATE R1.** Both models execute repeatedly, the selected accelerator is
> reported (not inferred from speed), and NPU median latency is roughly an order
> of magnitude below the CPU run. Record every field in Appendix D.
>
> **Decision rule:**
> - NPU on both → proceed to R2 as designed.
> - NPU on the detector, GPU on the segmenter → proceed; note the segmenter as
>   rung 4 and revisit cadence in R5.
> - GPU only → proceed on GPU. Say "GPU" to judges, never "NPU". The product is
>   unchanged; one claim weakens.
> - Neither loads → **stop feature work.** Fall back to the LAN toggle
>   (`InferenceSource.LAN_BACKEND`, R2) so there is still a working demo, and
>   spend the remaining time on R3/R4 quality and the dashboard story.

**If it fails.** The most common causes, in order: the asset was compressed
(fix `noCompress`); the model is float where the HTP requires quantized; the NPU
runtime libraries were never packaged; a QAIRT version mismatch between host
export and device runtime (§5.3).

**Commit.** `R1: NPU runtime spike — <accelerator>, <median ms> detector / <median ms> segmenter`

---

## R2 — replace the network inference seam

**Goal.** The camera frame reaches a model in-process. One inference produces two
detection views. Boxes draw correctly on the preview. The LAN path survives
behind a toggle.

**Read first.** ARCHITECTURE §2.2, §8.1–§8.4, §9.1–§9.3, §10, §22 R2.

**Touch.**
- new: `infer/OnDeviceDetector.kt`, `infer/LiteRtDetector.kt`,
  `infer/AcceleratorScheduler.kt`, `infer/Preprocess.kt`, `infer/PostProcess.kt`
- new: `perception/Canonicalizer.kt`
- edit: `walk/WalkController.kt`, `di/AppContainer.kt`,
  `settings/SettingsStore.kt`, `ui/SettingsScreen.kt`
- keep untouched: `walk/CameraFramePipeline.kt`, `ui/PreviewTransform.kt`,
  `walk/FrameFreshnessGate.kt`, `net/*`

**Do.**

1. **Define the seam.** One interface, two implementations, chosen at runtime:

   ```kotlin
   interface InferenceSource {
       suspend fun analyze(frame: OrientedFrame): DetectionSet   // risk + all
       val computeDevice: ComputeDevice                          // NPU|GPU|CPU|CUDA|NONE
       val executionOwner: ExecutionOwner                        // PHONE|LAPTOP
   }
   ```
   - `OnDeviceInference` — LiteRT (or ORT), new.
   - `LanBackendInference` — wraps the existing `DrishtiApi.analyze` multipart
     call, unchanged.

   Add `inference_source` to `SettingsStore` (default `ON_DEVICE`) and a plain
   switch in `SettingsScreen`. **The LAN path is insurance, and it also gives you
   a free A/B oracle on the phone itself** — same frame, two engines, compare.

2. **Preprocess.** `ImageProxy` YUV_420_888 → RGB → rotate to oriented → letterbox
   to 640×640 → the model's expected quantized input. Reuse the ring of 3
   buffers; no per-frame `Bitmap` allocation (ARCHITECTURE §5.3).

3. **The accelerator scheduler.** One serialized submitter with priority
   ordering, one in-flight inference, at most one replaceable pending frame,
   latest wins (ARCHITECTURE §8.2, §16.1). Do not skip this because only one
   model exists yet — retrofitting it after the segmenter lands is how the
   intermittent faults arrive.

4. **Postprocess.** Dequantize using the output tensor's scale and zero-point,
   decode boxes, NMS at IoU 0.45, un-letterbox, normalize against the **oriented
   capture** — not the tensor (ARCHITECTURE §9.3, §10.2).

5. **Canonicalize into two views from the one result** — port
   `entire-old-codebase/backend/app/perception/detector.py`'s
   `canonicalize_detections` exactly:
   - `all` — native COCO names, **no aliases**, above the confidence floor.
   - `risk` — the 19-label allowlist with aliases applied.

6. **Wire into `WalkController`** behind the toggle, preserving the existing
   freshness gate and capture pacing.

**Acceptance.**
```bash
cd /c/drishti-edge/apps/android && ./gradlew testDebugUnitTest
```
```bash
cd /c/drishti-edge/apps/android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone, with `ON_DEVICE` selected:

| # | Check | Pass |
|---|---|---|
| 1 | Point at a chair, a person, a desk | Boxes appear, labelled, tracking the objects |
| 2 | Overlay alignment | A box drawn at `(0,0,1,1)` traces the full oriented capture; under `COVER` it extends past the viewport edges — that is correct |
| 3 | Ten controlled frames | Ten fresh results, no queue growth, no stale output |
| 4 | Airplane mode on | Identical behaviour, no errors, no retry storm |
| 5 | Flip the toggle to `LAN_BACKEND` with the backend running | Still works; results comparable |
| 6 | `adb shell dumpsys meminfo com.drishti.app.debug` after 2 min | Resident memory flat, not climbing |

> **GATE R2.** Checks 1–4 pass. Check 4 is the one that proves the seam is real:
> if guidance survives airplane mode, no transport remains in the walking path.

**If it fails.** Boxes offset by a constant factor → letterbox or orientation
handled in the wrong order. Boxes uniformly wrong and confidence nonsense →
missing dequantization. Camera freezes after ~5 seconds → a leaked `ImageProxy`
on some path, probably an exception branch.

**Commit.** `R2: on-device detector behind an InferenceSource seam, LAN path retained`

---

## R3 — port deterministic safety behaviour

**This is the largest and highest-risk card in the build. Do not let an agent
take it in one pass.** Split it into R3a–R3d, each with its own golden vector
file and its own commit.

**Read first.** ARCHITECTURE §11, §12, §13, Appendix B, Appendix C. The golden
vectors from R0. The Python sources: `spatial/corridor.py`, `spatial/proximity.py`,
`spatial/surfaces.py`, `risk/scoring.py`, `risk/rules.py`, `risk/state_machine.py`.

### R3a — tracking and proximity

**Touch.** `perception/Tracker.kt`, `spatial/Proximity.kt`

IoU + class association, `track_iou_threshold` 0.20,
`track_centre_distance_threshold` 0.12, `track_max_age_frames` 3. Approach state
from box-area growth with `approach_change_threshold` 0.05. Proximity from
`proximity_area_weight` 0.55, `proximity_area_scale` 0.50, bands at 0.35 / 0.55 /
0.78.

**Acceptance.** `./gradlew testDebugUnitTest --tests "*ProximityGoldenTest*" --tests "*TrackerGoldenTest*"`

### R3b — corridor geometry

**Touch.** `spatial/Corridor.kt`, `spatial/Surfaces.kt`

The trapezoid — `corridor_horizon_y` 0.38, `corridor_top_half_width` 0.08,
`corridor_bottom_half_width` 0.42 — with `direction_for_anchor` interpolating the
half-width by vertical progress, `bbox_path_overlap` against the corridor
polygon, per-corridor costs, floor extents, wall and stairs ratios.

> **It is a perspective trapezoid, not three equal vertical thirds.** Porting it
> as thirds is the single easiest way to produce a system that is subtly wrong in
> every frame and passes a casual look.

**Acceptance.** `./gradlew testDebugUnitTest --tests "*CorridorGoldenTest*"`

### R3c — the weighted score and the rule cascade

**Touch.** `risk/Scoring.kt`, `risk/Rules.kt`

The weighted score, 0.30 path overlap / 0.25 proximity / 0.20 approach / 0.15
class severity / 0.10 confidence, summing to exactly 1.0; bands at 0.25 / 0.65 /
0.50 / 0.80. Then the nine-step ordered cascade of ARCHITECTURE §12.2, with the
vehicle set `{bicycle, motorcycle, car, bus}` and critical thresholds 0.60 / 0.70
/ 0.15.

Severities come from Appendix B and are quoted in the diff.

**Acceptance.** `./gradlew testDebugUnitTest --tests "*RiskScoringGoldenTest*" --tests "*RulesCascadeGoldenTest*"`

### R3d — the state machine

**Touch.** `risk/AlertStateMachine.kt`

Port `state_machine.py` including the four pending codes. The order of the
branches *is* the behaviour:

```
CRITICAL            → commit now, bypass cooldown
PAUSE_UNCLEAR       → commit now
same as current     → commit now
new decision        → needs 2 consecutive frames
   while pending, current is MOVE_*  → PAUSE_UNCLEAR / DIRECTION_CHANGE_PENDING
   while pending, otherwise          → silent CLEAR / ALERT_PERSISTENCE_PENDING
decay to CLEAR      → needs 3 consecutive frames
   evidence still ≥ 0.50             → silent CAUTION / RISK_HYSTERESIS_ACTIVE (frame not counted)
   otherwise                         → silent CAUTION / RISK_DECAY_PENDING
speech cooldown     → 3.0 s, bypassed by CRITICAL
```

**Acceptance.** `./gradlew testDebugUnitTest --tests "*StateMachineGoldenTest*"`

> **GATE R3.** Every golden vector passes on decision, level, **reason code**,
> preferred corridor, and critical track ids. Not "mostly passes". A single
> disagreement means the port and the reference differ somewhere, and you do not
> yet know where else.

**If it fails.** Diff the Kotlin output against the JSON case by case. Do not
adjust a threshold to make a test pass — if you believe a constant is wrong, that
is a tuning decision, and it is made against measurements after parity, never
during the port (ARCHITECTURE §12.1).

**Commit.** One per sub-card: `R3a: port tracking and proximity under golden parity`, etc.

---

## R4 — phone-owned accessible output

**Goal.** The guidance object drives speech and spatial audio directly, with
safety preempting everything.

**Read first.** ARCHITECTURE §13.4, §15, §2.5.

**Touch.** `feedback/` (wiring only), `walk/WalkController.kt`, `ui/StateBanner.kt`

**Do.**

1. Drive `SpeechEngine`, `SpatialAudioEngine`, `SonarMapping` and
   `AudioFocusManager` from the in-process `GuidanceContract` instead of the
   parsed HTTP response. The interfaces do not change.
2. Implement the four-level preemption of §13.4. Priority 1 interrupts
   mid-utterance via audio focus; it is never queued.
3. Mute target spatial audio whenever the action is not `CLEAR`.
4. Keep ducking, never stopping, the user's own audio.
5. Haptics stay wired and out of the acceptance path (ARCHITECTURE §2.5).
6. Build the **diagnostics panel** now if R1 left it as a spike: per stage,
   backend in use, inference ms, rolling FPS, resident MB, thermal status. It is
   required by ARCHITECTURE §7.3 and it is the best demo prop you have.

**Acceptance.** On the phone: visible guidance, spoken action, and spatial audio
agree on the same frame; a `STOP` interrupts a target cue immediately; music
playing in another app ducks rather than stops.

**Commit.** `R4: drive speech and spatial audio from in-process guidance`

---

## R5 — segmentation

**Goal.** Floor, wall, and stairs semantics on the phone, matching the PC.

**Read first.** ARCHITECTURE §6.2, §9.4, §11.3, §22 R5. This plan §6.2.

**Touch.** `infer/Segmenter.kt`, `infer/MaskToPolygons.kt`, `spatial/Surfaces.kt`

**Do.**

1. Load the AI Hub `segformer_base` W8A8 TFLite through the same accelerator
   scheduler, at the segmentation priority. Never concurrent with a detector
   submission.
2. Reproduce the Python preprocessing exactly — resize, channel order,
   normalisation constants. Read them from
   `entire-old-codebase/backend/app/perception/segmenter.py`; do not assume
   ImageNet defaults.
3. Map the 150 ADE20K classes with the token-normalized table the backend
   already uses, into `WALKABLE` / `ROAD` / `NON_WALKABLE` / `UNKNOWN`.
   Low-confidence pixels map to `UNKNOWN`, **never** to `WALKABLE`.
4. Contours → Douglas–Peucker → normalized polygons, **capped at 40 vertices**.
5. Feed surface ratios and floor extents into the corridor costs from R3b.
6. Cadence: every 2nd frame at `NONE`/`LIGHT` thermal, per ARCHITECTURE §17.3.

**Acceptance — the fixture replay.** This is the gate that matters.

```bash
cd /c/drishti-edge/entire-old-codebase && $env:DRISHTI_INDOOR_FIXTURE_DIR="C:\path\to\approved-controlled-frames"; ./.venv/Scripts/python.exe -m pytest backend/tests/integration/test_indoor_frames.py -m real_indoor
```

Then push the same five JPEGs to the phone and run them through a debug replay
entry point. Compare, per fixture:

| Fixture | PC expectation | Phone must agree on |
|---|---|---|
| `clear-corridor` | `CLEAR`/`CAUTION`, ≥1 safe polygon | action class and a safe polygon existing |
| `blank-wall` | `STOP` / `WALL_OR_DEAD_END_AHEAD` | action **and** reason code |
| `door-wall-left` | `STOP`/`PAUSE_UNCLEAR`, never `MOVE_LEFT` | the forbidden action staying forbidden |
| `room-corner` | `PAUSE_UNCLEAR` | action |
| `stairs-ahead` | `STOP` / `STAIRS_OR_LEVEL_CHANGE_AHEAD` | action **and** reason code |

> **GATE R5.** All five agree. If W8A8 disagrees on one or two, rerun with the
> float TFLite you downloaded in §4.2 before touching any threshold. If float
> fixes it, the quantization is the cause and W8A16 is the compromise; if float
> does not, the cause is preprocessing or the mask transform, and no amount of
> tuning will fix it.

**If the gate cannot be passed:** ship detector-only corridor reasoning, set
`degraded_modules = ["segmentation"]`, announce it aloud, and **stop claiming
wall and stairs semantics** — in the app, in the pitch, and in the README.
Expect most indoor frames to become `PAUSE_UNCLEAR`, and present that as the
system being honest, because it is.

**Commit.** `R5: ADE20K segmentation on device, fixture parity with the reference`

---

## R6 — Ask, Lock, Guide

**Goal.** "Find my bag" works from detector memory, with no VLM in the path.

**Read first.** ARCHITECTURE §14.

**Touch.** `perception/LandmarkMemory.kt`, `guidance/TargetGuidance.kt`,
`scene/TargetLocator.kt` (rewire), `feedback/SonarMapping.kt` (reuse)

**Do.**

1. Populate a TTL-bounded landmark memory from the **full-COCO view** of the same
   detector pass: TTL 45 s, max 40, min confidence 0.45, min sightings 2, person
   rejected.
2. Port `normalize_label` and `labels_match` so a spoken word reaches the right
   canonical form without destroying valid COCO nouns.
3. Resolution order of §14.1: reject person → normalize → search memory → lock and
   hand the box to the tracker → locator only on a miss.
4. States `IDLE`/`SEEKING`/`GUIDING`/`ARRIVED`/`LOST`. Bearing from normalized x
   against 67° HFOV; turn threshold 25°, face tolerance 10°, reacquire timeout
   8 s, arrived dwell 2 s, speech interval 4 s.
5. Target audio muted whenever the risk action is not `CLEAR`.

**Acceptance.** On the phone: place a backpack and a bottle in view, walk past
them, then ask for each. Both lock from memory with no network call and no VLM.
Cover the target — state goes `LOST` after ~8 s and says so. Trigger a `STOP`
while guiding — safety speech wins, target audio mutes.

**Commit.** `R6: Ask-Lock-Guide from detector landmark memory`

---

## R7 — coordinator and dashboard

**Goal.** The dashboard shows a live phone-owned session, and killing the laptop
changes nothing on the phone.

**Read first.** ARCHITECTURE §19, Appendix A amendments.

**Touch.** `telemetry/TelemetryQueue.kt`, `telemetry/CoordinatorClient.kt`,
`packages/contracts/src/index.ts`, `apps/dashboard/src/`

**Do.**

1. Bounded queue, capacity 50, **drop-oldest**, detached coroutine, short
   timeout, never blocking enqueue.
2. Send the telemetry envelope of Appendix A item 3 — frame id and time, phone
   stage timings, detector and segmenter state, action, reason code, target
   state, safety override. **No image bytes.**
3. Add `NPU` to `ComputeDevice` and the `PHONE`/`LAPTOP` execution owner; update
   the dashboard health and model panels so phone inference is never rendered as
   laptop VRAM.
4. Record the amendments in `docs/DECISIONS.md` (see the note at the end of this
   plan) before merging the contract change.
5. Reconnection replays nothing.

**Acceptance.**
```bash
cd /c/drishti-edge && npm test && npm run typecheck
```
Then: start a walk, watch the dashboard update, **kill the backend process
mid-walk**, and confirm the phone does not stutter, warn, or retry. Restart it
and confirm no stale instruction is replayed.

**Commit.** `R7: phone telemetry to the coordinator, dashboard distinguishes phone NPU`

---

## R8 — optional locator and Scene Mode

**Only if R1–R7 are stable.** On 12 GB, the answer is usually the laptop.

**Read first.** ARCHITECTURE §6.4, §18.2, §5.1–§5.2.

**Do — 16 GB.**

1. Ten-minute viability check *before* any integration: a throwaway activity that
   loads the `.litertlm`, sends one image plus "What is in front of me?", and
   prints the answer, peak memory, and time to first token.
   ```kotlin
   implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.0")
   ```
   ```kotlin
   val engineConfig = EngineConfig(
       modelPath = "/sdcard/Android/data/com.drishti.app.debug/files/models/gemma-4-E4B-it.litertlm",
       backend = Backend.CPU(),
       visionBackend = Backend.GPU(),
   )
   ```
   If the bundle turns out to be text-only, stop here and use rung 3.
2. Integrate as **Class B**: free-memory check with an 800 MB margin → load → one
   inference → explicit close → **verify reclaimed memory** → then return.
3. Deterministic timeout, start at 15 s. Cancellable. Never concurrent with a
   walk frame in flight.

**Do — 12 GB.** Enable the detector-derived scene summary (rung 3), and route
compositional target requests to the laptop `/api/v1/vlm/locate`, with
`confidence: null` preserved.

**Acceptance.** Scene answer returns; `dumpsys meminfo` shows the footprint
returning to baseline after unload; a `STOP` during generation interrupts the
answer; walk guidance never pauses without saying so.

**Commit.** `R8: on-demand scene answering, Class B with verified unload`

---

# Part D — operating it

## 17. Running it on the phone

### 17.1 Install

```bash
cd /c/drishti-edge/apps/android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug package is **`com.drishti.app.debug`** — the suffix matters for every
`adb shell` command and for the model directory path.

Launch it from the launcher, or:

```bash
adb shell monkey -p com.drishti.app.debug -c android.intent.category.LAUNCHER 1
```

### 17.2 First launch — grant these

| Permission | Why | If denied |
|---|---|---|
| **Camera** | The entire product | Nothing works |
| **Microphone** | Spoken target requests in Ask | Ask falls back to typed entry |
| **Notifications** | The walk foreground service posts one | The service may be killed in the background |

Grant them up front rather than mid-demo:

```bash
adb shell pm grant com.drishti.app.debug android.permission.CAMERA && adb shell pm grant com.drishti.app.debug android.permission.RECORD_AUDIO && adb shell pm grant com.drishti.app.debug android.permission.POST_NOTIFICATIONS
```

### 17.3 The gesture map — memorise the first three

The whole screen is one target; there is nothing to aim at. This is the existing,
implemented set.

**Ready screen**

| Gesture | Action |
|---|---|
| **Double-tap anywhere** | Start Walk |
| Long-press | Settings — language, speech rate, risk sensitivity, and the `ON_DEVICE` / `LAN_BACKEND` toggle |

**Walk screen**

| Gesture | Action |
|---|---|
| **Single-tap** | Repeat the last spoken guidance |
| **Long-press** | Ask — a spoken question, or a target to find |
| **Double-tap** | Stop Walk and exit |
| Two-finger swipe right, or volume-up | Explore — read a sign (OCR) |
| Two-finger tap | Blank the screen; audio keeps running |
| Triple-tap | Cancel the current alert / re-centre |
| Three-finger tap, or two-finger swipe up | Begin a hazard report (double-tap confirms) |

Every gesture gives an immediate acknowledgement, and all of them are exposed as
TalkBack custom actions.

### 17.4 Watching it work

```bash
adb logcat -c && adb logcat --pid=$(adb shell pidof -s com.drishti.app.debug) -v brief
```

Narrow to the stages that matter:

```bash
adb logcat --pid=$(adb shell pidof -s com.drishti.app.debug) -s DrishtiInfer:V DrishtiRisk:V DrishtiGuidance:V
```

Memory, any time:

```bash
adb shell dumpsys meminfo com.drishti.app.debug | head -20
```

Thermal status, any time:

```bash
adb shell dumpsys thermalservice | grep -i "Temperature\|status" | head
```

### 17.5 The two-minute smoke test, before every demo

1. Launch, double-tap, hear Walk Mode start.
2. Point at a chair in the centre — expect `CAUTION` or a movement cue, with a
   box on the chair.
3. Point at a blank wall — expect `STOP`, wall or dead end.
4. Cover the lens — expect `PAUSE_UNCLEAR`, **never** a direction.
5. Check the diagnostics panel reads the accelerator you intend to claim.
6. Kill the laptop backend; confirm nothing on the phone changes.

If step 4 gives a confident direction, **do not demo.** That is the one failure
that is worse than not demoing.

---

## 18. The demo script

Seven minutes, in this order. It front-loads what is both true and hard.

**1. The claim, in one sentence (20 s).**
"Everything you're about to see runs on this phone. No laptop, no cloud, no
network in the guidance loop."

**2. Prove it before showing it (40 s).**
Show the diagnostics panel: accelerator in use, inference milliseconds, rolling
FPS, resident memory, thermal status. Then power off the laptop backend, or pull
it off the network, in front of them. Keep walking. Nothing changes.

> This ordering matters. Proving independence *first* means every later scene is
> read as on-device. Proving it last reads as a rescue.

**3. The clear corridor (40 s).**
Walk. `CLEAR`, safe floor polygon, quiet speech. Point out that a system which
talks constantly is a system a blind user turns off.

**4. The chair in the centre (60 s).**
Obstacle appears, risk escalates, `MOVE_LEFT` with spatial audio to the left.
Show the reason code on screen: `CENTRE_BLOCKED_CLEARER_SIDE`. Say that the side
was not chosen because it scored lower — it was chosen because it had enough
visible floor, no wall, and beat the other side by a margin.

**5. The wall, and then the stairs (60 s).**
`WALL_OR_DEAD_END_AHEAD`, then `STAIRS_OR_LEVEL_CHANGE_AHEAD`. Mention that both
require stabilization across frames — one flickering mask never announces stairs
to someone walking.

**6. Cover the lens (40 s). This is the scene that wins it.**
`PAUSE_UNCLEAR`, spoken as uncertainty. Then say the thing nobody else will say:

> "It doesn't know. For a user who can't check the phone's work, a confident
> wrong answer is the most dangerous output this system could produce, so
> uncertainty is a first-class answer here, not a failure."

**7. Find something (50 s).**
Ask for a bag or a bottle seen earlier in the walk. It locks from memory and
guides. Note that no vision-language model ran — the object was already in the
detector's landmark memory from the same pass that keeps the user safe.

**8. The dashboard (40 s).**
Switch to the laptop. Hazard reports, recurrence, accessibility scoring. Then
say it plainly: this is a coordinator view, it never touched the walking loop,
and you already saw the phone work without it.

**9. Close (20 s).**
"It's an assistive prototype. It doesn't replace a cane, a guide dog, or
training. It never says a crossing is safe, and it never gives a distance in
metres, because one camera cannot measure one."

**Have ready, unspoken unless asked:** the golden-vector parity run, the
five-fixture indoor replay, the 20-minute soak numbers, and the commit hash of
the APK on the phone.

---

## 19. Failure playbook

Sorted by when it will happen to you.

| Symptom | Most likely cause | Fix |
|---|---|---|
| `INSTALL_FAILED_USER_RESTRICTED` | OriginOS blocks USB installs | Developer options → **Install via USB** on; some builds also need "verify apps over USB" off |
| App freezes ~5 s after Walk starts | A leaked `ImageProxy` on some branch | Every path, including exceptions, must `close()`. Check the new inference branch first |
| Boxes offset by a constant | Letterbox / rotation applied in the wrong order | Un-letterbox **then** normalize against oriented dimensions (ARCHITECTURE §10.2). Run the `(0,0,1,1)` test |
| Boxes nonsense, confidences absurd | Quantized output never dequantized | Apply the output tensor's scale and zero-point before decoding |
| Model loads but runs on CPU | Asset compressed, or model is float on an HTP that needs quantized | `noCompress` for `tflite`/`onnx`; use the W8A8/W8A16 build |
| Context binary fails to load | QAIRT version mismatch, host vs device | Ship the plain quantized model and compile on device at first launch (§5.3) |
| Everything is `PAUSE_UNCLEAR` indoors | Segmentation not running, or mask misaligned, or normalisation wrong | Check the diagnostics panel for segmenter state; run the fixture replay (R5) |
| Side cues flicker left/right | Persistence or hysteresis not ported | R3d. Expect `DIRECTION_CHANGE_PENDING` between changes; if you never see it, the pending branch is missing |
| It talks constantly | Speech cooldown dropped in the port | 3.0 s, bypassed only by `CRITICAL` |
| FPS fine cold, poor after 15 min | Thermal throttling | §17.3 ladder; stop charging; drop segmentation cadence; lower brightness |
| Process dies mid-walk | Low-memory killer | Check a Class B model actually unloaded; verify the 800 MB margin; close background apps |
| Office Kit drops when installing | USB mode change during `adb install` | Prefer wireless debugging for installs while Remote PC is active |
| Dashboard shows phone inference as laptop VRAM | Execution owner not sent | R7, Appendix A amendment 2 |

### The three stop-and-think moments

1. **A golden vector fails and the fix is "adjust the threshold."** It is not.
   Thresholds are tuned after parity, against measurements, never during a port.
2. **Segmentation will not pass R5 and someone suggests shipping the wall and
   stairs claims anyway.** That is a §3.1 violation. Drop the claim, keep the
   build.
3. **Hour 20, someone suggests defaulting unknown surface to walkable so the
   demo looks cleaner.** That manufactures confidence the model did not express,
   and it is the exact failure that walks someone into a wall.

---

## Appendix A — command reference

Everything in one place, in the order you will need it.

**Setup**
```bash
cd /c/drishti-edge/apps/android && ./gradlew assembleDebug
```
```bash
cd /c/drishti-edge/entire-old-codebase && py -3.11 -m venv .venv && ./.venv/Scripts/python.exe -m pip install -r backend/requirements.txt "ultralytics>=8.3" onnx onnxruntime
```

**Device facts**
```bash
adb devices -l && adb shell "cat /proc/meminfo | head -2; getprop ro.product.model; getprop ro.soc.model"
```

**Models**
```bash
curl -L -o /c/drishti-edge/entire-old-codebase/models/detector/yolo11n.pt https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt
```
```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/yolo.exe export model=models/detector/yolo11n.pt format=tflite int8=True imgsz=640 data=coco8.yaml
```
```bash
cd /c/drishti-edge/apps/android/app/src/main/assets/models && curl -L -O https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-tflite-w8a8.zip && unzip -o segformer_base-tflite-w8a8.zip && rm segformer_base-tflite-w8a8.zip
```

**Build, install, run**
```bash
cd /c/drishti-edge/apps/android && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk
```
```bash
adb shell pm grant com.drishti.app.debug android.permission.CAMERA && adb shell pm grant com.drishti.app.debug android.permission.RECORD_AUDIO && adb shell pm grant com.drishti.app.debug android.permission.POST_NOTIFICATIONS
```

**Tests**
```bash
cd /c/drishti-edge/apps/android && ./gradlew testDebugUnitTest
```
```bash
cd /c/drishti-edge && npm test && npm run typecheck
```
```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m pytest backend/tests -q --ignore=backend/tests/integration
```

**Observe**
```bash
adb logcat --pid=$(adb shell pidof -s com.drishti.app.debug) -v brief
```
```bash
adb shell dumpsys meminfo com.drishti.app.debug | head -20
```

**The LAN fallback backend**
```bash
cd /c/drishti-edge/entire-old-codebase && ./.venv/Scripts/python.exe -m uvicorn app.main:app --app-dir backend --host 0.0.0.0 --port 8000
```
```bash
cd /c/drishti-edge && npm run dev --workspace apps/dashboard
```

---

## Appendix B — file map

What exists, what you add, what you must not touch.

**Do not touch — it is already correct**

| Path | Why |
|---|---|
| `ui/PreviewTransform.kt` | The preview transform is solved. Regressing it breaks every overlay |
| `walk/CameraFramePipeline.kt` | CameraX use cases, `KEEP_ONLY_LATEST`, YUV output, proxy ownership |
| `walk/FrameFreshnessGate.kt` | Now guards against processing overrun instead of network lag |
| `feedback/*` | Speech, spatial audio, sonar, audio focus, gyro steering |
| `ui/gestures/WalkGestures.kt` | The zero-look gesture set |

**Add**

| Path | Card |
|---|---|
| `infer/OnDeviceDetector.kt`, `LiteRtDetector.kt`, `AcceleratorScheduler.kt`, `Preprocess.kt`, `PostProcess.kt` | R2 |
| `infer/Segmenter.kt`, `MaskToPolygons.kt` | R5 |
| `perception/Canonicalizer.kt` | R2 |
| `perception/Tracker.kt`, `LandmarkMemory.kt` | R3a, R6 |
| `spatial/Proximity.kt`, `Corridor.kt`, `Surfaces.kt` | R3a, R3b, R5 |
| `risk/Scoring.kt`, `Rules.kt`, `AlertStateMachine.kt` | R3c, R3d |
| `guidance/TargetGuidance.kt` | R6 |
| `telemetry/TelemetryQueue.kt`, `CoordinatorClient.kt` | R7 |
| `app/src/test/resources/golden/*.json` | R0 |
| `app/src/main/assets/models/*.tflite` | §4 |

**Edit carefully**

| Path | What changes |
|---|---|
| `walk/WalkController.kt` | Calls the seam instead of the API; drives feedback from the in-process guidance object |
| `di/AppContainer.kt` | Constructs the chosen `InferenceSource` |
| `settings/SettingsStore.kt`, `ui/SettingsScreen.kt` | The `ON_DEVICE` / `LAN_BACKEND` toggle |
| `net/*` | Untouched in behaviour; now reached only through `LanBackendInference` |

**Python reference — read, do not port by eye**

| Path | Ported by |
|---|---|
| `backend/app/perception/detector.py` | R2 |
| `backend/app/perception/landmark_memory.py` | R6 |
| `backend/app/spatial/corridor.py`, `proximity.py`, `surfaces.py` | R3a, R3b |
| `backend/app/risk/scoring.py`, `rules.py`, `state_machine.py` | R3c, R3d |
| `backend/app/guidance/target_guidance.py` | R6 |
| `backend/app/config.py` | Every constant in the build |

---

## Appendix C — verified links and checksums

Checked on 12 September 2026. The S3 asset URLs were confirmed to return HTTP 200
and the Maven versions were read from the repositories directly.

**Model sources**

| Asset | Source | Verification |
|---|---|---|
| YOLO11n weights | `https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt` | SHA-256 `0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1` |
| SegFormer-B0 ADE20K, TFLite W8A8 | `https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-tflite-w8a8.zip` | HTTP 200, 3.90 MB |
| SegFormer-B0 ADE20K, TFLite float | `…/segformer_base-tflite-float.zip` | HTTP 200, 14.4 MB |
| SegFormer-B0 ADE20K, ONNX W8A16 | `…/segformer_base-onnx-w8a16.zip` | HTTP 200, 4.57 MB |
| SegFormer-B0 ADE20K, QNN DLC W8A16 | `…/segformer_base-qnn_dlc-w8a16.zip` | HTTP 200, 4.57 MB |
| PC reference segmenter | `nvidia/segformer-b0-finetuned-ade-512-512` | `model.safetensors` SHA-256 `6ae39addd01de6b1b8bde2cf677d43a5cd733424b8d186de3f95d1c51fee23f9` |

**Dependency versions, read from the repositories**

| Artifact | Latest | Repository |
|---|---|---|
| `com.google.ai.edge.litert:litert` | `2.2.0` | Google Maven |
| `com.google.ai.edge.litertlm:litertlm-android` | `0.17.0` | Google Maven |
| `com.google.mlkit:text-recognition` | `16.0.1` | Google Maven |
| `com.microsoft.onnxruntime:onnxruntime-android-qnn` | `1.29.0` | Maven Central |

**Documentation**

- Ultralytics QNN export, HTP target table including **v81 = Snapdragon 8 Elite Gen 5** — <https://docs.ultralytics.com/integrations/qnn>
- LiteRT NPU acceleration, `CompiledModel`, API 31+ — <https://developers.google.com/edge/litert/next/npu>
- LiteRT-LM on Android, `EngineConfig`, vision backend — <https://developers.google.com/edge/litert-lm/android>
- ONNX Runtime QNN execution provider, session options, quantization requirement — <https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html>
- Qualcomm AI Hub Segformer-Base (ADE20K, 150 classes, 512×512, 8 Elite Gen 5 listed) — <https://aihub.qualcomm.com/models/segformer_base>
- Qualcomm AI Hub YOLOv11-Detection — <https://aihub.qualcomm.com/models/yolov11_det>
- Gemma on LiteRT-LM, `.litertlm` bundles — <https://huggingface.co/litert-community>

> Every latency, memory, and accelerator claim in a pitch must come from **your**
> measurement on **this** loaner, not from any page above. Those pages establish
> that a path exists. They do not establish what it does on an iQOO 15 running a
> camera, an audio stack, and a Compose UI at the same time.

---

## Appendix D — what to record for every measurement

One row per measurement, in a file you keep as you go. Without it, a good number
cannot be attributed to the code that produced it.

| Field | Example |
|---|---|
| Git commit | `722dcde` |
| APK SHA-256 | `sha256sum app/build/outputs/apk/debug/app-debug.apk` |
| Model file + SHA-256 | `yolo11n_full_integer_quant.tflite`, `…` |
| Runtime + version | LiteRT `2.2.0`, or ORT `1.29.0` + QAIRT `x.y.z` |
| Accelerator **reported by the runtime** | `NPU` — not inferred from speed |
| Device serial + RAM variant | `adb devices -l`, `MemTotal` |
| Office Kit mirroring active? | yes / no |
| Charging? | yes / no |
| Thermal status at start and end | `NONE` → `MODERATE` |
| What was measured | camera-to-guidance p50 / p95 over 10 min |
| Result | `74 ms / 118 ms` |

---

## A note on `docs/DECISIONS.md`

ARCHITECTURE Appendix A requires contract amendments to be recorded in
`docs/DECISIONS.md` before code depends on them — and that file does not exist in
this repository. It exists only in `entire-old-codebase/docs/`, which is
untracked.

Before R7 changes `packages/contracts`, create `docs/DECISIONS.md` here and
record the seven amendments as accepted decisions with dates. It takes ten
minutes and it is the difference between a contract change and an undocumented
breakage.
