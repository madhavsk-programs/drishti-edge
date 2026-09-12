# DRISHTI Edge — Build Plan

> **Status:** execution plan for the iQOO Hackathon Chennai City Battle,
> 12–13 September 2026.
> **Scope:** everything between the laptop as it is right now and a working
> on-device demo on the loaner iQOO 15.
> **Subordinate to:** [`ARCHITECTURE.md`](ARCHITECTURE.md) and
> [`docs/SAFETY_RULES.md`](docs/SAFETY_RULES.md). Where this document and the
> safety contract disagree, the safety contract wins and this document is wrong.
> **Supersedes:** `ARCHITECTURE.md` §6.2, §6.4 and §7.2 model/runtime rungs,
> for the measured reasons recorded in §2 and §3 below.

---

## CURRENT DEVICE STATE — read this first

Everything below was **executed and verified**, not planned. The app runs the
whole walking loop on iQOO 15 hardware with no backend and no network. The
original 16 GB loaner established the end-to-end result; the current 12 GB test
phone established the portable NPU path on the smaller production variant.

### Working on the loaner, measured

| Item | State |
|---|---|
| **Walking loop, end to end** | **On-device.** Detection → tracking → corridors → risk → state machine → speech/haptics/overlay. A warmed live frame on the 12 GB phone measured **57.96 ms total** |
| Detection | YOLO11n portable w8a16 QDQ, **guarded NPU rung**, live in the app. Real-fixture probe: **3.30 ms NPU vs 27.35 ms CPU** |
| Segmentation | SegFormer-B0 ADE20K, every 3rd frame, **guarded NPU rung**. Probe: **11.33 ms NPU vs 150.25 ms CPU** on a public fixture |
| Guidance | Reaches reasoned verdicts — `CENTRE_BLOCKED_DIRECTION_UNCLEAR` with left walkable and centre blocked, not a generic pause |
| Network in the walk path | **None.** `api.analyze`, the multipart assembly and the retry loop are deleted, not toggled |
| Tests | **56 unit tests + 1 on-device OCR test, 0 failures** |

### Cards complete

| Card | What landed |
|---|---|
| **A0** | `scripts/export_golden.py` → 89 golden cases in 5 files, exported from the production Python modules |
| **A1–A4** | `perception/`, `spatial/`, `risk/`, `config/` in Kotlin — canonicalization, tracker, corridors, proximity, risk score, `selectAction` cascade, `AlertStateMachine`. All pinned by the golden vectors |
| **A3 surfaces** | `Surfaces.kt`, `SegFormerSegmenter.kt`, `SurfaceEvidenceBuilder.kt` |
| **A6** | `inference/` seam + `LocalWalkPipeline` + the `WalkController` rewire |
| **A8** | Bundled ML Kit OCR, local confidence/route parsing, and uninterrupted walking safety during a read. Verified on the 12 GB phone with `BUS 42A` |

### NEXT AGENT — start here: real on-device VLM

The user explicitly rejected making detector/OCR templates stand in for scene
understanding. **Let the VLM own Scene Mode and semantic text comprehension.**
Do not add testing buttons; when a physical interaction must be checked, install
the build and ask the user to perform the existing gesture.

Current boundary:

- Explore/Read is complete and local (A8). ML Kit reads text; it is not a
  language model and must not be described as comprehension.
- `SceneDescriber` and `TargetLocator` still use the old `/vlm/query` and
  `/vlm/locate` endpoints. With no coordinator they say `Connection lost`.
  This is known unfinished work, not an OCR failure.
- A detector-derived Scene experiment was built and tested locally, then
  deliberately removed before commit at the user's direction. Do not restore it.
- The current 12 GB iQOO 15 is sufficient for implementation and the 450M/1.6B
  bring-up. Reserve the 16 GB phone for final larger-model validation.
- An `sdkmanager` attempt to install NDK `29.0.14206865` and CMake `3.31.6` was
  interrupted before completion; neither directory was present when checked.
  Verify `.android-toolchain/sdk/ndk` and `cmake`, then rerun if absent.
- The aligned `56167d0` debug build is installed on the connected 12 GB phone;
  the four staged YOLO/SegFormer model/config files are present in its external
  files directory. It was not launched after install, per the user's request to
  ask them for physical feature tests rather than adding test-only UI.
- One historical stash remains: `stash@{0}: Windows Android setup before
  b1c425a handoff`. It predates the authoritative remote handoff. **Do not pop it
  wholesale**; inspect individual paths only if something is demonstrably
  missing. The useful Android wrapper and ignore rule are already committed.

Implementation order:

1. Install/verify NDK 29 and CMake. Pin an audited llama.cpp revision; do not use
   an unvetted community AAR.
2. Build arm64-v8a llama.cpp + `libmtmd` with `GGML_NATIVE=OFF`,
   `GGML_CPU_KLEIDIAI=ON`, `GGML_OPENMP=OFF`, `GGML_LLAMAFILE=OFF`, and
   `LLAMA_OPENSSL=OFF`. Add the smallest JNI boundary needed for one image plus
   one prompt.
3. Implement the Class-B lifecycle from `docs/SCENE_MODE_VLM.md`: check free
   memory with the fixed 800 MB margin, load model + mmproj, run exactly one
   inference, close every native object, verify memory reclaim, then return.
   Cancellation/timeout must close deterministically.
4. Bring up `LFM2.5-VL-450M` first on the current 12 GB phone, then switch the
   same interface to the planned `LFM2.5-VL-1.6B` assets. Record URLs, licences,
   SHA-256 values, load time, first answer, repeated answer, peak memory and
   reclaim in `docs/SCENE_MODE_VLM.md`.
5. Only after a fixture proves real image-question answering, replace
   `SceneDescriber.post`. Route semantic questions about recognized text through
   the VLM itself; do not concatenate an OCR template and call it comprehension.
6. Keep the continuous YOLO/SegFormer safety loop isolated. The VLM is one-shot,
   CPU-side on-device inference and is never part of the NPU claim.

Upstream reference verified on 12 September 2026: llama.cpp documents Android
arm64 cross-compilation in `docs/build.md` and multimodal inference through
`libmtmd` / `tools/mtmd/mtmd-cli.cpp`. Re-check the pinned revision's API before
writing JNI because this interface moves quickly.

### Two Android packaging lessons, both paid for in debugging time

Both apply to `:app` and are already committed there. Neither is optional, and
each fails *silently*:

1. **`jniLibs { useLegacyPackaging = true }`.** AGP's default keeps `.so` files
   page-aligned inside the APK and never extracts them to `nativeLibraryDir`.
   QNN `dlopen`s `libQnnHtp.so` by bare name and needs a real file on disk.
   Symptom: `libQnnHtp.so` is in the APK and still "not found".
2. **`<uses-native-library>` for `libcdsprpc.so` / `libadsprpc.so` /
   `libsdsprpc.so`.** Android sandboxes which vendor libraries an app may load.
   Without these the DSP is unreachable and the error names a QNN device
   problem, never a linker one.

### ORT Java cannot make a uint16 tensor — affects every quantized-IO model

`ai.onnxruntime.OnnxJavaType` has **no `UINT16`** constant, so a uint16 input
cannot be constructed at all and `Run` rejects the int16 stand-in:

```
Unexpected input data type. Actual: (tensor(int16)), expected: (tensor(uint16))
```

Qualcomm's SegFormer export declares uint16 IO, so this blocked it on **both**
the CPU and the NPU rung. Fixed offline by
[`tools/segformer_float_io.py`](tools/segformer_float_io.py), which drops the
two boundary quant nodes and promotes the float tensors the network already
computes on. **Lossless** — it removes a round-trip rather than adding one.
Any other Qualcomm AI Hub model with quantized IO will need the same treatment.

### Getting QAIRT: the browser works, `curl` does not

The §3.4 claim that there is no account-free route is **superseded**. The
direct zip URL downloads fine **in a real browser** while `curl` gets a flat
CloudFront **403** regardless of user-agent or referer:

```
https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/2.50.0.260828/v2.50.0.260828.zip
```

Two caveats, both cost time if unknown:

- The download **truncates near the end** — the trailing central directory is
  missing, so `unzip` and Python's `zipfile` both refuse the archive. The file
  data is intact; entries are recoverable by scanning local file headers.
  Everything under `lib/aarch64-android/` extracted with **CRC verified**.
- Navigating the tab elsewhere **kills the transfer**. Start it and leave it.

### Target devices — confirmed

Both devices are **iQOO 15, Snapdragon 8 Elite Gen 5 (SM8850), HTP v81,
Android 16**. The original loaner has 16 GB RAM and measured `availMem`
**7,998 MB** of 15,219 MB in normal use. The current development phone has
12 GB RAM plus 12 GB compressed swap; the probe measured **5,155 MB available
of 11,205 MB**. Swap remains excluded from the model budget.

### NPU status — both resident models resolved

The EPContext version blocker is gone. Both portable QDQ graphs compile against
the transitive 2.42 runtime on the phone and create with CPU fallback disabled.
The model rewrites and evidence are characterised precisely in
[§3.5](#35-the-npu-on-real-silicon--where-it-actually-stands).

### Running it

```bash
export JAVA_HOME=/c/Users/yasha/tools/jdk-21.0.12.1+1 && export ANDROID_HOME=/c/Users/yasha/AppData/Local/Android/Sdk
```

```bash
cd apps/android && ./gradlew :app:testDebugUnitTest :app:installDebug
```

Models load from the app's external files dir, so swapping one needs no
rebuild. **Note the `.debug` suffix** on debug builds:

```bash
adb push models/staging/yolo11n_qdq.onnx models/staging/yolo11n_fp32_nchw.onnx models/staging/segformer_float.onnx models/staging/ade20k_config.json /sdcard/Android/data/com.drishti.app.debug/files/
```

```bash
adb logcat -s WalkController:* OrtYoloDetector:* SegFormer:* LocalWalkPipeline:*
```

---

## Table of contents

- [0. How to read this document](#0-how-to-read-this-document)
- [1. The timeline](#1-the-timeline)
- [2. Model verification verdict](#2-model-verification-verdict)
- [3. The runtime decision](#3-the-runtime-decision)
  - [**3.4 The QNN backend libraries are NOT in the AAR**](#34-the-qnn-backend-libraries-are-not-in-the-aar--verified)
  - [**3.5 The NPU on real silicon — where it stands**](#35-the-npu-on-real-silicon--where-it-actually-stands)
- [4. Device variants — 12 GB and 16 GB measured](#4-device-variants--12-gb-and-16-gb-measured)
- [5. The playground device](#5-the-playground-device)
- [6. Office Kit and proving on-device AI](#6-office-kit-and-proving-on-device-ai)
- [PART 0 — Laptop preparation (T−14 h → T−0)](#part-0--laptop-preparation-t14-h--t0)
- [PART 1 — Agent build manual](#part-1--agent-build-manual)
- [PART 2 — Loaner bring-up](#part-2--loaner-bring-up)
- [PART 3 — Operating DRISHTI on the phone](#part-3--operating-drishti-on-the-phone)
- [PART 4 — Measurement and evidence](#part-4--measurement-and-evidence)
- [PART 5 — Demo runbook](#part-5--demo-runbook)
- [PART 6 — Triage and cut lines](#part-6--triage-and-cut-lines)
- [Appendix A — model manifest](#appendix-a--model-manifest)
- [Appendix B — command cheat sheet](#appendix-b--command-cheat-sheet)
- [Appendix C — troubleshooting](#appendix-c--troubleshooting)

---

## 0. How to read this document

Three audiences, marked at every section heading.

| Marker | Audience | What it means |
|---|---|---|
| **[HUMAN]** | You, at the keyboard | Run these commands yourself. An agent cannot install a JDK, accept an SDK licence, plug in a phone, or walk a corridor. |
| **[AGENT]** | The coding agent | A task card with preconditions, files, acceptance test, and rollback. Hand it over verbatim. |
| **[OPERATOR]** | Whoever holds the phone | Runtime procedure. Also the demo script. |

One person is executing this. Every section says how long it takes and what
happens if it is skipped.

### 0.1 The three statement kinds, carried from `ARCHITECTURE.md` §1.2

- **MUST** — hard requirement. Violating it breaks safety or breaks a contract.
- **SHOULD** — intended approach. Deviate only with a stated reason.
- **MEASURE** — unknown until measured on the loaner. Every one has a decision
  rule attached.

### 0.2 The rule that governs the whole plan

> **Nothing in Part 0 requires the phone.** The loaner arrives at T−0. Every hour
> spent before then on work that *could* have been done without a device is an
> hour stolen from the 30 h window, and the 30 h window is the only time the
> device exists. Part 0 is therefore aggressive on purpose: it takes the entire
> deterministic Kotlin port — which is pure logic, JVM-testable, and the largest
> single body of work in the migration — out of the event window.

---

## 1. The timeline

**T−14 h → T−0:** laptop only. No phone. 
**T−0 → T+30 h:** event window. Phone in hand. 
**Total: 44 h, solo.**

### 1.1 Part 0 — laptop preparation, 14 h

| Slot | Block | Hours | Blocking? |
|---|---|---|---|
| P0.1 | Toolchain: JDK, Android SDK, Gradle repair, clean build | 1.5 | **Yes — everything** |
| P0.2 | Python environments and golden-vector export | 1.0 | Yes — R3 parity |
| P0.3 | Model acquisition, YOLO11 QNN export, staging | 1.5 | Yes — R1/R2 |
| P0.5 | **The deterministic Kotlin port** | 3.5 | **Yes — R3** |
| P0.6 | `OnDeviceDetector` seam and ORT QNN wiring | 1.5 | Yes — R2 |
| **P0.8** | **Prove the NPU path on the playground device (§5)** | **2.0** | **Yes — retires the R1 and R5 gates early** |
| P0.4 | Coordinator restore and narrow | 1.0 | No — R7 only. **Cut this first** |
| P0.7 | Pre-flight, pack, evidence log skeleton | 0.5 | Yes |
| — | **Sleep** | **4.0** | **Yes. Non-negotiable.** |

P0.3's downloads run in the background while P0.1 and P0.2 proceed. **P0.8 is
ordered ahead of P0.4 deliberately** — a proven NPU path is worth more than a
dashboard, and P0.4 is the first thing cut if anything overruns.

> **MUST: sleep the 4 h.** A solo operator who arrives at T−0 having been awake
> for 20 h will lose more than 4 h to mistakes inside the event window. This is
> not a wellbeing note; it is a schedule optimisation.

### 1.2 Part 1 — event window, 30 h

| Slot | Block | Hours | Gate | Cut line |
|---|---|---|---|---|
| E1 | Loaner triage — §24 unknowns 1–4 | 0.75 | **HARD** | If unknown 2 fails, escalate to organisers immediately |
| E2 | NPU proof (R1) — **re-run the P0.8 probe on the loaner** | 0.5 | **HARD** | Fall to GPU EP, then CPU EP; say so plainly |
| E3 | Wire the seam live (R2) | 2.0 | Hard | — |
| E4 | On-device parity and overlay (R3, device half) | 2.0 | Hard | **Demo is viable from here** |
| E5 | Output layer (R4) | 1.5 | Hard | — |
| E6 | Segmentation (R5) — wire in the model P0.8.4 already proved | 1.0 | Soft | Omit, announce degradation |
| — | **Sleep** | **5.0** | — | — |
| E7 | Ask → Lock → Guide (R6) | 2.5 | Soft | Cut Find |
| E8 | Coordinator and dashboard (R7) | 1.5 | Soft | Cut dashboard |
| E9 | Soak and the §23.2 functional checks | 2.0 | **HARD** | Never cut |
| E10 | VLM stretch (R8) — **16 GB only** | 2.0 | Optional | Cut first |
| E11 | Freeze, rehearse, evidence pack | 3.0 | **HARD** | Never cut |
| — | Buffer | **6.25** | — | P0.8 returned 2.5 h. It will still be consumed |

> **The feature freeze is at T+27 h.** E11 is three hours and it is not optional.
> A demo that works and cannot be explained scores worse than a smaller demo with
> a measured evidence log. After T+27 h the only permitted commits are
> reverts.

### 1.3 The "what ships if we stop here" markers

| Stopped after | What you can demo |
|---|---|
| E2 | A phone running YOLO11 on the Hexagon NPU, with measured proof it is the NPU. Honest, narrow, real. |
| E4 | **The product.** Camera → NPU detection → corridor reasoning → spoken guidance, no laptop. This is the demo. |
| E6 | The product with floor/wall/stairs semantics. |
| E7 | The product plus Find. |
| E9 | The product, verified, with a soak curve and a coordinator-off proof. |

**E4 is the line that matters.** Everything after it is width, not existence.

---

## 2. Model verification verdict

`ARCHITECTURE.md` §6 was written defensively, before the published figures were
checked. They have now been checked, against Qualcomm's own model cards, for the
exact target chipset. **The models are materially better than the architecture
assumes, and the primary risk it was hedging against does not exist.**

### 2.1 Detection — YOLO11n

| Runtime | Precision | Snapdragon 8 Elite Gen 5 | Peak memory | Compute unit |
|---|---|---|---|---|
| ONNX | float | 3.00 ms | 0 – 221 MB | NPU |
| **ONNX** | **w8a16** | **2.268 ms** | **0 – 82 MB** | **NPU** |
| QNN DLC | w8a16 | 1.747 ms | 2 – 186 MB | NPU |
| TFLite | w8a8 | 0.732 ms | 0 – 40 MB | NPU |

2.64 M parameters, 640×640 input, 3.30 MB at w8a16. Source:
[qualcomm/YOLOv11-Detection](https://huggingface.co/qualcomm/YOLOv11-Detection).

**Verdict: §6.1 rung 1 holds, comfortably.** There is no reason to consider
YOLOv8 or YOLOX. `ARCHITECTURE.md` §8.4's "target < 20 ms" is met by a factor of
nine.

### 2.2 Segmentation — SegFormer-B0 ADE20K

This is the finding that changes the plan.

| Runtime | Precision | Snapdragon 8 Elite Gen 5 | Peak memory | Compute unit |
|---|---|---|---|---|
| ONNX | float | 13.265 ms | 21 – 213 MB | NPU |
| **ONNX** | **w8a16** | **5.662 ms** | **13 – 217 MB** | **NPU** |
| ONNX | w8a8 | 1.910 ms | 0 – 193 MB | NPU |
| QNN DLC | float | 13.684 ms | 3 – 196 MB | NPU |

3.75 M parameters, 512×512 input, **150 output classes**, checkpoint
**`nvidia/segformer-b0-finetuned-ade-512-512`** — byte-for-byte the same
checkpoint [`backend/app/perception/segmenter.py`](entire-old-codebase/backend/app/perception/segmenter.py)
loads today. Source: [qualcomm/Segformer-Base](https://huggingface.co/qualcomm/Segformer-Base).

**Verdict: §6.2's core fear is unfounded.** The architecture's worry was that
preserving ADE20K indoor semantics would force a substitution that "silently
deletes those capabilities while appearing to work." No substitution is needed.
The same weights, the same 150 classes, the same `id2label` ordering, running on
the NPU at 5.7 ms.

> **Correction to `ARCHITECTURE.md` §6.2.** The gate stands — output ordering,
> preprocessing parity and the coordinate transform must still be proved (P0.3.4
> and E6). But the expected outcome inverts. The architecture says "if the gate
> fails, most frames will resolve to `PAUSE_UNCLEAR`, which is the correct
> behaviour." That is still the correct behaviour and still the fallback. It is
> no longer the likely outcome, and the build should not be scheduled as though
> it is.

> **Correction to `ARCHITECTURE.md` §17.3.** Detection plus segmentation is
> **~8 ms of NPU time per frame**. The thermal ladder's "segmentation every 4th
> frame" and "segmentation off" rungs remain implemented as responses to measured
> thermal status, but they are not expected to fire, and segmentation cadence
> **SHOULD start at every frame**, not every 2nd. The real frame budget will be
> spent on YUV conversion, letterboxing, and NMS on the CPU — not on the
> accelerator. Profile there.

### 2.3 The "not supported on any chipset" banner is a UI artifact

`ARCHITECTURE.md` §6.4 correctly caught Qwen3-VL-4B's AI Hub page listing
8 Elite Gen 5 as supported while also saying *"This model is currently not
supported on any Mobile chipset."* That contradiction is not specific to the 4B.
The identical banner appears on
[`segformer_base`](https://aihub.qualcomm.com/models/segformer_base) and
[`qwen3_vl_2b_instruct`](https://aihub.qualcomm.com/models/qwen3_vl_2b_instruct)
while their performance tables underneath list Snapdragon 8 Elite Gen 5 NPU rows
with real millisecond figures.

> **MUST: read the performance table, never the banner.** The banner reflects a
> default device filter on the page, not model support. Treating it as evidence
> is what nearly cost this build its segmentation capability.

### 2.4 Vision-language models

The architecture placed Qwen3-VL-2B at rung 1 on the assumption that the 2B was
in better shape than the 4B. It is not — it carries the same banner, and neither
has a mobile-runtime deployment story that is reachable in an event window.

**Verdict: no VLM runs on the NPU in this build.** The honest options are
LiteRT-LM or MediaPipe `tasks-genai` with a `.litertlm` / `.task` model on
**CPU or GPU**. That is genuinely on-device and genuinely offline, and it is
**not** an NPU claim. See §4 for the 16 GB gating.

> **MUST NOT** describe a CPU-hosted phone VLM as NPU inference, or let it near
> the walking loop. `ARCHITECTURE.md` §18.2's prohibition is unchanged.

### 2.5 OCR — the ladder inverts

| Rung | Path | Time to working | NPU? |
|---|---|---|---|
| **1** | **ML Kit text recognition** | **~30 min** | No |
| 2 | EasyOCR w8a8 via ORT QNN — [public assets](https://huggingface.co/qualcomm/EasyOCR), detector 5.5 ms + recognizer 8.2 ms on NPU | ~4 h | Yes |
| 3 | Cut Explore Mode | 0 | — |

`ARCHITECTURE.md` §6.3 ranks ML Kit at rung 3 and NexaSDK at rung 1. **NexaSDK is
dropped from this plan entirely** — its documented 16 GB Android floor makes it a
coin-flip on the loaner, and every capability it offered is available through a
path that does not gamble. Explore Mode is a SHOULD; it gets 30 minutes, not
four hours, unless E1–E9 finish early.

EasyOCR's recognizer shows a **810 MB peak** on the NPU path. That is a Class B
model under `ARCHITECTURE.md` §5.1 and the free-memory floor applies.

### 2.6 Does this answer the demo?

The question asked was whether the planned models are good enough to reproduce,
on the phone, what the laptop pipeline already does indoors. Point by point:

| Demo beat | Depends on | Verdict |
|---|---|---|
| Walk a corridor, hear obstacle guidance | YOLO11n on NPU | **Yes.** 2.27 ms, same labels, same canonicalization, same 19-class audited view. |
| "Wall ahead", "stairs ahead", floor polygon | SegFormer-B0 ADE20K on NPU | **Yes.** Identical checkpoint and 150 classes. The rewritten graph creates with CPU fallback disabled and measures 11.33 ms on the current phone. |
| Obstacle centre → "move left" | Corridor geometry + risk engine, pure Kotlin | **Yes,** and it is a port under golden-vector parity, not a reimplementation. |
| Camera covered → `PAUSE_UNCLEAR` | Risk cascade, pure Kotlin | **Yes.** |
| Find the bottle I walked past | Landmark memory from the full-COCO view | **Yes,** no extra inference, no VLM. |
| Read that sign | ML Kit | **Yes,** on-device, not NPU. Say so. |
| "What's in front of me?" | Detection-derived summary | **Yes,** composed from detections. A real VLM is 16 GB stretch only. |
| Laptop powered off, walk continues | Architecture | **Yes.** Structurally — there is no client left in the loop. |

The one capability the laptop has that the phone will not have is Moondream2's
open-vocabulary locate. §6.4 rung 3 (detection-derived) plus landmark memory
covers the demo beat without it.

> **Unchanged from `ARCHITECTURE.md` §6.2 and `docs/SAFETY_RULES.md`:** `door` is
> not a COCO class and remains unreachable from the detector. The system does not
> claim door detection. ADE20K *does* carry a `door` class, so segmentation may
> report a door-shaped non-walkable surface — that is a surface, not a detection,
> and it is never spoken as "door ahead."

---

## 3. The runtime decision

`ARCHITECTURE.md` §7.2 offers Path A (AI Hub + QNN/QAIRT) and Path B (NexaSDK).
Path B is dropped (§2.5). Path A is replaced by something narrower and better
evidenced.

### 3.1 One runtime, one dependency, both models

```
LAPTOP (Part 0)                              PHONE (event window)
─────────────────                            ────────────────────
yolo11n.pt
   │ ultralytics: yolo export format=qnn name=81
   ▼
yolo11n_qnn.onnx  ──────────────┐
  (w8a16, embedded HTP v81      │
   context binary)              │
                                ├──► app/src/main/assets/
segformer_base-onnx-w8a16.zip   │         │
   │ public S3, no account      │         ▼
   ▼                            │   onnxruntime-android-qnn
segformer_base.onnx  ───────────┘   (QNN EP, backend htp)
                                          │
                                          ▼
                                    Hexagon NPU
```

**Why this and not the architecture's Path A:**

1. **No Qualcomm account, no cloud compile job, no device farm.** Ultralytics
   ships a [first-party QNN exporter](https://docs.ultralytics.com/integrations/qnn)
   that targets HTP architecture versions directly. `name="81"` is Snapdragon
   8 Elite Gen 5. Context-binary generation runs on the x64 Windows host and does
   not need a Snapdragon device present.
2. **Qualcomm publishes SegFormer as a plain public S3 zip.** No sign-up. The URL
   is in Appendix A.
3. **One Android dependency covers both models.**
   `com.microsoft.onnxruntime:onnxruntime-android-qnn` bundles the QNN backend
   libraries in the AAR. One session factory, one scheduler, one thing to debug.
4. **It gives a hard NPU proof rather than an inference.** `ARCHITECTURE.md` §7.3
   says "MUST NOT infer NPU execution from speed alone." ONNX Runtime provides
   the session option `session.disable_cpu_ep_fallback = "1"`, which makes a
   partial or total CPU fallback a **session-creation exception** instead of a
   silent slowdown. That is the strongest form of the evidence §7.3 demands.

### 3.2 Version pinning — the one thing that will bite

> **MUST: match the ONNX Runtime Android version to the one the Qualcomm assets
> were built against.** The SegFormer release manifest declares
> `onnx_runtime: 1.27.1` and `qairt: 2.45.0.260326154327`. An
> `onnxruntime-android-qnn` older than 1.27 may fail to load an EPContext model
> built by a newer toolchain, and the error is an opaque session-creation
> failure, not a version complaint.
>
> **Verified against Maven Central's `maven-metadata.xml`** (the Maven *search*
> API is stale and reports 1.22.0 as newest — it is not): published versions run
> to **1.29.0**, with 1.26.0, 1.27.0 and 1.28.0 also available.
>
> **Use 1.29.0.** It is at or above the assets' declared 1.27.1, and a newer ORT
> loads an older EPContext model while the reverse is not true. If SegFormer's
> session fails to create, treat it as a version problem first (Appendix C.4).

### 3.3 The runtime ladder, replacing §7.2

| Rung | Path | Evidence standard | When |
|---|---|---|---|
| **1** | **ORT QNN EP, `htp` backend, `disable_cpu_ep_fallback=1`** | Session creates; logcat shows QNN HTP init; latency matches the published figure within ~3× | Default |
| 2 | ORT QNN EP without the fallback guard | Backend reported per node; latency compared against a CPU EP run | If rung 1 throws on one model but not the other |
| 3 | ORT with the plain ONNX export, QNN compiles at session creation | Slow first load (seconds), then NPU | If the prebuilt context binary is rejected by the device |
| 4 | ORT GPU or NNAPI EP | Report as GPU. Hotter, slower, still on-device | If HTP is unreachable from a third-party app |
| 5 | ORT CPU EP | **Announce as degraded.** Demo only | Last resort |

**MUST NOT skip rungs to save time.** Each rung down costs a specific claim, and
the claim is what is being scored.

### 3.4 The QNN backend libraries are NOT in the AAR — verified

> **SUPERSEDED IN PART by [§3.5](#35-the-npu-on-real-silicon--where-it-actually-stands).**
> The AAR's own payload is exactly as described below — that inspection was
> correct. What it missed is the **dependency graph**: `onnxruntime-android-qnn`
> pulls `qnn-runtime:2.42.0` transitively, which does ship the backend
> libraries AND the v81 skel. Read §3.5 before acting on this section.

`com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0` was downloaded and
opened. Its complete native payload is:

```
jni/arm64-v8a/libonnxruntime.so          23,990,744
jni/arm64-v8a/libonnxruntime4j_jni.so       111,504
```

**That is all.** There is no `libQnnHtp.so`, no `libQnnSystem.so`, no
`libQnnHtpV81Stub.so`, no `libQnnHtpV81Skel.so`. Strings inside
`libonnxruntime.so` confirm it **dlopen's them by name at runtime**:

```
libQnnHtp.so     libQnnSystem.so     libQnnGpu.so
LoadQnnSystemLib "Loading QnnSystem lib"
backend_path     soc_model           htp_arch
ep.context_enable    ep.context_file_path    ep.context_embed_mode
```

**Consequence: adding the Gradle dependency is necessary and not sufficient.**
Without the QAIRT backend libraries packaged into the APK, session creation fails
and — with `disable_cpu_ep_fallback` off — it fails *by silently running on the
CPU*, which is precisely the failure mode `ARCHITECTURE.md` §7.3 warns will "pass
functional testing and then melt the phone twenty hours later."

#### What must be packaged, for HTP v81

Into `app/src/main/jniLibs/arm64-v8a/`:

| Library | Role |
|---|---|
| `libQnnHtp.so` | The HTP backend entry point |
| `libQnnSystem.so` | QNN system interface |
| `libQnnHtpV81Stub.so` | **v81** CPU-side stub — the version must match the SoC |
| `libQnnHtpPrepare.so` | Graph preparation |
| `libQnnCpu.so` | CPU reference backend, for the §6.2 comparison |
| `libQnnHtpV81Skel.so` | **Runs on the DSP.** Needs `ADSP_LIBRARY_PATH` — see below |
| `libqnnhtpv81.cat` | HTP catalogue blob |

> **The `V81` in those names is the same `81` as `yolo export ... name=81` and the
> same `81` as the device's own `libbitml_nsp_81na_skel.so` (§5).** All three must
> agree. A v79 stub on a v81 device fails at session creation.

#### Where they come from

They ship in the **Qualcomm AI Runtime SDK (QAIRT)**, under
`lib/aarch64-android/` and `lib/hexagon-v81/unsigned/`.

> **MEASURE 3.4.1 — obtain QAIRT. Do this before anything else in Part 0.**
>
> **Use [Qualcomm Software Center](https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_Software_Center),
> not Qualcomm Package Manager.** QPM is the route Qualcomm's own ONNX Runtime
> docs point at, and it does **not** work — confirmed on this project's account,
> not merely suspected. Software Center is a desktop installer; install it, then
> pull **Qualcomm AI Runtime SDK (QAIRT)** from its catalogue.
>
> **Target version: 2.50.x.** The exported detector self-reports
> `ep_compatibility_info.QNNExecutionProvider = v2:6:2.50.40:5.50.0:81:0:0:0`,
> so the Android backend libraries should match that QAIRT line.
>
> **CORRECTION — the direct URL does work, in a browser.** The 403 below is a
> `curl`/bot artifact, not an access control. See the handoff block: the same
> URL downloads fine from a real browser tab, and QAIRT **2.50.0.260828** was
> obtained that way with its `lib/aarch64-android/` libraries CRC-verified.
>
> | Attempted | Result |
> |---|---|
> | `softwarecenter.qualcomm.com/api/download/.../Qualcomm_AI_Runtime_Community/...` via **curl** | **HTTP 403** on every version string and header set tried |
> | the same URL in a **real browser tab** | **Works.** Truncates near the end; recover by scanning local file headers |
> | `QAIRT_*.zip` on [qualcomm/qai-appbuilder releases](https://github.com/qualcomm/qai-appbuilder/releases) | Contains `arm64x-windows-msvc` only — **no Android** |
>
> What you need out of the SDK is small — `lib/aarch64-android/` and the
> `hexagon-v81/unsigned/` skel — but there is no supported way to get just those.
>
> **Decision rule:** install Software Center and pull QAIRT during Part 0. There
> is no verified alternative, and discovering that at T+2 h costs the NPU claim.

#### The `Skel` library and `ADSP_LIBRARY_PATH`

`libQnnHtpV81Skel.so` executes on the DSP, not the CPU, so it is not loaded by
the normal linker. The process must point the DSP loader at it:

```kotlin
// Before creating any QNN session.
val libDir = applicationInfo.nativeLibraryDir
Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp", true)
```

> **MUST** set this before the first session, not after. A missing
> `ADSP_LIBRARY_PATH` presents as a generic backend-initialisation failure with
> no mention of the DSP, and it is an hour lost if you have not seen it before.

> Retail devices run the DSP in an **unsigned process domain**, so no signing is
> required. That is what makes this reachable from a third-party app at all — and
> it is the substance of `ARCHITECTURE.md` §24 unknown 4, which P0.8 now answers
> on real silicon before the event.

#### An EPContext model cannot run on the CPU — verified

Attempting to open `yolo11n_qnn.onnx` with the CPU EP fails outright:

```text
NOT_IMPLEMENTED : EPContext node generated by 'QNNExecutionProvider' is not
compatible with any execution provider added to the session.
```

The compiled context binary is executable **only** by the QNN EP. Two
consequences, both of which change how evidence is collected:

1. **§6.2 proof 3 (the NPU-vs-CPU comparison) needs two model files.** You cannot
   toggle backends on one artifact. The NPU rungs use `yolo11n_qnn.onnx`; the CPU
   rung uses the separate `yolo11n_fp32_nchw.onnx`. Pointing the CPU rung at the
   QNN model makes a perfectly healthy setup look broken.
2. **The two files have different tensor layouts.**

   | Artifact | Input | Layout | Runs on |
   |---|---|---|---|
   | `yolo11n_qnn.onnx` | `[1, 640, 640, 3]` | **NHWC** | QNN EP only |
   | `yolo11n_fp32_nchw.onnx` | `[1, 3, 640, 640]` | **NCHW** | CPU / GPU EP |

   Both emit `output0` `[1, 84, 8400]`, so **postprocessing is shared and the
   preprocessing is not**. Whatever loads a model **MUST** read the input shape
   from the session and lay the tensor out accordingly, rather than hardcoding
   either layout — otherwise descending §3.3's ladder silently corrupts every
   frame.

> When quoting the comparison, say what it is: a **w8a16 QNN build on the NPU**
> versus an **fp32 build on the CPU**. That is still the honest and relevant
> number — it is what the product would actually run in each case — but it is not
> one binary on two backends, and claiming otherwise invites a correction you
> cannot make.

#### SegFormer behaves the opposite way — and that is useful

Qualcomm's `segformer_base.onnx` was opened and run. It is **not** an EPContext
model: 1284 nodes, `QuantizeLinear`/`DequantizeLinear`/`MatMul` — a plain **QDQ**
graph. It loads and executes on the **CPU EP** unmodified.

| | `yolo11n_qnn.onnx` | `segformer_base.onnx` |
|---|---|---|
| Form | EPContext (precompiled) | QDQ (portable) |
| Layout | **NHWC** `[1,640,640,3]` | **NCHW** `[1,3,512,512]` |
| dtype | float32 | **uint16** |
| CPU EP | **Cannot load** | Loads and runs |
| First session | Fast — already compiled | **Slower — QNN compiles on-device** |

Two consequences:

1. **SegFormer is the better vehicle for §6.2 proof 3.** One artifact, two
   backends, genuinely apples-to-apples — unlike the YOLO comparison, which is
   necessarily a w8a16 NPU build against an fp32 CPU build. Lead the live toggle
   demo with SegFormer.
2. **Expect a slow first SegFormer session on the phone.** A QDQ graph is
   compiled to a context binary by the QNN EP at session creation. Warm it during
   Walk Mode startup, not at the moment the user asks for guidance — and consider
   `ep.context_enable` + `ep.context_file_path` to cache the compiled binary to
   disk after the first run.

> **MUST NOT** hardcode layout or dtype anywhere in the loader. Across just these
> two models the layout flips **and** the dtype changes. Read both from the
> session's `TensorInfo` every time.

#### Revision to §3.1

§3.1 claims the path needs "no Qualcomm account, no cloud compile job". That is
**correct for the export side** — Ultralytics compiles the context binary locally
and Qualcomm's SegFormer assets are a plain public S3 zip, both verified. It is
**wrong for the runtime side**: the Android backend libraries need a Qualcomm
account. One signup, one download, done once, entirely within Part 0.

---

### 3.5 The NPU on real silicon — where it actually stands

> **This section supersedes §3.4's headline.** §3.4 says the AAR ships no QNN
> backend libraries. That was true of the AAR's own payload and is **false of
> its dependency graph**, which changes the problem completely.

#### Result on 12 September 2026: both portable QDQ models succeed

Option C below was executed. [`tools/export_yolo_qdq.py`](tools/export_yolo_qdq.py)
exports a static, float-IO YOLO11n graph with w8a16 QDQ nodes and **no
`EPContext`**, calibrated on 128 real COCO128 images using the app's exact RGB
letterbox. Artifact SHA-256:
`ec71f1a401dc341293a765f8f3c47749878595e7ea979e1dffeb91b0bce3dac2`.

On the current 12 GB iQOO 15, the session creates with
`session.disable_cpu_ep_fallback=1` and runs at **3.30 ms mean** (guarded QNN
HTP) versus **27.35 ms mean** (plain FP32 CPU). A public COCO fixture produced
five detections on both paths with identical classes, box IoU **0.926–0.991**,
and maximum confidence drift **0.068**. The app then loaded the same artifact
on its guarded NPU rung in live Walk Mode. This proves execution placement and
one integration fixture; it is **not** an mAP or field-safety evaluation.

SegFormer was then resolved with a provider profile. The entire network was one
QNN partition except for two CPU `DequantizeLinear` nodes feeding the final
classifier's constant weight and bias. `tools/segformer_float_io.py` now folds
only those constants and verifies **bit-identical logits and 100% pixel argmax
agreement** against the prior float-IO graph before writing the artifact.

The rewritten graph (SHA-256
`6c31edf490cf7d215d2654d41f0f392dcc79926bdff6e7fdc43fe906681e7f7f`)
creates with the fallback guard enabled and measures **11.33 ms NPU versus
150.25 ms CPU** on a public fixture. On a public indoor floor/wall fixture, NPU
and CPU agree on **99.50%** of raw class pixels and **99.91%** of the
safety-relevant surface kinds, with zero hazard-flag differences, identical
walkable/uncertain corridor states, and maximum corridor-ratio drift **0.00391**.
The app loads both resident models on guarded NPU sessions; a warmed live frame
measured **57.96 ms total**, including 6.21 ms detection and 14.96 ms
segmentation. This is integration evidence, not mAP or field-safety validation.

#### Reproduced independently on the 16 GB loaner, second toolchain

Both models were regenerated from scratch on the second PC and re-probed on the
16 GB I2501 (`10BFAU133N000XR`, also SM8850). Both guarded rungs created and
both verdicts read `NPU CONFIRMED`:

| Model | Guarded HTP | CPU | First phone |
|---|---|---|---|
| `yolo11n_qdq.onnx` | **3.40 ms** | 34.36 ms | 3.30 / 27.35 |
| `segformer_float.onnx` | **11.69 ms** | 146.45 ms | 11.33 / 150.25 |

The app itself then opened both resident sessions on the guarded rung and ran
the live loop, so this is the whole walking pipeline and not two isolated
benchmarks:

```
WalkController: detector ready on NPU: YOLO11n on the Hexagon NPU (QNN HTP, CPU fallback disabled).
SegFormer:      segmentation ready on NPU: 512x512, 150 classes
WalkController: first local frame 1: total=63.63 ms, detection=7.23 ms, segmentation=18.54 ms
```

That frame is **cold** — it is frame 1, and it carries first-call overhead the
first phone's 57.96 ms warmed figure does not. Compare like with like before
reading a regression into the difference.

Two provenance notes, because the artifacts are **not** byte-identical across
machines and that difference must not be mistaken for drift later:

- `segformer_float.onnx` **is** reproducible. The rewrite is deterministic and
  came back as `6c31edf4…`, the same SHA-256 recorded above, from the same
  `segformer_base-onnx-w8a16` input.
- `yolo11n_qdq.onnx` is **not** bit-reproducible across ONNX Runtime versions.
  The second export ran on **ORT 1.30.0** and produced `a6ae5cf6…` rather than
  `ec71f1a4…`, from a byte-identical `yolo11n_fp32_nchw.onnx` (`190371f3…`) and
  the same 128 COCO128 images. Calibration is version-sensitive; the artifact
  hash is therefore evidence of *which build made it*, not of correctness. The
  guarded-rung result and the latency above are what establish equivalence.
  `tools/export_yolo_qdq.py` writes `yolo11n_qdq.json` next to the model
  recording the ORT version and every calibration image hash — read that before
  comparing two exports.

#### Correction: the AAR does bring backend libraries, transitively

`onnxruntime-android-qnn:1.29.0` pulls **`qnn-runtime:2.42.0`** as a transitive
Maven dependency, and that artifact contains the full set — `libQnnHtp.so`,
`libQnnSystem.so`, `libQnnHtpV81Stub.so` and, notably,
**`libQnnHtpV81Skel.so`**, the DSP-side library §3.4 assumed had to come from
the SDK. The build log shows AGP resolving the collision:

```
2 files found for path 'lib/arm64-v8a/libQnnHtp.so'
 - .../app/src/main/jniLibs/arm64-v8a/libQnnHtp.so          (ours, 2.50.0)
 - .../transformed/qnn-runtime-2.42.0/jni/arm64-v8a/...     (transitive)
```

The app module's own `jniLibs` wins. So **which** QAIRT runs is a choice, and
that turns out to be the whole game.

#### Two runtimes, two different failures — both measured on the loaner

| Runtime | `QnnDevice_create` | Model load | Verdict |
|---|---|---|---|
| **QAIRT 2.50.0.260828** (staged by hand) | **FAILS** `QNN_DEVICE_ERROR_INVALID_CONFIG` | never reached | Device is not recognised |
| **qnn-runtime 2.42.0** (from the AAR) | **SUCCEEDS** — FastRPC opens a session, `Created user PD on domain 3`, `libQnnHtpV81Skel.so` handle opened | **FAILS** `Failed to create context from binary. Error code: 5000` | Runtime is fine; the *binary* is wrong for it |

The 2.50.0 failure is not a misconfiguration on our side. It persists with no
provider options at all, and with `htp_arch=81`, and with `soc_model=SM8850`,
and with `soc_model=87`. SM8850 is very new silicon and 2.50.0 was built
2026-08-28; the most economical explanation is that this QAIRT does not yet
know this SoC.

**That experiment established that the NPU was one artifact change away, not
one unknown away.** The DSP was reachable, the skel loaded, and a process
domain was created. The EPContext binary alone failed because it was compiled
for **2.50.40** and loaded into a **2.42.0** runtime. The portable QDQ result
above subsequently removed that mismatch.

#### Therefore: stage no QAIRT by hand. Delete any `jniLibs` you already made.

This is now the single most expensive trap in the build, because it fails
*quietly and plausibly*. A leftover `src/main/jniLibs/arm64-v8a/` shadows the
AAR's working 2.42.0, and the symptom is not a link error — it is a session
that still creates on the unguarded rung and still returns correct numbers,
only slower than the CPU. On 12 September 2026 the 16 GB loaner reproduced it
exactly: guarded rung refused with `ORT_FAIL … fallback to CPU EP has been
explicitly disabled`, unguarded rung ran YOLO at **53.68 ms against 33.25 ms
on plain CPU**, and SegFormer at **140.41 ms against 144.27 ms**. QNN had
accepted zero nodes. The proof is one line down in logcat:

```
remote_handle_open_domain: dynamic loading failed for
  file:///libQnnHtpV81Skel.so?qnn_2_50_0_skel_handle_invoke ... on domain 3
[E:onnxruntime:, qnn_execution_provider.cc:1046 GetCapability]
  QNN SetupBackend failed Failed to create device.
  Error: QNN_DEVICE_ERROR_INVALID_CONFIG
```

`qnn_2_50_0_` in the skel URI names the guilty runtime. Removing the five
hand-staged libraries and reinstalling was the entire fix; nothing else
changed. **Grep for it before diagnosing anything else:**

```bash
find apps/android -type d -name jniLibs
```

Both modules must come back empty. `:probe` matters as much as `:app` — it is
the instrument you would otherwise trust to tell you the NPU is broken.

#### The route taken, and remaining historical alternatives

**Option C is complete.** A and B chased a version pin; C removed it.

**C — DONE: export YOLO11n as a plain QDQ graph instead of an EPContext binary.**
An EPContext model is a *precompiled* context blob and is therefore welded to
the QAIRT that compiled it — that weld is the entire failure above. A QDQ graph
carries no compiled context: `libQnnHtpPrepare.so` (already in the APK, 80 MB
of it) compiles it **on the device, at session creation**, against whatever
runtime is actually present. This is exactly what Qualcomm's SegFormer export
is, and it is why SegFormer never hit error 5000.
- Cost: a slow first session — warm it during Walk Mode startup, and consider
  `ep.context_enable` to cache the compiled result *after* it succeeds once.
- Payoff: version-independent. It would also survive a future AAR bump.
- Try: `yolo export format=onnx int8=True` plus ORT's QNN quantization path, or
  Qualcomm AI Hub's ONNX (not context-binary) output, and confirm the exported
  graph has **no `EPContext` node** before shipping it.

**A — find a runtime that both knows SM8850 and is ≥ 2.50.40.** Check newer
`onnxruntime-android-qnn` versions (1.30+) for a newer bundled `qnn-runtime`;
this is a one-line dependency bump and worth ten minutes before anything else.
Watch for a QAIRT **2.50.40** release on the Software Center channel — the
version the export self-reports, and not public as of 2026-09-12.

**B — re-export YOLO11n against QAIRT 2.42.x** so the context binary matches the
AAR's bundled runtime. The pin comes from the *export toolchain*, not from us,
so this means installing an `onnxruntime-qnn` whose bundled QAIRT is 2.42.x and
re-running the export. Cheap to try, but it pins the app to one AAR version
forever, which is why it ranks below C.

#### How to verify, whichever path

The probe answers this without relying on the app. It reports honestly
rather than inferring from speed:

```bash
cd apps/android && ./gradlew :probe:installDebug && adb shell am start -n com.drishti.probe/.ProbeActivity
```

```bash
adb logcat -d | grep -iE "onnxruntime|qnn" | grep -v DrishtiProbe
```

`session.disable_cpu_ep_fallback = "1"` is set on the NPU rung in the probe,
`OrtYoloDetector`, and `SegFormerSegmenter`, so a silent CPU fallback is
impossible. Both portable graphs create: **YOLO detection and SegFormer
segmentation run entirely on the Hexagon NPU.** CPU remains an explicit,
reported fallback if guarded session creation fails on another device.

---

## 4. Device variants — 12 GB and 16 GB measured

> **Both iQOO 15 variants are now in hand.** The original loaner has 16 GB; the
> current development phone has 12 GB RAM plus compressed swap. The guarded
> YOLO NPU path is proven on the 12 GB unit, so the 16 GB phone is not required
> again until on-demand Scene/VLM work begins. `ARCHITECTURE.md` §4.1's rule
> that every MUST-tier item fit 12 GB remains the controlling budget.

**It touched exactly one capability, and that capability is Scene Mode.**

| | **12 GB** | **16 GB** |
|---|---|---|
| Detection on NPU | Identical | Identical |
| Segmentation on NPU, every frame | Identical | Identical |
| Tracking, spatial, risk, guidance, target memory | Identical | Identical |
| Speech, spatial audio, overlay | Identical | Identical |
| Walk-loop model residency | ~300–450 MB | ~300–450 MB |
| Estimated free RAM (**MEASURE 4.1**) | 5.5 – 6.5 GB | 9 – 10 GB |
| Class B ceiling after the 800 MB margin | ~4.5 GB | ~8 GB |
| OCR / Explore Mode | ML Kit | ML Kit |
| **Scene Mode** | Rung 3 only | **Rung 3 by default; LFM2.5-VL-1.6B (1.31 GB) or Gemma 3n E4B (4.4 GB) as the E10 stretch — see [`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md). CPU, not NPU** |
| Demo claim | Unchanged | Unchanged, plus "it can also answer a spoken question about the scene, offline" |

> **The safety path is byte-identical on both variants.** Nothing on the
> `ARCHITECTURE.md` §3 contract, nothing in the §23.2 functional checks, and
> nothing in the E1–E9 schedule depends on which SKU is drawn. That was the point
> of §4.1's "every MUST-tier item fits 12 GB" and it survives contact with the
> measured numbers with room to spare.

### 4.1 If you draw 16 GB — what E10 actually attempts

Pick **one**. Do not stage both into the APK.

| Option | Size | Runtime | Notes |
|---|---|---|---|
| **Gemma 3n E2B** `.litertlm` | ~3.1 GB | LiteRT-LM / MediaPipe `tasks-genai` | Safest. Fits 12 GB too, if E1–E9 finished early. [google/gemma-3n-E2B-it-litert-lm](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm) |
| **Gemma 3n E4B** `.litertlm` | ~4.4 GB | Same | The "4B-class VL model" case. Comfortable at 16 GB, marginal at 12 GB. [google/gemma-3n-E4B-it-litert-lm](https://huggingface.co/google/gemma-3n-E4B-it-litert-lm) |

Both repos are **licence-gated on Hugging Face** — accept the terms and download
during Part 0 (P0.3.6), not at T+20 h.

> **MUST:** whichever is staged, it is Class B under `ARCHITECTURE.md` §5.1 —
> free-memory check, load, **one** inference, unload, verify reclaim, then
> return. It is never resident, never concurrent with a walk frame in flight, and
> it is audibly refused rather than risked when memory is short.

> **MEASURE 4.1** — on the loaner, after a reboot with everything closed, record
> `ActivityManager.MemoryInfo.availMem`.
> **Decision rule:** ≥ 8 GB free → E10 may attempt E4B. 5–8 GB free → E2B only.
> < 5 GB free → rung 3 only, no phone VLM, and say so if asked.

### 4.2 What this settles

- **E10 stays in the schedule** — but it remains the first thing cut (§6.2).
- **Nothing else changes.** Not the walking loop, not the §3 safety contract, not
  one of the eighteen §23.2 functional checks, not the E1–E9 ordering.
- Scene Mode's ladder and its Class B contract move to
  [`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md), rewritten against 16 GB.

> **Still MEASURE `availMem` on the loaner**, in the state it will be in at demo
> time rather than freshly rebooted. On a measured 12 GB device in daily use the
> figure was **3.6 GB** against a 5.5 GB post-reboot expectation. Assume a
> comparable gap at 16 GB and size the Scene model against the number you read,
> not the number on the box.

### 4.3 Scene Mode

The table above was written before any 12 GB device was measured. One has now
been measured (§5), and **12 GB can carry a real VLM** — LFM2.5-VL-1.6B at
1.31 GB fits inside the worst realistic memory state with the 800 MB safety
margin fully intact.

Full plan, measurements, tier ladder and Class B contract:
[`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md).

It stays a contingency, it stays off the safety path, and it stays the first
thing cut. It runs on the **CPU**, not the NPU, and it is never part of the
accelerator claim.

---

## 5. The playground device

A personal Android device is attached to the build laptop and is available for
testing **before** the event. Measured, not assumed:

| Property | Value |
|---|---|
| Model | OnePlus CPH2767 (15R) |
| SoC | **SM8845 — Snapdragon 8 Gen 5** (not Elite) |
| Platform | `canoe` |
| **Hexagon HTP architecture** | **v81** |
| RAM | `MemTotal` 10.84 GiB — **the 12 GB class** |
| Android | 16, SDK 36, arm64-v8a |
| NPU | `ro.boot.vendor.qspa.npu: enabled` |

### 5.1 Why this is the most valuable fact in the plan

**The playground shares HTP v81 with the target.** The loaner's SM8850
(8 Elite Gen 5) and this SM8845 (8 Gen 5) are different chips with the same
Hexagon architecture version. Evidence: the device ships
`/vendor/lib64/rfs/dsp/libbitml_nsp_81na_skel.so` — the `81` is the NSP
architecture version — corroborated by the
[llama.cpp SM8845 discussion](https://github.com/ggml-org/llama.cpp/discussions/19245).

Consequences:

- A QNN context binary exported with `name=81` **runs on both devices**. One
  export, both targets.
- `ARCHITECTURE.md` §24's unknowns **2, 3 and 4** — can a custom APK install, can
  custom model files load, is the NPU reachable from a third-party app — are all
  answerable **tonight**, on real silicon, instead of at T+2 h on a loaner.
- `ARCHITECTURE.md` §22's R1 gate moves out of the event window and into Part 0.
- The §3.3 runtime ladder becomes testable rather than theoretical.
- Same RAM class as the 12 GB iQOO, so the §4 memory budget is measured.

> **This converts Part 0 from "write code that cannot be run" into "prove the
> entire runtime path on real silicon."** It is the largest single risk reduction
> available in this build, and P0.8 exists to take it.

### 5.2 What it does not prove

- **Not the loaner's thermals.** Different SoC, chassis and sustained clocks.
  MEASURE 17.4.1 must still be run on the iQOO.
- **Not the loaner's absolute latency.** 8 Gen 5 is the volume flagship;
  8 Elite Gen 5 is faster. Expect the loaner to be **at least** as quick. Numbers
  measured here are a **floor** and **MUST NOT** be quoted as iQOO figures.
- **Not OriginOS.** This is OxygenOS. Sideloading toggles, battery-optimisation
  behaviour and developer-option layout differ. E1.2 still applies in full.
- **Not Office Kit.** That is an iQOO feature and cannot be rehearsed here.

> **MUST:** every playground measurement is logged with device serial
> `3C15CC00C3J00000` and labelled **PLAYGROUND**. `ARCHITECTURE.md` §7.4 requires
> the serial beside every quoted number precisely so a playground figure can
> never be mistaken for a loaner figure.

---

## 6. Office Kit and proving on-device AI

Two separate obligations, satisfied by two different bodies of evidence. Do not
let either stand in for the other.

### 6.1 Office Kit is a development control plane, not evidence of AI

`ARCHITECTURE.md` §20 stands unchanged. Office Kit's capabilities are screen
mirroring, remote input, shared clipboard, file transfer and Remote PC. It is how
the machines are operated.

**It must be genuinely used throughout the build, not opened once for judging.**
The §20.3 loop is the intended workflow: checkout, toolchain, Gradle cache and
the coding agent stay on the laptop; the iQOO is the device from which that
environment is operated, and the device the APK lands on.

| Use it for | Never use it for |
|---|---|
| Operating the laptop terminal from the phone (Remote PC) | Camera frames |
| Mirroring the phone UI to show camera overlays live | Inference results |
| Moving APKs, model assets, benchmark exports | Safety decisions |
| Clipboard for hashes, prompts, target names | Anything carrying a typed contract |

> **MUST NOT** present Remote PC development as on-device AI
> (`ARCHITECTURE.md` §20.5). It is not, and a judge who spots the conflation will
> discount the claim that *is* real. The two evidence sets stay separate and are
> presented separately.

> **MUST** record whether mirroring was active for every latency, temperature,
> power or sustained-FPS measurement (§20.2). Mirroring adds display, encode,
> network and thermal load. Pause it for anything that will be quoted, then
> reconnect.

### 6.2 The on-device AI evidence set

Four independent proofs, strongest first. Collect **all four** — together they
cost minutes, and they are the difference between a claim and a demonstration.

| # | Proof | How | Strength |
|---|---|---|---|
| **1** | **The graph cannot run anywhere but the NPU** | Session created with `session.disable_cpu_ep_fallback = "1"`. A CPU fallback becomes a thrown exception at session creation rather than a silent slowdown — so if the session creates, the graph is on HTP | **Strongest.** A structural guarantee, not an observation |
| **2** | **Runtime node assignment** | Enable ONNX Runtime profiling, `adb pull` the JSON, show every node assigned to the QNN EP | Strong, and it is a handover artifact |
| **3** | **Live backend toggle** | Diagnostics panel switches NPU ↔ CPU EP on the same model and frame; the millisecond count collapses by roughly an order of magnitude | Persuasive live, weak on paper. §7.3: **MUST NOT** infer NPU execution from speed alone — this supports proofs 1 and 2, it never replaces them |
| **4** | **Aeroplane mode + coordinator powered off** | Walk the corridor with the radio off and the laptop off. Guidance continues | Weakest technically, **most legible to a non-technical judge** |

> **Deviation from `ARCHITECTURE.md` §2.5, with its reason stated.** §2.5 puts
> flight-mode demonstrations out of scope, preferring the coordinator-off proof
> for *independence*. That reasoning is correct for independence and is kept. But
> "prove on-device AI" is a **separate** obligation, and aeroplane mode is the
> most legible answer to it for an audience that will not read a profiling JSON.
> It costs one toggle. **Do both:** coordinator off for independence, radio off
> as well for the on-device claim. A §1.2 SHOULD-tier deviation with a stated
> reason — not a contract change.

> **MUST NOT** overclaim. ML Kit OCR is on-device and **not** NPU. The VLM, if it
> ships, is on-device and **not** NPU. Only YOLO11 and SegFormer are NPU work.
> Saying exactly that, unprompted, is more convincing than hedging — and it
> protects the claim that is real.

---

# PART 0 — Laptop preparation (T−14 h → T−0)

**No phone is required for P0.1–P0.7.** P0.8 uses the playground device (§5) and
is the highest-value block in Part 0.

## P0.0 — Current state **[HUMAN]**

Verified on this machine:

| Component | State |
|---|---|
| `adb` | **Present**, 1.0.41 / 37.0.0, at `C:\platform-tools\adb.exe` |
| JDK | **Absent.** `java` is not on PATH |
| Android SDK | **Absent.** No `ANDROID_HOME`, no `cmdline-tools`, no platform-36 |
| Gradle | Wrapper will fetch 9.1.0 on first run |
| Python | 3.14.7 — sufficient; `onnxruntime-qnn` 2.6.0 supports 3.11–3.14 |
| Node / npm | 24.19.0 / 11.17.0 — sufficient |
| Git | 2.55.0 |
| GPU | NVIDIA RTX 3050 **6 GB** (not the 8 GB the old backend config assumes) |
| System RAM | 15.7 GB |
| Free disk | 138 GB |

> **`apps/android/gradle.properties` is currently unbuildable on this machine.**
> Line 12 pins `org.gradle.java.home=/home/sparkle/.jdks/jdk-21.0.12.1+1`, a Linux
> path. The first `gradlew` invocation will fail. P0.1.3 fixes it.

---

## P0.1 — Toolchain (1.5 h) **[HUMAN]**

### P0.1.1 — JDK 21

AGP 9.0.1 needs JDK 17+; the module declares `jvmToolchain(21)`.

```bash
winget install --id EclipseAdoptium.Temurin.21.JDK -e --accept-package-agreements
```

Then open a **new** shell and set `JAVA_HOME` permanently:

```bash
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot"
```

Correct the version folder to whatever `ls "C:\Program Files\Eclipse Adoptium"`
actually shows. Verify in a new shell: `java -version` must print 21.

### P0.1.2 — Android SDK

Do **not** install Android Studio. Command-line tools only — 150 MB instead of
1.2 GB, and nothing in this build needs the IDE.

1. Download `commandlinetools-win-*_latest.zip` from
   <https://developer.android.com/studio#command-line-tools-only>
2. Unzip so the final path is
   `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`
   (the zip contains a `cmdline-tools` folder — its contents go into `latest`).
3. Then:

```bash
setx ANDROID_HOME "%LOCALAPPDATA%\Android\Sdk"
```

In a new shell:

```bash
"%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
```

Accept all. Then:

```bash
"%ANDROID_HOME%\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
```

> **MUST: resolve the double-adb hazard now, not at T+2 h.** You will now have
> two `adb.exe` — the existing `C:\platform-tools\adb.exe` and the SDK's. Two
> different adb versions fighting over the server port produces
> `adb server version (X) doesn't match this client (Y); killing...` in a loop,
> and it will happen while a phone is plugged in and a build is waiting. Pick the
> SDK's copy and put `%LOCALAPPDATA%\Android\Sdk\platform-tools` **ahead of**
> `C:\platform-tools` on PATH, or delete `C:\platform-tools` outright. Verify
> with `where adb` — exactly one result should matter, and `adb version` must
> agree with it.

### P0.1.3 — Repair the Gradle configuration

Edit [`apps/android/gradle.properties`](apps/android/gradle.properties) — delete
the two `org.gradle.java.*` lines and raise the heap:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED
org.gradle.caching=true
org.gradle.configuration-cache=false

android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

Create `apps/android/local.properties` (gitignored, must exist):

```properties
sdk.dir=C:/Users/yasha/AppData/Local/Android/Sdk
```

> **Use forward slashes.** This is a Java **properties** file, where `\` is an
> escape character, so a Windows path written with single backslashes silently
> collapses (`C:\Users\yasha` becomes `C:Usersyasha`). Doubling them works but is
> easy to get wrong; forward slashes need no escaping and Gradle accepts them on
> Windows.
>
> **The resulting failure is unrecognisable.** Gradle reports:
>
> ```text
> Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.
> > java.io.IOException: The filename, directory name, or volume label syntax is incorrect
> ```
>
> Nothing in that message names `local.properties` or the SDK. Observed on this
> laptop; it cost a full 4m45s build cycle.

### P0.1.4 — Prove the build **[GATE P0.1]**

```bash
cd apps/android && ./gradlew --no-daemon clean assembleDebug
```

First run downloads Gradle 9.1.0 and the full dependency set — allow 10–15
minutes. Then:

```bash
cd apps/android && ./gradlew test
```

The five existing unit tests in
[`app/src/test/java/com/drishti/app/`](apps/android/app/src/test/java/com/drishti/app)
must pass.

> **GATE P0.1 — passed when `assembleDebug` produces an APK and `test` is green.**
> Nothing else in Part 0 proceeds until this is true. If it is not true at
> T−12 h, stop everything else and fix it: an unbuildable project at T−0 is a
> dead build.

---

## P0.2 — Python environments and golden vectors (1.0 h) **[HUMAN]**

Two environments, deliberately separate. Do not merge them — the heavy one's
dependency resolution is slow and failure-prone, and the light one is on the
critical path.

### P0.2.1 — The light environment: golden-vector export

The risk, spatial, tracking, and state-machine tests are **model-free**. They
need no torch, no ultralytics, no GPU.

```bash
cd entire-old-codebase/backend && python -m venv .venv-vectors && .venv-vectors/Scripts/pip install numpy opencv-python-headless pydantic pydantic-settings pytest fastapi httpx sqlalchemy pillow
```

Verify the pure-logic suites run:

> **`tests/conftest.py` imports the whole FastAPI app**, which transitively pulls
> `fastapi`, `httpx`, `sqlalchemy` and `pillow` even for the model-free suites —
> verified here by hitting each ImportError in turn. Hence the wider list above.
>
> **A0's export script MUST import the production modules directly**
> (`app.risk.rules`, `app.spatial.corridor`, ...) and **not** go through
> `conftest`. The golden vectors describe pure functions; routing them through an
> app fixture adds a dependency chain that has nothing to do with the behaviour
> being frozen.

```bash
cd entire-old-codebase/backend && .venv-vectors/Scripts/python -m pytest tests/unit/test_risk.py tests/unit/test_spatial.py tests/unit/test_tracking.py -q
```

### P0.2.2 — The heavy environment: QNN export

Install the CPU torch wheel explicitly. The default index pulls a ~2.5 GB CUDA
build that nothing here uses.

```bash
cd /c/Users/yasha/Documents/drishti-edge-yash && python -m venv .venv-export && .venv-export/Scripts/pip install torch --index-url https://download.pytorch.org/whl/cpu
```

```bash
cd /c/Users/yasha/Documents/drishti-edge-yash && .venv-export/Scripts/pip install ultralytics onnxruntime-qnn onnxslim
```

Start this download **first** and let it run while P0.1 and P0.2.1 proceed.

### P0.2.3 — Export the golden vectors **[AGENT — task A0]**

See [A0](#a0--golden-vectors) in Part 1. Produces
`apps/android/app/src/test/resources/golden/*.json`.

---

## P0.3 — Models (1.5 h, mostly unattended) **[HUMAN]**

Full manifest with URLs and checksums: [Appendix A](#appendix-a--model-manifest).

### P0.3.1 — Create the staging directory

```bash
mkdir -p /c/Users/yasha/Documents/drishti-edge-yash/models/staging
```

`models/` is gitignored — by design (`.gitignore` line 14). Weights are staged on
disk, never committed.

### P0.3.2 — YOLO11n base weights

```bash
curl -L -o models/staging/yolo11n.pt https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt
```

Verify against the digest the parent project already recorded:

```bash
python -c "import hashlib,sys;print(hashlib.sha256(open('models/staging/yolo11n.pt','rb').read()).hexdigest().upper())"
```

Expect `0EBBC80D4A7680D14987A577CD21342B65ECFD94632BD9A8DA63AE6417644EE1`.

> **MUST:** if the digest differs, stop. A different YOLO11n means different
> label ordering risk and the golden vectors no longer describe the deployed
> model.

### P0.3.3 — Export YOLO11n to QNN for HTP v81

```bash
cd /c/Users/yasha/Documents/drishti-edge-yash && .venv-export/Scripts/yolo export model=models/staging/yolo11n.pt format=qnn name=81 imgsz=640 batch=1
```

`name=81` is the Hexagon HTP architecture version for **Snapdragon 8 Elite
Gen 5**. Getting this wrong produces a binary that silently will not load on the
loaner.

| HTP | Chip |
|---|---|
| 75 | 8 Gen 3 |
| 79 | 8 Elite |
| **81** | **8 Elite Gen 5 — this is the one** |

Precision is auto-enforced to `w8a16`. Calibration data is downloaded
automatically (a small COCO subset) unless `data=` is given — this is the one
step in Part 0 that needs internet mid-command.

Produces `yolo11n_qnn.onnx` with the context binary and the Ultralytics metadata
(class `names`, `imgsz`, `task`) embedded.

> **VERIFIED — this export has been run.** 3.5 MB, 33 s on this laptop, no
> Qualcomm account. The artifact carries
> `ep_compatibility_info.QNNExecutionProvider = v2:6:2.50.40:5.50.0:81:0:0:0`,
> which reads as QNN EP 2.6, **QAIRT 2.50.40**, **HTP arch 81**. That QAIRT
> version is the one the §3.4 Android backend libraries should match.

> **MUST use a real calibration set.** With `data=` omitted, Ultralytics defaults
> to `coco8.yaml` — **four images** — and warns:
> *">300 images recommended for INT8 calibration, found 4 images."* Four images
> cannot characterise the activation ranges of a detector that has to work in a
> corridor, and the resulting quantization error shows up as missed small
> obstacles. That is a safety regression wearing the costume of a successful
> export.
>
> Pass at least `data=coco128.yaml`. **Better:** once you have indoor frames from
> the venue, calibrate on those — a detector quantized against the environment it
> will actually run in is the cheapest accuracy win available in this build.

> Also expect: *"original model opset version is 18, which does not support
> 16-bit integer quantization natively … automatically update the model to opset
> 21. Please verify the quantized model."* This is benign, and the verification
> it asks for is exactly P0.8.3's fixture check.

> **The exporter refuses to overwrite an existing context model.** Re-running the
> command when `yolo11n_qnn.onnx` is already present fails with:
> *"Failed to generate EP context model since the file 'yolo11n_qnn.onnx' exists
> already. Please remove the EP context model if you want to re-generate it."*
>
> It is **not** a quantization or toolchain failure, and — because the stale file
> is still sitting there at the expected size — it is very easy to mistake for a
> successful re-export. Delete the artifact first:
>
> ```bash
> cd models/staging && rm -f yolo11n_qnn.onnx && yolo export model=yolo11n.pt format=qnn name=81 imgsz=640 batch=1 data=coco128.yaml
> ```
>
> **MUST** re-verify the checksum after every re-export, or you will ship the
> four-image-calibrated build believing it is the 128-image one.

> **Also export a plain ONNX as the rung-3 fallback**, so the event window never
> waits on a laptop export — and **rename it immediately**:
>
> ```bash
> cd models/staging && yolo export model=yolo11n.pt format=onnx imgsz=640 batch=1 simplify=True && mv yolo11n.onnx yolo11n_fp32_nchw.onnx
> ```
>
> **The rename is not cosmetic.** The QNN export pipeline writes its own
> NHWC intermediate to `yolo11n.onnx` as a side effect, so a plain export left
> under that name is silently overwritten by the next `format=qnn` run — and the
> file that survives carries the *QNN* export's `args` metadata, which makes the
> substitution invisible unless you read the graph. Observed on this laptop.

### P0.3.4 — SegFormer-B0 ADE20K

Public S3, no account:

```bash
curl -L -o models/staging/segformer-w8a16.zip "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-onnx-w8a16.zip"
```

Also pull the float build as the parity reference and the fallback:

```bash
curl -L -o models/staging/segformer-float.zip "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-onnx-float.zip"
```

Unzip both into `models/staging/segformer/`.

**The label map is already on disk.** Copy it — this is what makes the semantics
port with zero drift:

```bash
cp entire-old-codebase/models/segmentation/segformer-b0-ade20k/config.json models/staging/segformer/ade20k_config.json
```

It carries the full 150-entry `id2label` (`0 → wall`, `1 → building`,
`2 → sky`, `3 → floor`, `5 → ceiling`, …) that
[`surfaces.py`](entire-old-codebase/backend/app/spatial/surfaces.py) tokenises
against. Preprocessing, from `preprocessor_config.json`: resize 512, bilinear,
normalize with ImageNet mean `[0.485, 0.456, 0.406]` / std `[0.229, 0.224, 0.225]`.

> **MEASURE P0.3.4 — RESOLVED.** The w8a16 package was downloaded and its
> `metadata.json` read. The contract is:
>
> | | Name | Shape | dtype | Quantization |
> |---|---|---|---|---|
> | **Input** | `image` | `[1, 3, 512, 512]` | **uint16** | scale `1.5259022e-05`, zero-point `0`, **value range `[0.0, 1.0]`** |
> | **Output** | `class_logits` | `[1, 150, 128, 128]` | **uint16** | scale `8.306152e-04`, zero-point `60183` |
>
> Package layout: `segformer_base.onnx` (356 KB graph) + `segformer_base.data`
> (15 MB external initializers) + `metadata.json`. **Both files must ship
> together** — the `.onnx` alone is a graph with no weights.
>
> **This is the good branch.** Logits are emitted, not argmax, so the confidence
> map survives and `wall_min_pixel_confidence` still applies. Port the Python
> path exactly: softmax over the class dim → max → class + confidence → resize to
> source, `INTER_NEAREST` for classes and `INTER_LINEAR` for confidence. The
> 128×128 output is 1/4 resolution, as SegFormer natively emits; upsample it.

> **CORRECTION to the preprocessing, and it matters.** The `value_range` is
> **`[0.0, 1.0]`** — **not** ImageNet mean/std. Qualcomm's wrapper has baked the
> `[0.485, 0.456, 0.406]` / `[0.229, 0.224, 0.225]` normalization **into the
> graph**. Feed raw RGB scaled to 0–1 and quantized to uint16
> (`u16 = round(rgb01 / 1.5259022e-05)`, i.e. `rgb01 * 65535`).
>
> **MUST NOT** apply `preprocessor_config.json`'s mean/std on top. Doing so
> double-normalizes, and the failure mode is the worst kind: segmentation that
> runs, returns plausible-looking regions, and is quietly wrong — which under
> `docs/SAFETY_RULES.md` is a system confidently telling a blind user where the
> floor is, incorrectly. The ImageNet constants stay relevant **only** to the
> Python reference implementation during parity checks.

### P0.3.5 — ML Kit (no runtime download) **[COMPLETE]**

ML Kit text recognition ships its model inside the bundled dependency. Nothing
to stage or fetch when the feature runs. Added and verified in
[A8](#a8--explore-mode-ocr).

### P0.3.6 — VLM, 16 GB stretch only

Both repos are **licence-gated**. Accept the terms on Hugging Face in a browser
now, then download. Do not leave this to T+20 h.

- <https://huggingface.co/google/gemma-3n-E2B-it-litert-lm> — ~3.1 GB
- <https://huggingface.co/google/gemma-3n-E4B-it-litert-lm> — ~4.4 GB

Stage both to `models/staging/vlm/`. You will push exactly one to the phone,
after the variant is known.

### P0.3.7 — EasyOCR, stretch only

```bash
curl -L -o models/staging/easyocr-w8a8.zip "https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/easyocr/releases/v0.62.1/easyocr-onnx-w8a8.zip"
```

Stage it. Do not plan to use it.

### P0.3.8 — Record every checksum **[GATE P0.3]**

```bash
cd models/staging && find . -type f \( -name "*.onnx" -o -name "*.pt" -o -name "*.zip" \) -exec sha256sum {} \; > MANIFEST.sha256 && cat MANIFEST.sha256
```

`ARCHITECTURE.md` §7.4 requires model-asset checksums alongside every quoted
measurement. This file is that record.

---

## P0.4 — Coordinator restore (1.0 h) **[AGENT — task A9]**

There is **no backend in this repository.** `apps/` contains only `android` and
`dashboard`; the FastAPI service exists only in the gitignored
`entire-old-codebase/backend/`. The dashboard cannot run without it.

See [A9](#a9--the-narrowed-coordinator). First thing cut if P0.1 overruns.

> The RTX 3050's **6 GB** VRAM is below the old config's
> `vlm_min_free_vram_mb: 4600` comfort margin for Moondream2. The narrowed
> coordinator **MUST NOT** include the VLM locator — `ARCHITECTURE.md` §6.4 rung 2
> is dropped from this build. Nothing depends on it; rung 3 covers the demo beat.

---

## P0.5 — The deterministic Kotlin port (3.5 h) **[AGENT — tasks A1–A5]**

**This is the single largest win available in Part 0.** Tracking, spatial
reasoning, the risk engine, the guidance state machine, and landmark memory are
pure functions of detections and surface regions. They need no camera, no NPU,
and no phone. They are testable on the JVM against the P0.2.3 golden vectors.

`ARCHITECTURE.md` §22 places this at R3, inside the event window. Moving it to
Part 0 buys back roughly four hours of device time, and device time is the only
scarce resource in this build.

Task cards: [A1](#a1--the-detection-domain-model) through [A5](#a5--landmark-memory).

---

## P0.6 — The inference seam (1.5 h) **[AGENT — tasks A6–A7]**

Write the `OnDeviceDetector` interface, the ORT QNN session factory, the
preprocessing and postprocessing, and the accelerator scheduler. It will compile
and it cannot be run — there is no phone. That is fine; E2 and E3 are then wiring
and measurement rather than authorship.

Task cards: [A6](#a6--the-ondevicedetector-seam), [A7](#a7--the-accelerator-scheduler).

---

## P0.7 — Pre-flight (0.5 h) **[HUMAN]**

Go / no-go before sleeping.

- [x] `./gradlew assembleDebug` green
- [x] `./gradlew test` green, including the new golden-vector tests
- [x] `qnn-runtime:2.42.0` resolved transitively with the v81 libraries; the
      incompatible manually staged QAIRT runtime is not used (§3.5)
- [x] `ADSP_LIBRARY_PATH` set before the first QNN session
- [x] project-local `tools/android.ps1 adb` selects one authoritative SDK copy
- [x] deployed YOLO and SegFormer SHA-256 values recorded in §3.5
- [x] `yolo11n.pt` digest matches `0EBBC80D…`
- [x] SegFormer w8a16 ONNX unzipped, `ade20k_config.json` beside it with 150 labels
- [x] Golden vectors committed under `app/src/test/resources/golden/`
- [ ] Coordinator starts and `/api/v1/health` answers (or explicitly cut)
- [ ] Dashboard builds: `npm run build --workspace apps/dashboard`
- [ ] Evidence log file created (Part 4)
- [ ] Phone charger, **USB-C data cable** (not a charge-only cable), laptop charger packed
- [x] **GATE P0.8 passed** — probe reports NPU on the playground with the
      fallback guard on, and plausible boxes on the fixture image
- [ ] Playground evidence pack captured and labelled **PLAYGROUND** (P0.8.5)
- [ ] `git log --oneline -1` recorded — this commit is the Part 0 baseline

> **The cable is a real failure mode.** A charge-only USB-C cable enumerates
> power and no data, `adb devices` shows nothing, and you will spend twenty
> minutes debugging OriginOS developer options. Pack two known-good data cables.

Then sleep four hours.

---

## P0.8 — Prove the NPU path on the playground device (2.0 h) **[HUMAN + AGENT]**

**This is the highest-value block in Part 0 and it did not exist before the
playground device (§5) was available.** It answers, on real HTP v81 silicon,
three of the four questions `ARCHITECTURE.md` §24 reserves for the loaner — and
it answers them tonight instead of at T+2 h with a 30 h clock running.

Reorder Part 0 to fit it: P0.4 (coordinator) drops behind P0.8. A working NPU
path is worth more than a dashboard.

### P0.8.1 — The probe app

Build the smallest thing that proves the runtime: a single-Activity debug build
that loads `yolo11n_qnn.onnx`, runs it on one bundled test image, and prints the
result. No camera, no Compose, no guidance. It exists to answer one question.

It **MUST** report, on screen and in logcat:

| Field | Why |
|---|---|
| Session created with `disable_cpu_ep_fallback = "1"`? | §6.2 proof 1 |
| QNN EP registration succeeded, and the option map used | Confirms the API shape (MEASURE A6.1) |
| Inference ms, warm, averaged over 50 runs | The real number |
| Same model on CPU EP, same 50 runs | §6.2 proof 3, the comparison |
| Peak native memory | §4 budget check |
| The detected boxes, as normalized coordinates | Confirms output layout and class ordering |

Use [`test-media/phase2/ultralytics-bus.jpg`](entire-old-codebase/test-media/phase2/ultralytics-bus.jpg)
as the fixture — the parent project already used it, so the expected detections
are a known quantity rather than a judgement call.

### P0.8.2 — Run it

```bash
cd apps/android && ./gradlew :probe:installDebug && adb shell am start -n com.drishti.probe/.ProbeActivity
```

```bash
adb logcat -c && adb logcat -v time | grep -Ei "qnn|htp|ep_context|backend|probe"
```

### P0.8.3 — The gate **[GATE P0.8]**

| Question | Passed when | If it fails |
|---|---|---|
| Does a custom APK install on a retail device? | `installDebug` succeeds | §24 unknown 2 — but on *this* device it is a local problem, not an organiser problem |
| Do custom model files load from assets? | The session creates | Try the external-files-dir path (Part 3.3) |
| **Is the NPU reachable from a third-party app?** | **Session creates with the fallback guard ON** | Descend §3.3's ladder and record which rung held |
| Is the context binary for the right HTP? | No architecture-mismatch error | Re-export with a different `name=` and record which one works |
| Does output layout match expectations? | Boxes on the bus image are sane | Fix postprocessing now, on a known image, not at T+6 h on a live camera |

> **GATE P0.8 — passed when the probe reports NPU, with the fallback guard on,
> and plausible boxes on the fixture image.** Once this is true, the riskiest
> unknown in the entire build is retired before the event starts.

> **If the guard throws:** that is the guard working, and it is still a good
> outcome — you have learned it tonight. Retry without the guard, read which
> nodes fell back, and record it. A partial fallback on a small subgraph still
> supports the NPU claim; say exactly which nodes and no more.

### P0.8.4 — Do the same for SegFormer

Same probe, second model. This retires the §6.2 gate — the one
`ARCHITECTURE.md` treats as most likely to fail — before the event.

Feed it one indoor frame from a phone camera and check that `floor` (ADE20K
class 3), `wall` (0) and `ceiling` (5) land where a human would put them.

> **MEASURE P0.8.4** — confirm the output tensor shape against MEASURE P0.3.4's
> decision rule, and confirm that argmax over the class dimension reproduces the
> Python `id2label` ordering on the same input. This is `ARCHITECTURE.md` §6.2
> gate steps 1 and 2, completed on a laptop schedule rather than an event one.

> **RESULT — passed on the 12 GB iQOO 15.** The output is
> `[1,150,128,128]`; guarded HTP runs at 11.33 ms. The public indoor fixture in
> `tools/check_segformer_fixture.py` measured 99.50% class argmax agreement and
> 99.91% safety-surface agreement against CPU, with identical corridor threshold
> states and no hazard-flag differences. This is an integration fixture, not a
> substitute for the full indoor replay or field validation.

### P0.8.5 — Capture the evidence pack now

Everything in §6.2 that does not need the iQOO:

- [ ] ORT profiling JSON, pulled with `adb pull`, showing QNN EP node assignment
- [ ] Logcat excerpt showing HTP backend initialisation
- [ ] The NPU-vs-CPU millisecond comparison, both numbers, same model, same image
- [ ] Screenshot of the probe's own report

> **MUST** label every one of these **PLAYGROUND / SM8845 / serial
> 3C15CC00C3J00000** (§5.2). They are proof that the *path* works. They are not
> iQOO numbers and **MUST NOT** be presented as such. Re-capture the same set on
> the loaner at E2 — which will then take twenty minutes instead of two hours,
> because the code and the procedure will already be known-good.

### P0.8.6 — What this buys in the event window

| Was | Becomes |
|---|---|
| E2 — NPU proof, 2.0 h, **hard gate, unknown outcome** | E2 — re-run a known-good probe on the loaner, **0.5 h** |
| E6 — segmentation, 2.0 h, soft gate, expected to be painful | E6 — wire a proven model in, **1.0 h** |
| MEASURE A6.1 — unresolved API shape | Resolved |
| Postprocessing correctness — discovered live on camera | Already correct against a fixture |

**That is roughly 2.5 h returned to the event window, and the two scariest gates
retired.** It is worth the 2 h it costs tonight.

---

# PART 1 — Agent build manual

Hand these to the coding agent one at a time, in order. Each card is
self-contained.

**Rules that apply to every card:**

> - **MUST NOT** start the Android client from zero (`ARCHITECTURE.md` §2.2). The
>   Compose UI, CameraX plumbing, preview transform, feedback engines and
>   existing tests stay. One seam changes.
> - **MUST NOT** touch [`ui/PreviewTransform.kt`](apps/android/app/src/main/java/com/drishti/app/ui/PreviewTransform.kt).
>   It is correct. Regressing it breaks the overlay silently
>   (`ARCHITECTURE.md` §10).
> - **MUST** keep the existing `packages/contracts` shapes. The dashboard and the
>   Android DTOs in [`net/Dto.kt`](apps/android/app/src/main/java/com/drishti/app/net/Dto.kt)
>   are written against them.
> - **MUST** port behaviour, not improve it. A port that changes behaviour while
>   changing platform is a port whose failures cannot be isolated
>   (`ARCHITECTURE.md` §2.3).
> - Every card ends with a green test. No card is "done" on inspection.

---

## A0 — Golden vectors

**Slot:** P0.2.3 · **Gate:** R0 · **Phone:** not needed

**Preconditions:** `.venv-vectors` created (P0.2.1).

**Task.** Write `entire-old-codebase/backend/scripts/export_golden.py` that
imports the *production* modules — not the tests — and serialises input/output
pairs to JSON.

Cover, at minimum:

| Vector file | Source module | Cases |
|---|---|---|
| `canonicalization.json` | `perception/detector.py` `canonicalize_detections` | Alias collapse (`backpack`/`handbag` → `bag`, `dining table`/`table` → `desk`); allowlist rejection; `allowed_labels=None, apply_aliases=False` full-COCO view; sub-threshold rejection; degenerate box rejection; clamping |
| `tracking.json` | `perception/tracking.py` `SessionTracker` | ID persistence across frames; IoU 0.20 and centre-distance 0.12 association; `track_max_age_frames` 3 expiry; `MotionVector`; `ApproachState` at the 0.05 deadband — all four outcomes |
| `spatial.json` | `spatial/corridor.py`, `spatial/proximity.py` | `direction_for_anchor`; `bbox_path_overlap`; trapezoid corridor polygons; `analyze_corridors` with and without surfaces; proximity bands at 0.35 / 0.55 / 0.78 |
| `risk.json` | `risk/scoring.py`, `risk/rules.py` | Weighted per-detection score (0.30/0.25/0.20/0.15/0.10); `risk_level_for_score`; the full `select_action` cascade; critical approaching-vehicle override; `_clearer_side` |
| `state_machine.json` | `risk/state_machine.py` | `alert_persistence_frames` 2; `alert_clear_frames` 3; `alert_cooldown_seconds` 3.0; **all four pending reason codes**; `decision_margin` 0.15 |
| `surfaces.json` | `spatial/surfaces.py` | `semantic_kind_map` for a synthetic `class_map`; ADE20K token → `SurfaceKind`; `UNKNOWN` for low confidence |

Each case: `{"name", "settings_overrides", "input", "expected"}`. Default
`Settings()` unless overridden.

> **MUST** include failure and boundary cases, not just successes.
> `ARCHITECTURE.md` §22 R3's gate is parity "for every golden success *and*
> failure case." A vector set of only happy paths proves nothing about the
> cascade.

> **MUST** include the cases behind `ARCHITECTURE.md` §23.2 tests 7, 8 and 10 —
> obstacle centre with neither side defensible, no usable evidence, and a
> direction change under persistence. Those are the `PAUSE_UNCLEAR` and
> `DIRECTION_CHANGE_PENDING` paths, they are the §3 behaviour that separates this
> from a demo toy, and they are the first thing a hurried port breaks.

**Output:** `apps/android/app/src/test/resources/golden/*.json`, committed.

**Acceptance:** `pytest` green; every JSON parses; ≥ 8 cases per file; each file
contains at least one case whose expected action is `PAUSE_UNCLEAR`.

---

## A1 — The detection domain model

**Slot:** P0.5 · **Gate:** R3 · **Phone:** not needed

**Task.** Create `com.drishti.app.perception` with Kotlin equivalents of the
Python dataclasses. Immutable (`data class`, `val`).

```kotlin
data class DetectionCandidate(
    val label: String,
    val confidence: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
)

/** Two label filterings of ONE detector invocation (ARCHITECTURE.md §9.3). */
data class DetectionSet(
    val risk: List<DetectionCandidate>,   // 19 audited labels, aliased
    val all:  List<DetectionCandidate>,   // full native COCO, no aliasing
)
```

Port `canonicalizeDetections` from
[`detector.py`](entire-old-codebase/backend/app/perception/detector.py) with
`CANONICAL_LABELS` (19) and `LABEL_ALIASES` verbatim.

> **MUST NOT** feed all 80 classes into the safety engine
> (`ARCHITECTURE.md` §9.3). A COCO label says an object is present; it does not
> establish that it obstructs the walking corridor.

> **MUST NOT** apply aliases to the full view. The aliases collapse `backpack`
> and `handbag` into `bag`, which discards the exact word the user says when
> asking to be guided to one.

> `door` is in `CANONICAL_LABELS` and is **not a COCO class**. Keep it in the
> set for parity with the Python source, and record in the diagnostics panel that
> it is unreachable from the deployed detector. The system does not claim door
> detection (`docs/SAFETY_RULES.md`).

**Acceptance:** `canonicalization.json` passes.

---

## A2 — Tracking

**Slot:** P0.5 · **Gate:** R3 · **Phone:** not needed

**Task.** Port [`tracking.py`](entire-old-codebase/backend/app/perception/tracking.py)
to `com.drishti.app.perception.SessionTracker`. IoU + class identity
association, thresholds 0.20 / 0.12, `maxAgeFrames` 3, per-track id / class /
box history / first-seen / last-seen, `MotionVector` from centre trajectory,
`ApproachState` from area growth at the 0.05 deadband.

- **MUST** run on CPU. There is no reason to involve an accelerator.
- **MUST NOT** replace it with a "nearest object" heuristic during the port.
- Deterministic: given the same frames in the same order, the same track IDs.

**Acceptance:** `tracking.json` passes.

---

## A3 — Spatial reasoning

**Slot:** P0.5 · **Gate:** R3 · **Phone:** not needed

**Task.** Port [`corridor.py`](entire-old-codebase/backend/app/spatial/corridor.py)
and [`proximity.py`](entire-old-codebase/backend/app/spatial/proximity.py).

The Python uses numpy masks and polygon intersection. In Kotlin:

- Corridor trapezoids from `corridorHorizonY` 0.38, `corridorTopHalfWidth` 0.08,
  `corridorBottomHalfWidth` 0.42. **Port the geometry exactly** — it encodes the
  camera's perspective, and "thirds" is not an acceptable simplification.
- `bboxPathOverlap` — polygon intersection area. A small shoelace-formula
  implementation; no geometry library.
- `surfaceRatios` / `floorExtent` — rasterise the corridor polygon against the
  segmentation kind map. `MIN_WALKABLE_RATIO` 0.25, `MIN_BLOCKING_SURFACE_RATIO`
  0.20.
- Proximity: `proximityAreaWeight` 0.55, `proximityAreaScale` 0.50, bands at
  0.35 / 0.55 / 0.78.

> **MUST NOT** convert a proximity band into, or present it as, a metric
> distance (`docs/SAFETY_RULES.md`). A single camera cannot measure it.

> Low-confidence pixels map to `UNKNOWN`, **never** to `WALKABLE`. Defaulting
> uncertainty to walkable manufactures confidence the model did not express and
> is a §3 violation (`ARCHITECTURE.md` §9.4).

**Acceptance:** `spatial.json` and `surfaces.json` pass.

---

## A4 — Risk engine and guidance state machine

**Slot:** P0.5 · **Gate:** R3 · **Phone:** not needed

**Task.** Port [`scoring.py`](entire-old-codebase/backend/app/risk/scoring.py),
[`rules.py`](entire-old-codebase/backend/app/risk/rules.py),
[`state_machine.py`](entire-old-codebase/backend/app/risk/state_machine.py),
[`priority.py`](entire-old-codebase/backend/app/risk/priority.py),
[`actions.py`](entire-old-codebase/backend/app/guidance/actions.py).

All 19 `riskClassSeverities`, the five weights summing to 1.0, the thresholds,
persistence, hysteresis, cooldown, all four pending reason codes, and
`safetyPreemptsTargetGuidance`.

> **MUST:** frame-level decision is a **rule cascade**, not an aggregate
> (`ARCHITECTURE.md` §12.2). Do not "simplify" it into a max or a mean. The
> cascade's ordering is the safety behaviour.

> **MUST:** prefer `STOP` or `PAUSE_UNCLEAR` over a confident wrong answer. When
> the cascade reaches no defensible direction, the answer is `PAUSE_UNCLEAR` —
> never a guess, and never `CLEAR`.

> **MUST NOT** add a "nothing detected → CLEAR" shortcut. That is the §3.1
> violation `ARCHITECTURE.md` §3.4 names by anticipation. An empty detection list
> from a covered lens and an empty detection list from an open corridor are the
> same input and must not produce the same confident output.

**Acceptance:** `risk.json` and `state_machine.json` pass on **decision, reason
code, preferred corridor, and critical override** — all four, every case.

---

## A5 — Landmark memory

**Slot:** P0.5 · **Gate:** R6 · **Phone:** not needed

**Task.** Port [`landmark_memory.py`](entire-old-codebase/backend/app/perception/landmark_memory.py),
including `normalizeLabel`, `labelsMatch`, the colour-word and leading-noise
stripping, and the synonym table.

Fed from `DetectionSet.all` — the full native COCO view. Bounds: TTL 45 s, max
40, min confidence 0.45, min sightings 2, `landmarkAllowPerson = false`.

> **MUST NOT** retain anything after the session ends. Session-scoped, in memory,
> no disk (`docs/SAFETY_RULES.md`).

**Acceptance:** unit tests for TTL eviction, the max-entries bound, sighting
counting, and `labelsMatch` on the synonym and colour-word cases.

---

## A6 — The `OnDeviceDetector` seam

**Slot:** P0.6 · **Gate:** R2 · **Phone:** needed only to *run*

**Task.** This is the one structural change to the Android client
(`ARCHITECTURE.md` §2.2).

Add to [`app/build.gradle.kts`](apps/android/app/build.gradle.kts):

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
```

> **PREREQUISITE — [§3.4](#34-the-qnn-backend-libraries-are-not-in-the-aar--verified).**
> The AAR ships **no QNN backend libraries**. Before this card can run on a
> device, the QAIRT `aarch64-android` and `hexagon-v81` libraries must be in
> `app/src/main/jniLibs/arm64-v8a/`, and `ADSP_LIBRARY_PATH` must be set before
> the first session. Adding the Gradle line alone gets you a silent CPU
> fallback.

Define the interface:

```kotlin
interface OnDeviceDetector {
    val backend: InferenceBackend      // NPU | GPU | CPU | UNAVAILABLE
    val detail: String
    suspend fun detect(frame: OrientedFrame): DetectionSet
    fun close()
}
```

Implement `QnnYoloDetector`:

1. **Session creation.** Load `yolo11n_qnn.onnx` from assets. Register the QNN
   EP with `backend_type = "htp"` and `htp_performance_mode = "burst"`, and set
   the config entry `session.disable_cpu_ep_fallback = "1"`.
   > **MEASURE A6.1 — RESOLVED.** The 1.29.0 AAR's `classes.jar` was inspected
   > directly. `ai.onnxruntime.OrtSession$SessionOptions` exposes **`addQnn`**,
   > `ai.onnxruntime.OrtProvider` has a **`QNN`** constant with provider name
   > **`QNNExecutionProvider`**, and `addConfigEntry` is present for the fallback
   > guard. Options are passed as a string map
   > (`ai.onnxruntime.providers.StringConfigProviderOptions`). No guessing needed.
2. **Backend reporting.** If session creation throws with the fallback guard on,
   catch it, record `InferenceBackend.CPU`, retry **without** the guard, and
   surface the degradation. Never fail silently to CPU.
3. **Preprocess — the exported contract, verified by loading the artifact.**

   | | Name | Shape | dtype |
   |---|---|---|---|
   | Input | `images` | `[1, 640, 640, 3]` | **float32** |
   | Output | `output0` | `[1, 84, 8400]` | **float32** |

   > **CORRECTION — the input is NHWC, not NCHW.** An earlier draft of this card
   > said NCHW. It is `[1, 640, 640, 3]`: batch, height, width, channel. Feeding
   > NCHW produces a session that runs and returns confident nonsense. This is
   > the single easiest way to lose two hours on this card.

   > **The graph quantizes internally.** The exported model is three nodes —
   > `QuantizeLinear → EPContext → DequantizeLinear` — so **feed plain float32
   > normalised 0–1 and read plain float32 back**. Do *not* hand-quantize to
   > uint16 the way SegFormer requires (P0.3.4). The two models differ here and
   > the difference is deliberate.

   YUV_420_888 → RGB → letterbox to 640×640 → NHWC float32 0–1. Reuse buffers.
   > **MUST NOT** allocate a `Bitmap` per frame. At 5–10 fps that is a GC storm
   > that shows up as stutter in the guidance cadence (`ARCHITECTURE.md` §5.3).
4. **Postprocess.** `output0` is `[1, 84, 8400]` — the raw YOLO11 head, 8400
   anchors × (4 box + 80 class scores), **no NMS applied**. NMS at IoU 0.45 and
   the 0.35 confidence floor are yours to implement.
   **Un-letterbox before normalising.** Read class names from the ONNX metadata
   property **`names`** — verified present in the export, a full 80-entry dict
   (`{0: 'person', 1: 'bicycle', 2: 'car', ...}`). Do not hardcode COCO ordering.
   The export also carries `imgsz`, `task`, `stride`, `batch` and an AGPL-3.0
   `license` string in the same metadata block.
   > **MUST:** boxes are emitted in `ORIENTED_CAPTURE_NORMALIZED` — normalised
   > against the full oriented capture, **not** the letterboxed tensor. This is
   > the single most common source of overlay misalignment and it is silent:
   > boxes land slightly wrong in a way that looks like camera jitter
   > (`ARCHITECTURE.md` §9.3).
5. **Two views from one inference.** Call `canonicalizeDetections` twice over the
   same raw list — once allow-listed and aliased, once full-COCO and unaliased.
   One forward pass. Never two.

Then rewire
[`WalkController.onCameraFrame`](apps/android/app/src/main/java/com/drishti/app/walk/WalkController.kt:294):
the frame goes to `OnDeviceDetector`, not to `FrameEncoder` and
`api.analyze`. Delete the JPEG encode from the walking path.

**Keep:** `STRATEGY_KEEP_ONLY_LATEST`, `CaptureLoopGate`, `FrameFreshnessGate`.
The freshness gate changes meaning — it now guards against processing overrun
rather than network lag (`ARCHITECTURE.md` §8.3) — but it stays.

**Delete the LAN inference path. Do not keep a toggle.**

An earlier revision of this plan kept the FastAPI path as a runtime-switchable
fallback. That is **cancelled**. The demo fallback is the previous project,
running on its own RTX 4060 machine, shown side by side. That is a better
fallback than a toggle in three ways: it cannot regress the new build, it needs
no code, and a judge seeing both architectures running at once understands the
migration better than any slide.

So, in the walking path:

- **Delete** the `api.analyze` call, the multipart assembly, and
  [`FrameEncoder`](apps/android/app/src/main/java/com/drishti/app/walk/FrameEncoder.kt)
  usage. `FrameEncoder` itself stays — hazard evidence and OCR stills still need
  a JPEG.
- **Delete** `maxImageBytes` / `recommendedCaptureFps` / `maxResultAgeMs`
  negotiation from `startSession`. Pacing is now a local decision.
- **Delete** the connection-lost speech and the retry loop from the walk path.
  There is no connection to lose.

> **MUST:** the only backend selector that survives is **NPU / GPU / CPU**, and
> it exists for the §6.2 evidence set — not as a degradation rung. There is no
> runtime path from the walking loop to the network, in any state, by any toggle.
> `ARCHITECTURE.md` §19.3: reintroducing a network dependency under failure
> conditions restores exactly the coupling this migration exists to remove, at
> the moment the user can least tolerate it.

> This also removes roughly 200 lines from
> [`WalkController`](apps/android/app/src/main/java/com/drishti/app/walk/WalkController.kt)
> and deletes an entire class of failure from the demo. It is a simplification,
> not a sacrifice.

**Acceptance (P0.6, no phone):** compiles; unit tests for letterbox →
un-letterbox round-trip and for NMS against a fixture. 
**Acceptance (E3, phone):** ten controlled frames produce aligned, fresh
detections with no unbounded queue and no FastAPI call in the walking path.

---

## A7 — The accelerator scheduler

**Slot:** P0.6 · **Gate:** R2 · **Phone:** not needed to write

**Task.** One scheduler, serialised, priority-ordered
(`ARCHITECTURE.md` §8.1, §16.1).

```
priority 1: detection     (every frame)
priority 2: segmentation  (every Nth frame, N=1 by default — see §2.2)
priority 3: Class B       (never concurrent with a walk frame in flight)
```

- **MUST** route every accelerator submission through it. Two concurrent
  submissions on one NPU at best serialise internally and at worst fault or
  exhaust shared memory — and the failure is intermittent, which means it appears
  during the demo and not during testing.
- **MUST** hold at most one in-flight inference and one replaceable pending
  frame. Latest wins; older frames are dropped, not queued.
- **MUST** pass immutable snapshots between stages. A shared mutable detection
  list read by the overlay while the tracker mutates it is a crash that appears
  only under load.
- **MUST NOT** let the telemetry path block anything. Bounded queue, capacity
  ~50, drop-oldest, never blocks on enqueue.

**Also build the diagnostics panel here** (`ARCHITECTURE.md` §7.3 — a MUST, and
the best demo prop in the build). Per stage: backend actually in use
(NPU / GPU / CPU), inference ms, rolling FPS, resident memory, thermal status
polled every 5 s, and free `availMem`.

**Acceptance:** a unit test proving two concurrent `submit` calls serialise and
that a third submission while one is in flight replaces the pending frame rather
than queueing.

---

## A8 — Explore Mode (OCR) **[COMPLETE]**

**Slot:** E-window, 30 min · **Gate:** R5-adjacent · **Phone:** verified

```kotlin
implementation("com.google.mlkit:text-recognition:16.0.1")
```

Wire into the existing
[`ExploreController`](apps/android/app/src/main/java/com/drishti/app/explore/ExploreController.kt),
replacing the `api.readText` call. Extract text and any route-number token
(`BUS 42A CENTRAL` → `42A`). Emit `OcrConfidenceQualification` of
`HIGH | LOW | NONE`.

> **MUST** speak the qualification, not just the text. *"Low confidence: bus four
> two A"* is honest; reading it flatly implies a certainty the model did not have
> (`ARCHITECTURE.md` §18.1).

> ML Kit runs on-device and does **not** run on the NPU. If a judge asks, say so.
> It keeps Explore Mode alive; it does not strengthen the on-device-AI claim.

**Acceptance:** a printed sign is read aloud with its qualification, and Walk
guidance continues uninterrupted throughout.

**Landed 12 September 2026.** `ExploreController` no longer receives a
`DrishtiApi`: the multipart JPEG upload, conflict retry and transport-error path
were deleted. `OnDeviceTextReader` opens the bundled Latin recognizer for one
still, computes character-weighted element confidence, closes it, and returns
the existing `ReadTextResponse` shape. The original route-token regex is ported
exactly. `READING` now keeps the walking inference loop active, so safety speech
can pre-empt OCR instead of leaving a blind interval.

Verification on the current 12 GB iQOO 15 (Android 16): an instrumented test
rendered `BUS 42A` into a JPEG on-device, passed it through the production
reader, and asserted both recognized text and route `42A`. Result: **1 test,
0 failures**, complete test case **89 ms**; JVM suite: **56 tests, 0 failures**.

---

## A9 — The narrowed coordinator

**Slot:** P0.4 · **Gate:** R7 · **Phone:** not needed

> **Why a backend exists at all.** It does not serve the product. The phone
> owns every frame, every inference and every guidance decision, and it would run
> identically if this service never existed. The backend exists for exactly one
> reason: **the React dashboard is an HTTP client and needs something to talk
> to.** Nothing else. If the dashboard were cut, the backend would be cut with
> it in the same commit.
>
> Scope it accordingly. This is a **data shim for one web page**, not a service.
> Target ~200 lines and one process. If it grows past that, something has been
> carried over that should have been deleted.

**Task.** Create `apps/coordinator/` from `entire-old-codebase/backend/`,
carrying **only** what `ARCHITECTURE.md` §19.1 keeps:

| Keep | Drop |
|---|---|
| `/api/v1/health` | `/api/v1/walk/analyze` — **the entire frame-ingress path** |
| Telemetry envelope ingestion | `perception/` — detector, segmenter, tracking |
| Hazard CRUD, recurrence, accessibility scoring | `risk/`, `spatial/`, `guidance/` |
| CSV / JSON export | `explore/local_vlm.py`, `api/vlm.py` |
| Dashboard REST + bounded live feed | `scheduling/latest_frame.py` |
| SQLite + Alembic migrations | `frame_ingress.py` |

Dependencies drop to `fastapi uvicorn sqlalchemy pydantic pydantic-settings` —
no torch, no ultralytics, no opencv, no transformers, **and no Alembic**. Three
migration files for a throwaway hackathon database is ceremony; use
`Base.metadata.create_all()` on startup and delete `db/migrations/` entirely.

> **MUST NOT** port `walk_sessions.py`, `frame_ingress.py`, `request_limits.py`,
> or any `/api/v1/walk/*` route. The phone does not call them. A route that
> exists but is never called is a route that will be discovered at hour 26 and
> mistaken for a dependency.

> **MUST NOT** let it receive the CameraX frame stream (`ARCHITECTURE.md` §19.3).
> **MUST NOT** include the Moondream2 locator — see P0.4's note on the 6 GB card.
> **MUST NOT** let reconnection replay stale safety instructions. Telemetry is a
> record of what already happened, not a command channel.

Adapt the health and model panels so a phone NPU execution is never displayed as
consuming laptop VRAM (Appendix A of `ARCHITECTURE.md`).

**Acceptance:** `uvicorn` starts; `/api/v1/health` answers;
`npm run dev --workspace apps/dashboard` renders against it; **and killing the
coordinator changes nothing about a running phone build.**

---

## A10 — Ask → Lock → Guide

**Slot:** E7 · **Gate:** R6 · **Phone:** to verify

**Task.** Port [`target_guidance.py`](entire-old-codebase/backend/app/guidance/target_guidance.py)
and wire it to [A5](#a5--landmark-memory) and the on-device tracker. Resolution
order per `ARCHITECTURE.md` §14.1 — landmark memory first, live detections
second, **no VLM in the path**.

`parseLocateTarget` already exists in
[`WalkController.kt:80`](apps/android/app/src/main/java/com/drishti/app/walk/WalkController.kt:80)
and is already unit-tested. Keep it.

> **MUST** suppress target speech and target spatial audio whenever the risk
> action is anything other than `CLEAR`. Safety wins immediately and the target
> cue is **dropped, not queued** (`ARCHITECTURE.md` §3.2, §13.4).

**Acceptance:** target lock from memory, tracking, loss, rescan, and safety
preemption all pass with no VLM invoked.

---

# PART 2 — Loaner bring-up

**[HUMAN] · E1 · 45 minutes · This is a hard gate.**

The loaner is in your hands. Do these in order. **Nothing else starts until
items 1–4 are answered** (`ARCHITECTURE.md` §24).

## E1.1 — The four blocking unknowns

| # | Question | How | If bad |
|---|---|---|---|
| 1 | **RAM variant?** | Settings → About, or `adb shell cat /proc/meminfo \| head -1` | Neither is bad. Record it and apply §4. |
| 2 | **Can we install a custom APK?** | `adb install -r app-debug.apk` | **The entire plan is void. Escalate to organisers immediately.** |
| 3 | **Can custom model files load?** | Assets are inside the APK, so this follows from 2 | AI Hub prebuilt only |
| 4 | **Is the NPU reachable from a third-party app?** | E2 | GPU path, and the on-device-AI claim weakens sharply. **Say so plainly rather than implying NPU.** |

## E1.2 — Enable the device

1. Settings → About phone → tap **Build number** seven times.
2. Settings → System → Developer options → **USB debugging** on.
3. Also enable **Install via USB** and **USB debugging (Security settings)** —
   OriginOS gates sideloading behind these and they are separate toggles. This
   step may require a signed-in vivo account.
4. Plug in with a **data** cable. Accept the RSA fingerprint prompt on the phone.
5. `adb devices` → one device, state `device` (not `unauthorized`, not
   `offline`).
6. Disable battery optimisation for the app once installed — OriginOS is
   aggressive about background foreground-services.
7. Developer options → set **Don't keep activities** OFF, and animation scales
   to 0.5× to make UI debugging faster.

```bash
adb devices -l && adb shell getprop ro.product.model && adb shell getprop ro.board.platform && adb shell cat /proc/meminfo | head -3
```

Record the device serial. `ARCHITECTURE.md` §7.4 requires it beside every quoted
measurement.

## E1.3 — Install the Part 0 baseline

```bash
cd apps/android && ./gradlew installDebug
```

The app should start, show the camera preview, and run the on-device detector
with no coordinator anywhere in sight. It proves the APK installs, the camera
binds, the UI runs, and the NPU session creates on **this** device rather than on
the playground.

If the dashboard is not up yet, nothing should complain. There is no network path
in the walking loop to complain about.

> **GATE E1 — passed when the Part 0 APK is installed and running on the loaner
> with a live camera preview.** If it is not passed at T+1 h, you are in the
> unknown-2 failure branch and the problem is organisational, not technical. Go
> find an organiser.

## E1.4 — Office Kit

`ARCHITECTURE.md` §20.4's enablement gate. Attempt it **once**, timeboxed to 15
minutes, and move on.

- Does Remote PC work on this firmware?
- Does `adb devices` still show the iQOO while Remote PC is active?
- Does `adb install -r` survive without killing the control session?

**If it fails:** mirror-and-input only, builds driven at the laptop directly.
**MUST NOT** rebuild the Android toolchain inside Termux — that is a rejected
workflow (`ARCHITECTURE.md` §20.5) and it will consume the rest of the event.

> Whatever you conclude: **pause mirroring for every measurement you intend to
> quote**, and label the measurement accordingly. Mirroring adds display, encode,
> network and thermal load, and a benchmark captured with it active is not
> comparable to one captured without (`ARCHITECTURE.md` §20.2).

---

# PART 3 — Operating DRISHTI on the phone

**[OPERATOR]**

## 3.1 Build, install, run

```bash
cd apps/android && ./gradlew installDebug
```

```bash
adb shell am start -n com.drishti.app.debug/com.drishti.app.MainActivity
```

Note the `.debug` suffix — [`build.gradle.kts`](apps/android/app/build.gradle.kts)
sets `applicationIdSuffix = ".debug"`.

## 3.2 Watch the logs

```bash
adb logcat --pid=$(adb shell pidof com.drishti.app.debug) -v time
```

Filter for the inference path:

```bash
adb logcat --pid=$(adb shell pidof com.drishti.app.debug) -v time | grep -Ei "qnn|htp|onnx|backend|npu|thermal"
```

## 3.3 Pushing a model without rebuilding

Faster than a full APK cycle when iterating on a model:

```bash
adb push models/staging/segformer/model.onnx /sdcard/Android/data/com.drishti.app.debug/files/models/
```

The app **SHOULD** prefer a model in its external files dir over the bundled
asset when one is present, and say which it loaded in the diagnostics panel.
Ship the final demo build with the asset, not the pushed file.

## 3.4 Memory and thermal, live

```bash
adb shell dumpsys meminfo com.drishti.app.debug | head -25
```

```bash
adb shell dumpsys thermalservice | grep -Ei "status|temperature" | head -20
```

```bash
adb shell cat /proc/meminfo | head -3
```

## 3.5 Runtime gestures

| Gesture | Mode |
|---|---|
| Start / stop | Walk |
| Explore gesture | One OCR read, then back to Walk |
| Ask gesture + spoken phrase | Find (landmark memory) or Scene |
| SOS | Alert |

Walk Mode is **screen-on** for this build (`ARCHITECTURE.md` §2.5). Keep
brightness low during soaks — it is a real thermal contributor.

## 3.6 Demo hygiene

- **Do not charge while demoing.** Charging plus sustained NPU load throttles
  much faster than either alone.
- Reboot and close everything before a measured run.
- Aeroplane mode is **not** the independence proof. Power the coordinator off —
  that is the proof `ARCHITECTURE.md` §23 asks for, and §2.5 explicitly excludes
  the radio-off stunt.

---

# PART 4 — Measurement and evidence

**[HUMAN] · continuous**

Create `docs/EVIDENCE_LOG.md` at the start of Part 0 and append to it as you go.
`ARCHITECTURE.md` §7.4 requires every quoted number to carry its provenance.

## 4.1 The row format

| Field | Example |
|---|---|
| Timestamp | `T+04:12` |
| Git commit | `722dcde` |
| APK SHA-256 | `…` |
| Model asset SHA-256 | from `MANIFEST.sha256` |
| Device serial | `…` |
| RAM variant | 12 GB / 16 GB |
| Office Kit mirroring active? | **yes / no** |
| Measurement | the number |
| Backend evidence | logcat excerpt or the fallback-guard result |

## 4.2 The measurements that must be in the log

| ID | Measurement | Decision rule |
|---|---|---|
| **4.1** | Free `availMem` after reboot | §4.1 — gates the VLM tier |
| **7.3.1** | Inference ms with QNN EP vs CPU EP, **plus** the backend the runtime reports | Both, or the NPU claim is not made |
| **8.4.1** | Sustained **camera-to-guidance** frame time over 10 min | ≥ 8 fps → ship as designed. 4–8 → segmentation every 3rd frame. < 4 → segmentation off, announce |
| **17.4.1** | Frame time at minutes 1, 5, 10, 20 of a continuous walk | Minute 20 within 2× of minute 1 |
| **P0.3.4** | SegFormer ONNX output shape | §P0.3.4 |
| **A6.1** | The ORT Java QNN registration API in the resolved AAR | — |

> **MUST NOT** quote a published model-card figure as an application benchmark.
> Qualcomm's 2.268 ms is a raw model profile on a reference device. It excludes
> preprocessing, tensor copies, postprocessing, camera conversion, audio, and
> thermal throttling. **MEASURE 8.4.1 is the number that matters**, and it will
> be several times larger. Quote it, and quote the model figure separately and
> labelled as what it is.

## 4.3 The §23.2 functional checks

Run all eighteen from `ARCHITECTURE.md` §23.2 at E9 and record pass/fail.

> Tests **8, 9 and 10** — camera covered, lens smeared or low light, and a
> direction change under persistence — are the ones most likely to be skipped
> under time pressure and the most important in the set. They verify the system
> admits uncertainty rather than inventing confidence. **Do not skip them.**

> **MUST NOT test blindfolded** (`ARCHITECTURE.md` §23.3). A sighted tester holds
> the phone and evaluates whether the guidance *would have been* correct and
> sufficient.

---

# PART 5 — Demo runbook

**[OPERATOR] · E11**

## 5.1 Pre-demo, 10 minutes before

- [ ] Phone rebooted, everything closed, **not charging**
- [ ] Brightness low
- [ ] Office Kit mirroring on for the audience, and you have already recorded
      your quoted numbers with it off
- [ ] Coordinator running on the laptop, dashboard open
- [ ] Diagnostics panel reachable in one gesture
- [ ] A printed sign for Explore Mode within reach
- [ ] The corridor walked once already, this session

## 5.2 The sequence

1. **Open on the diagnostics panel.** Backend: NPU. Inference: single-digit
   milliseconds. Thermal: nominal. This is the technical claim, stated before any
   narrative.
2. **Walk the clear corridor.** `CLEAR` / `PATH_CLEAR`, floor polygon on the
   overlay, spatial audio centred.
3. **Put an obstacle in the centre, left side open.** `MOVE_LEFT` /
   `CENTRE_BLOCKED_CLEARER_SIDE`. Speech, overlay and audio agree on the same
   frame.
4. **Cover the camera.** `PAUSE_UNCLEAR`. **This is the beat that matters.** Say
   out loud: it does not guess, and it does not say clear. Uncertainty and danger
   are different answers, and the system has a word for each.
5. **Walk at a wall.** `WALL_OR_DEAD_END_AHEAD`, and no movement cue into it.
6. **Power the laptop coordinator off, mid-walk. Then put the phone in
   aeroplane mode.** Nothing happens either time. Keep walking. The dashboard
   dies; the guidance does not. *This is the whole argument*, and the radio-off
   half is the version a non-technical judge feels immediately (§6.2 proof 4).
7. **Find.** Ask for something walked past thirty seconds ago. It locks from
   landmark memory — **no extra inference, no VLM**.
8. **Explore.** Read the sign, with its confidence qualification spoken.
9. **Back to diagnostics.** Toggle the backend to CPU. Watch the millisecond
   count collapse. Toggle back.
10. **If the old project is set up on its RTX 4060 machine, run it alongside.**
    Same corridor, same obstacle — one architecture sending every frame over
    Wi-Fi to a laptop GPU, one doing it all on the phone. The comparison argues
    the migration better than any slide, and it doubles as the demo's safety net
    (§6.3).

## 5.3 Answering the hard questions honestly

| Question | Answer |
|---|---|
| "Is it really the NPU?" | Session creation runs with `disable_cpu_ep_fallback=1`, so a CPU fallback is a thrown exception, not a silent slowdown. Plus the logcat backend line and the CPU-EP comparison. |
| "Is the OCR on the NPU?" | No. ML Kit, on-device, CPU. It keeps the feature alive and it is not part of the NPU claim. |
| "Can it tell me it's safe to cross?" | No, and it never will. It reports what it detects. It never certifies. That is a design rule, not a limitation. |
| "How far away is that?" | It does not say. A single camera cannot measure distance. Relative bands only. |
| "Does it detect doors?" | No. `door` is not a COCO class. Segmentation may mark a door-shaped non-walkable surface; that is a surface, not a door detection. |
| "What if the laptop dies?" | You just watched it die. |

> **MUST NOT** advertise a capability the deployed models cannot produce. Every
> row above is a §3.1 obligation, not modesty.

---

# PART 6 — Triage and cut lines

## 6.1 The degradation ladder

`ARCHITECTURE.md` §21, unchanged. Descend in order, never skip. Every level from
4 down is **announced out loud**.

Losing the coordinator is **not on this ladder.** It costs the dashboard and
nothing else.

## 6.2 Time-pressure cut order

Cut from the bottom up:

1. E10 — phone VLM
2. A8 — Explore Mode / OCR
3. E8 — coordinator and dashboard
4. E7 — Ask → Lock → Guide
5. E6 — segmentation (announce the degradation)

**Never cut:** E9 (verification) or E11 (freeze and rehearsal). A demo that
works and cannot be explained, or that has never been walked end to end, will
fail in front of judges in a way that a smaller verified demo will not.

### 6.2.1 The real fallback is a second machine, not a code path

If the phone build fails outright, **demo the previous project on its RTX 4060
machine.** It works, it is the same product, and it is a separate computer that
cannot be broken by anything done to the phone build.

> **MUST** keep that machine's checkout on the last known-good commit and
> **MUST NOT** point the coding agent at it during the event. Its entire value is
> that it is untouched. A fallback that shares a repository with the thing it is
> falling back from is not a fallback.

This is why the LAN inference toggle was deleted from the APK ([A6](#a6--the-ondevicedetector-seam)):
a separate working machine is a strictly better safety net than a switch inside
the build that might fail.

## 6.3 If a gate fails

| Failure | Response |
|---|---|
| GATE P0.1 — no build | Stop everything else. Nothing matters until this is green. |
| GATE E1 — cannot install | Organisational. Escalate immediately. Do not debug alone. |
| GATE E2 — no NPU | Descend §3.3's runtime ladder. Record honestly. The product still works on GPU. |
| SegFormer session will not create | §3.2 version mismatch first (Appendix C.4), then the float build, then omit and announce |
| YOLO context binary rejected | §3.3 rung 3 — the plain ONNX, compiled at session creation |
| Golden vectors fail parity | **Fix the Kotlin, never the vector.** The Python is the specification. |
| Thermal throttling in the soak | §17.3 ladder. It should not fire at 8 ms of NPU time; if it does, the cost is elsewhere — profile the CPU path. |

## 6.4 The one thing that cannot be traded

> Under time pressure, someone — possibly you, at hour 22 — will suggest "just
> say it's clear if we don't detect anything." That is a `docs/SAFETY_RULES.md`
> violation and the answer is no. The user is blind and cannot cross-check what
> the phone tells them. A confident wrong answer is the most dangerous possible
> output, and shipping one is worse than shipping nothing.

---

# Appendix A — model manifest

Stage all of these during P0.3. **MUST** record every SHA-256 in
`models/staging/MANIFEST.sha256`.

| # | Asset | Source | Size | Needed for |
|---|---|---|---|---|
| 1 | `yolo11n.pt` | `https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n.pt` | 5.4 MB | **Required.** SHA-256 `0EBBC80D4A7680D14987A577CD21342B65ECFD94632BD9A8DA63AE6417644EE1` |
| 2 | `yolo11n_qnn.onnx` | Exported locally, P0.3.3 | ~4 MB | **Required.** w8a16, HTP v81 context binary |
| 3 | `yolo11n.onnx` | Exported locally, P0.3.3 | ~10 MB | Rung-3 fallback |
| 4 | `segformer_base-onnx-w8a16.zip` | [S3](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-onnx-w8a16.zip) | ~5 MB | **Required** for segmentation |
| 5 | `segformer_base-onnx-float.zip` | [S3](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-onnx-float.zip) | ~15 MB | Parity reference; fallback if w8a16 drops the confidence map |
| 6 | `segformer_base-qnn_dlc-w8a16.zip` | [S3](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/segformer_base/releases/v0.62.1/segformer_base-qnn_dlc-w8a16.zip) | ~5 MB | Alternative runtime fallback |
| 7 | `ade20k_config.json` | `entire-old-codebase/models/segmentation/segformer-b0-ade20k/config.json` | 8 KB | **Required.** The 150-entry `id2label` |
| 8 | `easyocr-onnx-w8a8.zip` | [S3](https://qaihub-public-assets.s3.us-west-2.amazonaws.com/qai-hub-models/models/easyocr/releases/v0.62.1/easyocr-onnx-w8a8.zip) | ~25 MB | Stretch only |
| 9 | **`LFM2.5-VL-1.6B-Q4_K_M.gguf`** + `mmproj-...-Q8_0.gguf` | [HF, open](https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF) | 731 MB + 583 MB | **Tier 1 Scene model.** See [`docs/SCENE_MODE_VLM.md`](docs/SCENE_MODE_VLM.md) |
| 10 | `LFM2.5-VL-450M-Q4_K_M.gguf` + mmproj | [HF, open](https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF) | 229 MB + 103 MB | Tier-2 fallback. Costs nothing to stage |
| 11 | `gemma-3n-E2B-it.litertlm` | [HF, gated](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm) | ~3.1 GB | 16 GB stretch |
| 12 | `gemma-3n-E4B-it.litertlm` | [HF, gated](https://huggingface.co/google/gemma-3n-E4B-it-litert-lm) | ~4.4 GB | 16 GB stretch |

**Not used, and why:**

| Rejected | Reason |
|---|---|
| NexaSDK | Documented 16 GB Android floor; every capability reachable another way without the gamble |
| Qwen3-VL-2B / 4B on NPU | AI Hub mobile deployment is not reachable in an event window (§2.4) |
| SegFormer Cityscapes | Deletes indoor floor/wall/door/stairs semantics. Forbidden by `ARCHITECTURE.md` §6.2 |
| YOLOv8 / YOLOX | YOLO11 passes at 2.27 ms. No measured reason to switch, and switching costs a post-processing review |
| Moondream2 laptop locator | 6 GB card, and rung 3 covers the demo beat |
| Moondream2 **on the phone** | No quantized GGUF is published — f16 only, 3.75 GB with the projector. Would need self-quantizing, which is an hour on the critical path for a narrative benefit |
| The LAN inference toggle in the APK | Deleted. The fallback is the previous project on its own RTX 4060 machine — a separate computer beats a switch that can itself fail ([§6.2.1](#621-the-real-fallback-is-a-second-machine-not-a-code-path)) |

> **Licensing.** YOLO11 is **AGPL-3.0** — the same posture the parent project
> already recorded for this internal prototype. SegFormer-Base and EasyOCR on
> AI Hub are Apache-2.0. Gemma is under Google's gated terms. Note it if a judge
> asks; it does not block a hackathon prototype.

---

# Appendix B — command cheat sheet

**Build and install**

```bash
cd apps/android && ./gradlew installDebug
```

**Unit tests, including golden vectors**

```bash
cd apps/android && ./gradlew test
```

**Clean rebuild when Gradle gets confused**

```bash
cd apps/android && ./gradlew --stop && ./gradlew clean assembleDebug
```

**Launch**

```bash
adb shell am start -n com.drishti.app.debug/com.drishti.app.MainActivity
```

**Filtered logcat**

```bash
adb logcat --pid=$(adb shell pidof com.drishti.app.debug) -v time
```

**Memory**

```bash
adb shell dumpsys meminfo com.drishti.app.debug | head -25
```

**Thermal**

```bash
adb shell dumpsys thermalservice | grep -Ei "status|temperature"
```

**APK checksum for the evidence log**

```bash
sha256sum apps/android/app/build/outputs/apk/debug/app-debug.apk
```

**Coordinator**

```bash
cd apps/coordinator && .venv/Scripts/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

**Dashboard**

```bash
npm run dev --workspace apps/dashboard
```

**Golden vector export**

```bash
cd entire-old-codebase/backend && .venv-vectors/Scripts/python scripts/export_golden.py
```

**Re-export YOLO for a different HTP target**

```bash
cd /c/Users/yasha/Documents/drishti-edge-yash && .venv-export/Scripts/yolo export model=models/staging/yolo11n.pt format=qnn name=81 imgsz=640 batch=1
```

---

# Appendix C — troubleshooting

**C.1 — `adb server version doesn't match this client`** 
Two adb installations. See P0.1.2. `adb kill-server`, fix PATH, `adb start-server`.

**C.2 — `adb devices` shows nothing** 
In order: charge-only cable; OriginOS "Install via USB" not enabled; RSA prompt
not accepted (unplug, replug, watch the phone screen); USB mode set to charging
only in the notification shade.

**C.3 — Gradle fails with a JDK path error** 
`org.gradle.java.home` still points at the Linux path. P0.1.3.

**C.3a — `gradlew` dies with `java.net.ConnectException: Connection timed out`
while downloading the distribution — OBSERVED ON THIS LAPTOP**

`services.gradle.org` 307-redirects to a GitHub release asset and then to
`release-assets.githubusercontent.com`. The Gradle wrapper's Java HTTP client
times out on that chain even where `curl` pulls the same file at full speed, so
it is **not** a bandwidth problem and re-running `gradlew` will not fix it.

Seed the wrapper cache by hand. The wrapper creates its hashed directory
*before* it fails, so reuse that directory rather than guessing the hash:

```bash
find ~/.gradle/wrapper/dists -maxdepth 2 -type d
```

```bash
D=~/.gradle/wrapper/dists/gradle-9.1.0-bin/<hash> && rm -f "$D"/*.part "$D"/*.lck && curl -L --retry 3 -o "$D/gradle-9.1.0-bin.zip" https://services.gradle.org/distributions/gradle-9.1.0-bin.zip
```

Verify against `distributionSha256Sum` in
[`gradle-wrapper.properties`](apps/android/gradle/wrapper/gradle-wrapper.properties),
then re-run `gradlew` — it finds the zip and skips the download.

> **Beware the masking pipe.** `./gradlew assembleDebug 2>&1 | tail -40` reports
> the exit status of **`tail`**, which is 0 even when Gradle failed. A build that
> "succeeds" while leaving nothing in `app/build/outputs/apk/debug/` is this.
> Check for the artifact, or use `set -o pipefail`.

**C.4 — ORT session creation throws on the SegFormer model** 
Version mismatch first — the assets declare ONNX Runtime 1.27.1, and an older
`onnxruntime-android-qnn` fails opaquely (§3.2). Then try the float build. Then
the QNN DLC build. Then omit segmentation and announce the degradation.

**C.4a — Session creation fails with a backend-initialisation error, no mention
of the DSP**
Either the QAIRT libraries are missing from `jniLibs/arm64-v8a/` (§3.4), or
`ADSP_LIBRARY_PATH` was not set before the first session, or the stub/skel
version does not match the SoC (`V81` for both SM8850 and SM8845). Check all
three, in that order.

**C.5 — ORT session creation throws on YOLO with the fallback guard on** 
That is the guard working. Something in the graph is not HTP-supported. Retry
without the guard, read which nodes fell back, and record it. If the fallback is
partial and small, the NPU claim still holds for the bulk of the graph — say
exactly that, not more.

**C.6 — Boxes are drawn slightly wrong, like camera jitter** 
Un-letterboxing. The boxes are normalised against the 640×640 tensor instead of
the full oriented capture. `ARCHITECTURE.md` §9.3, and the test in §10.3 — a
`(0,0,1,1)` box must trace the full oriented capture in both orientations.

**C.7 — The app freezes after about five seconds** 
A leaked `ImageProxy`. `close()` is missing on some path, probably an exception
path. `ARCHITECTURE.md` §9.1 — the symptom looks nothing like the cause.

**C.8 — Guidance stutters at a regular cadence** 
Per-frame `Bitmap` allocation causing a GC storm. `ARCHITECTURE.md` §5.3.

**C.9 — The process is killed mid-walk** 
Low-memory killer. A Class B model was loaded without the free-memory check, or
`SAFETY_MARGIN_BYTES` was tuned down to make something work.
`ARCHITECTURE.md` §5.2 — it is 800 MB and it is not negotiable.

**C.10 — Everything works, then degrades after twenty minutes** 
Thermal. Check `dumpsys thermalservice`, confirm the §17.3 ladder is firing and
announcing, and confirm you are not charging. At ~8 ms of NPU time per frame the
cost is more likely on the CPU path — profile YUV conversion, letterboxing, and
NMS before blaming the accelerator.

---

## Note on scope

This document plans a build. It does not amend the safety contract, and it does
not amend the typed contracts in `packages/contracts/`. The
`ARCHITECTURE.md` Appendix A amendments remain **proposals** until recorded in a
decisions log with tests in Python, TypeScript, and Kotlin.

The three corrections this document makes to `ARCHITECTURE.md` — the segmentation
gate's expected outcome (§2.2), the AI Hub banner's evidential weight (§2.3), and
the runtime path (§3) — are each backed by a measured or published figure cited
inline. Everything else in `ARCHITECTURE.md` stands as written.
