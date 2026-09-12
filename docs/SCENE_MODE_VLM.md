# Scene Mode — the on-device vision-language model

> **Variant confirmed: iQOO 15, 16 GB.** This file previously hedged across both
> SKUs. It no longer needs to.
>
> **Status:** contingency. Nothing on the safety path depends on it, it is the
> last item in the build, and it is the first thing cut under time pressure
> ([`../BUILD_PLAN.md` §6.2](../BUILD_PLAN.md#62-time-pressure-cut-order)).
>
> **Subordinate to:** [`SAFETY_RULES.md`](SAFETY_RULES.md) and
> [`../ARCHITECTURE.md` §5](../ARCHITECTURE.md#5-memory-architecture). A VLM is a
> **Class B** model and every Class B rule applies without exception.

---

## 1. What 16 GB actually buys

| | 12 GB | **16 GB (actual)** |
|---|---|---|
| Headroom, post-reboot | ~5.5 – 6.5 GB | **~9 – 10 GB** |
| Headroom, realistic daily-use state | ~3.6 GB *(measured)* | **~7 GB** *(estimate — MEASURE it)* |
| Class B budget after the 800 MB margin | ~2.8 GB | **~6 GB** |
| Largest safe Scene model | LFM2.5-VL-1.6B (1.3 GB) | **Gemma 3n E4B (4.4 GB)** |

Everything else — detection, segmentation, tracking, corridor geometry, the risk
cascade, speech, spatial audio — is **byte-identical** on both. The extra 4 GB
touches exactly one capability.

> **MEASURE VLM.1** — read `ActivityManager.MemoryInfo.availMem` on the loaner in
> the state it will be in at demo time, **not** freshly rebooted. On a measured
> 12 GB device in daily use the figure was 3.6 GB against a 5.5 GB post-reboot
> expectation. Assume a comparable gap here.
>
> **Decision rule:** ≥ 6 GB free → tier 2 (E4B). 3 – 6 GB free → tier 1
> (LFM2.5-VL-1.6B). < 3 GB free → tier 0, no phone VLM, say so plainly if asked.

---

## 2. The tier ladder

| Tier | Model | Total size | Runtime | When |
|---|---|---|---|---|
| **0** | **Detection-derived scene summary — no VLM** | 0 | Kotlin | **Default. Always available. Cannot fail.** |
| **1** | **LFM2.5-VL-1.6B** Q4_K_M + mmproj Q8_0 | **1.31 GB** | llama.cpp `libmtmd` | The safe real VLM |
| 2 | Gemma 3n E4B `.litertlm` | ~4.4 GB | LiteRT-LM / MediaPipe | What 16 GB unlocks |
| 3 | LFM2.5-VL-450M Q4_K_M + mmproj | 0.33 GB | llama.cpp | If tier 1 is too slow |

> **Tier 0 is not a failure state.** *"A person ahead on the left, a chair to the
> right, a doorway centre"* composed from the detector's existing output is
> genuinely useful, costs zero memory, adds zero latency, and cannot fail
> (`ARCHITECTURE.md` §6.4 rung 3). **It ships regardless.** Tiers 1–3 are
> additive, never load-bearing.

### 2.1 Why tier 1 before tier 2, even at 16 GB

Tier 2 is the bigger model, but tier 1 is the better bet:

- **1.31 GB fits any plausible memory state**, including the pessimistic one that
  MEASURE VLM.1 is likely to return.
- **Liquid AI built LFM2-VL for on-device edge inference** rather than shrinking a
  server model, and it is the most-downloaded GGUF VLM family by a wide margin.
- **It degrades to the 450M sibling with no code change** — identical loader,
  identical prompt path.

Attempt tier 2 only if tier 1 works *and* every gate before it is green.

### 2.2 Ruled out

| Model | Why not |
|---|---|
| Moondream2 on the phone | No quantized GGUF published — f16 only, **3.75 GB** with the projector. Self-quantizing is an hour on the critical path for a narrative benefit |
| Gemma 3n E2B | Strictly dominated: same runtime as E4B, less capable, and 16 GB has room for E4B |
| Qwen3-VL 2B / 4B on NPU | AI Hub mobile deployment is not reachable in an event window (`BUILD_PLAN.md` §2.4) |

---

## 3. Sizes — actual Hugging Face blob sizes, not estimates

A vision model needs **two** files — the language model and the `mmproj` vision
projector. People routinely budget only the first.

| Model | Text GGUF | mmproj | **Total** |
|---|---|---|---|
| LFM2.5-VL-1.6B Q4_K_M + Q8_0 | 731 MB | 583 MB | **1.31 GB** |
| LFM2.5-VL-450M Q4_K_M + Q8_0 | 229 MB | 103 MB | **0.33 GB** |
| SmolVLM2-500M Q8_0 + Q8_0 | 437 MB | 109 MB | 0.55 GB |
| Moondream2 (f16 only) | 2,840 MB | 910 MB | 3.75 GB |

Sources: [LiquidAI/LFM2.5-VL-1.6B-GGUF](https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF),
[LiquidAI/LFM2.5-VL-450M-GGUF](https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF),
[ggml-org/SmolVLM2-500M-Video-Instruct-GGUF](https://huggingface.co/ggml-org/SmolVLM2-500M-Video-Instruct-GGUF),
[moondream/moondream2-gguf](https://huggingface.co/moondream/moondream2-gguf).

---

## 4. Integration cost — stated honestly

**This is why it stays a contingency.** There is **no official Maven AAR** for
llama.cpp with multimodal support.

| Path | Cost | Risk |
|---|---|---|
| Build `llama.android` JNI from the llama.cpp tree with the NDK | 2 – 4 h | NDK, CMake, ABI filters, `mtmd` wiring. Each step known; there are many |
| A community-published AAR | 0.5 – 1 h | Unvetted supply chain, unknown `mtmd` version |
| LiteRT-LM / MediaPipe (tier 2 only) | 1 – 2 h | Does not take GGUF — `.litertlm` / `.task` only |

> **MUST NOT** start this before `BUILD_PLAN.md` gates E1–E9 are green. It is the
> last item in the build and the first cut.

> **MUST** do any NDK work on the playground device during Part 0, never inside
> the event window. A first NDK build at T+22 h is how the last eight hours
> disappear.

---

## 5. The Class B contract — non-negotiable

```
check free memory  →  refuse if below floor
       ↓
load model + mmproj
       ↓
run exactly ONE inference
       ↓
unload, release native buffers, verify reclaim
       ↓
return the result to the caller
```

- **MUST** return the result *after* the unload, never before.
- **MUST** keep `SAFETY_MARGIN_BYTES` at 800 MB. It is not tuned down to make a
  demo work.
- **MUST** refuse **audibly** — *"Scene mode is not available right now."* Walk
  Mode continues uninterrupted.
- **MUST NOT** be co-resident with any other Class B model, or run while a walk
  frame is in flight (`ARCHITECTURE.md` §16.1).
- **MUST NOT** appear anywhere in the continuous loop. A 1.6B VLM's latency is far
  too high for safety guidance, and any architecture that lets it try will
  eventually speak a stale answer about a scene the user has already walked past.
- **MUST** cancel deterministically on timeout. Start at 15 s; on timeout, unload
  and say so.
- **MUST NOT** assume dropping the Kotlin reference frees native memory. Call the
  explicit free, then **measure**. If reclaim cannot be proved, host it in a
  separate killable process and reclaim by killing it.

---

## 6. Pre-staging

Tier 1 is public — no licence gate, no account:

```bash
mkdir -p models/staging/vlm && curl -L -o models/staging/vlm/lfm25-vl-1.6b-q4km.gguf "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/LFM2.5-VL-1.6B-Q4_K_M.gguf" && curl -L -o models/staging/vlm/lfm25-vl-1.6b-mmproj-q8.gguf "https://huggingface.co/LiquidAI/LFM2.5-VL-1.6B-GGUF/resolve/main/mmproj-LFM2.5-VL-1.6b-Q8_0.gguf"
```

Tier 2 is **licence-gated** — accept the terms in a browser first:
[google/gemma-3n-E4B-it-litert-lm](https://huggingface.co/google/gemma-3n-E4B-it-litert-lm).

Record every SHA-256 in `models/staging/MANIFEST.sha256` (`ARCHITECTURE.md` §7.4).

> **Licence check.** LFM2.5-VL is published under the **LFM Open License**, not
> Apache-2.0. Read it and be able to state the terms. SmolVLM2 is Apache-2.0 and
> is the clean-licence fallback if the LFM terms are a problem for how the work
> is presented.

---

## 7. What this does and does not claim

| | |
|---|---|
| Runs on the phone | **Yes.** Fully offline, no network, no laptop |
| Runs on the **NPU** | **No.** llama.cpp and LiteRT-LM execute on CPU (optionally GPU) |
| Strengthens the on-device-AI / NPU claim | **No.** That rests entirely on YOLO11 and SegFormer through ORT QNN |
| Is on the safety path | **No, and MUST NOT become so** |
| Needed for the demo | **No.** Tier 0 covers the Scene Mode beat |

> **MUST NOT** present this as NPU inference. If a judge asks whether the VLM runs
> on the Hexagon NPU, the answer is **no — it runs on the CPU, and the NPU work is
> the detector and the segmenter.** That answer is stronger than a hedge, because
> the NPU claim it protects is the one backed by `disable_cpu_ep_fallback` and a
> measured comparison.
