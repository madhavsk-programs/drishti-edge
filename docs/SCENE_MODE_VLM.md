# Scene Mode — the on-device vision-language model

> **Variant confirmed: iQOO 15, 16 GB.** This file previously hedged across both
> SKUs. It no longer needs to.
>
> **Status:** **running on device** — LFM2.5-VL-450M answers inside the app
> in 1.3–1.8 s (§8). Nothing on the safety path depends on it, and it remains
> the first thing cut under time pressure
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
| Largest Scene model that *fits* | Qwen3-VL-2B (1.55 GB) | Qwen3-VL-4B (2.95 GB) |
| Largest that is **fast enough** (§4.5) | LFM2.5-VL-450M | **LFM2.5-VL-450M** |

Everything else — detection, segmentation, tracking, corridor geometry, the risk
cascade, speech, spatial audio — is **byte-identical** on both. The extra 4 GB
touches exactly one capability.

> **MEASURE VLM.1** — read `ActivityManager.MemoryInfo.availMem` on the loaner in
> the state it will be in at demo time, **not** freshly rebooted. On a measured
> 12 GB device in daily use the figure was 3.6 GB against a 5.5 GB post-reboot
> expectation. Assume a comparable gap here.
>
> **Memory decision rule.** ≥ 1.2 GB free → tier 1 (LFM2.5-VL-450M). Below that
> → tier 0, no phone VLM, say so plainly if asked. The threshold is the model's
> resident size plus the fixed 800 MB `SAFETY_MARGIN_BYTES` plus room for the KV
> cache and the decoded image; it is not the model size alone.
>
> **Memory is no longer the binding constraint — latency is.** Both larger tiers
> fit this phone comfortably and are still disqualified by §4.3.2. Do not widen
> this rule on the strength of free memory alone.

---

## 2. The tier ladder

| Tier | Model | Total size | Runtime | When |
|---|---|---|---|---|
| **0** | **Detection-derived scene summary — no VLM** | 0 | Kotlin | **Default. Always available. Cannot fail.** |
| **1** | **LFM2.5-VL-450M** Q4_K_M + mmproj Q8_0 | **0.33 GB** | llama.cpp `libmtmd` | **The shipping VLM. 1.4 s, meets the sub-7 s bound** (§4.5) |
| 2 | Qwen3-VL-2B-Instruct Q4_K_M + mmproj Q8_0 | 1.55 GB | llama.cpp `libmtmd` | **Only if §5 is amended** — ~13 s of load per call otherwise (§4.3.3) |
| 3 | Qwen3-VL-4B-Instruct Q4_K_M + mmproj Q8_0 | 2.95 GB | llama.cpp `libmtmd` | Best answers, ~16 s load. Not reachable under §5 |

**One runtime across every tier.** That is the point of this ladder: the tiers
differ only by which two files are on disk, so the choice between them was a
*measurement* rather than an engineering commitment — `libmtmd` was built once
and each GGUF timed. §4 is what that measurement found.

> **Decision rule, settled by measurement on 12 September 2026.** Free memory
> still gates which tier *may* load (§1), but **latency chose the default, and
> it chose the smallest model.** The requirement is a sub-7 s answer every time;
> tier 1 delivers 1.4 s and passes the capability checks in §4.5, while tiers 2
> and 3 spend 13 – 16 s in initialization alone, before looking at a pixel, on
> every invocation. That is a property of the Class B contract (§5) meeting
> Qwen3-VL's init cost — not something a better quantization fixes (§4.3.3).

> **Tier 0 is not a failure state.** *"A person ahead on the left, a chair to the
> right, a doorway centre"* composed from the detector's existing output is
> genuinely useful, costs zero memory, adds zero latency, and cannot fail
> (`ARCHITECTURE.md` §6.4 rung 3). **It ships regardless.** Tiers 1–3 are
> additive, never load-bearing.

### 2.1 Why Gemma 3n was dropped, and Qwen3-VL put in its place

An earlier revision of this document had **Gemma 3n E4B** at tier 2 and listed
"Qwen3-VL 2B / 4B" under *Ruled out*. That was re-examined on 12 September 2026
and reversed. The reversal matters more than the models, because **the original
rejection did not say what it appeared to say.**

`ARCHITECTURE.md` §6.4 excluded Qwen3-VL-4B because its AI Hub page listed
8 Elite Gen 5 as supported while also stating *"not supported on any Mobile
chipset."* `BUILD_PLAN.md` §2.3 then **overturned that reasoning**: the banner is
a page-filter artifact that appears on `segformer_base` too, and believing it
"nearly cost this build its segmentation capability." What survived was §2.4's
narrower finding — *no VLM runs on the **NPU** in this build* — a rejection of
the **Qualcomm AI Hub deployment route**, not of the model. The *Ruled out* row
even said so: "Qwen3-VL 2B / 4B **on NPU**."

Scene Mode is CPU-side llama.cpp, one-shot, and explicitly outside the NPU claim
(§7). The AI Hub objection never applied to it. **Qwen3-VL had simply never been
evaluated as a GGUF.** Evaluated on that footing it wins on four counts:

| | Gemma 3n E4B | **Qwen3-VL-4B** |
|---|---|---|
| Size | 4.4 GB | **2.95 GB** |
| Runtime | LiteRT-LM / MediaPipe — **a second integration** | **llama.cpp `libmtmd`, same as every other tier** |
| Licence | Google gated; browser acceptance before download | **Apache-2.0, ungated** |
| Text in images | Not its strength | **Its strongest capability** |

The runtime row is decisive. Gemma was the *only* thing forcing a second
inference stack into this build; removing it makes the whole ladder a file
swap. The licence row is not academic either — a browser-gated download is the
exact failure mode that cost this project hours on Qualcomm Software Center.

And the last row is the one that matches the requirement: Scene Mode must answer
**semantic questions about text it can see** (§5), not merely caption. That is
what Qwen3-VL is best at and what Gemma 3n is weakest at.

> **That last row was the reasoning, and latency overruled it.** Qwen3-VL is
> indeed the best of these at reading text — §4.3.3 shows it answering correctly
> — but it spends 13 – 16 s initializing before it looks at anything, on every
> invocation, and that is disqualifying under a sub-7 s bound. The analysis in
> this section stands on size, licence and runtime; it was simply not the
> dimension that decided the outcome. See §4.5 for what shipped instead.

### 2.2 Ruled out

| Model | Why not |
|---|---|
| Moondream2 on the phone | No quantized GGUF published — f16 only, **3.75 GB** with the projector. Self-quantizing is an hour on the critical path for a narrative benefit |
| Gemma 3n E2B / E4B | Superseded — see §2.1. Bigger, gated, weaker at text, and the only tier that needed a second runtime |
| LFM2.5-VL-1.6B | Not wrong, just dominated at the same size class: Qwen3-VL-2B is 1.55 GB against 1.31 GB, Apache-2.0 against the LFM Open Licence, and far stronger on text in images. Its 450M sibling is what shipped instead — not because it is better, but because it is the only one fast enough (§4.5) |
| Qwen3-VL 2B / 4B **on the NPU** | Unchanged and still true: AI Hub mobile deployment is not reachable in an event window (`BUILD_PLAN.md` §2.4). Tiers 1 and 2 run on the **CPU** and make no NPU claim |

---

## 3. Sizes — actual Hugging Face blob sizes, not estimates

A vision model needs **two** files — the language model and the `mmproj` vision
projector. People routinely budget only the first.

| Model | Text GGUF | mmproj | **Total** | Licence |
|---|---|---|---|---|
| **Qwen3-VL-4B-Instruct** Q4_K_M + Q8_0 | 2,497 MB | 454 MB | **2.95 GB** | Apache-2.0 |
| **Qwen3-VL-2B-Instruct** Q4_K_M + Q8_0 | 1,107 MB | 445 MB | **1.55 GB** | Apache-2.0 |
| LFM2.5-VL-1.6B Q4_K_M + Q8_0 | 731 MB | 583 MB | **1.31 GB** | LFM Open |
| **LFM2.5-VL-450M** Q4_K_M + Q8_0 | 229 MB | 103 MB | **0.33 GB** | LFM Open |
| SmolVLM2-500M Q8_0 + Q8_0 | 437 MB | 109 MB | 0.55 GB | Apache-2.0 |
| Moondream2 (f16 only) | 2,840 MB | 910 MB | 3.75 GB | — |

Note how little the `mmproj` differs between Qwen3-VL 2B and 4B (445 vs 454 MB):
they share a vision tower, so moving between tiers 1 and 2 costs ~1.4 GB of
language model and nothing else.

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

### 4.1 `GGML_LLAMAFILE=ON`. The earlier `OFF` was wrong, and it was expensive

This document and `BUILD_PLAN.md` both used to prescribe `GGML_LLAMAFILE=OFF`
for the arm64 build. That flag was never measured; it was inherited as a
"minimal build" instinct. **It cripples the exact code path a vision model
depends on.**

On the 16 GB I2501, LFM2.5-VL-450M Q4_K_M, 8 threads, cooled, `llama-bench`:

| Build | `pp128` (prompt) | `tg32` (decode) |
|---|---:|---:|
| `GGML_LLAMAFILE=OFF` | **18.20 ± 0.19 t/s** | 100.13 ± 9.53 t/s |
| `GGML_LLAMAFILE=ON` | **268.35 ± 65.64 t/s** | 105.93 ± 3.41 t/s |

**14.7× on prompt processing; decode unchanged within noise.** The `OFF` build
was the anomaly, not the `ON` one — prompt processing is normally *faster* than
decode, and seeing `pp` at a fifth of `tg` is the signature of missing batch
matmul kernels. `GGML_LLAMAFILE` supplies those (tinyBLAS); without them every
batched matmul falls back to the scalar path.

This matters far more for a VLM than for a text model. **Every image token is
prompt, not decode.** A 320 px image is a few hundred vision tokens, so the
`OFF` build was paying roughly fifteen times over for the one thing Scene Mode
does most. End-to-end on the 450M at 320 px this alone moved a full answer from
**4.7 s to 1.8 s**, and it is what made the larger tiers worth measuring at all.

> **Keep `GGML_CPU_KLEIDIAI=ON` as well** — the two are complementary, not
> alternatives. Note the build log line `kleidiai: no kernel for tensor type
> q6_K`: KleidiAI accelerates `Q4_0` and `Q8_0`, and a `Q4_K_M` file contains
> `q6_K` tensors it will skip. That is a known, unquantified lead, not a
> conclusion — do not restate it as a reason to change quantization without
> measuring both.

### 4.2 Bound the input resolution — it dominates everything else

The vision encoder cost is superlinear in pixels and has a hard cliff.
Qwen3-VL-2B, same image, same build, encode time only:

| Long edge | Encode | Reads "Cero Emisiones" on the bus? |
|---|---:|---|
| 512 px | 15,250 ms | yes |
| 384 px | 7,461 ms | yes |
| **320 px** | **1,603 ms** | **yes** |
| 256 px | 1,110 ms | **no** — answered "e-transportes" |

**320 px is the operating point**: roughly ten times cheaper than 512 px with no
loss of text legibility, and one step below it the model starts inventing text.
That last row is why resolution must be chosen by a text-reading check and not
by latency alone — a scene caption degrades gracefully at 256 px, but reading a
bus number or a door sign does not, and Scene Mode is supposed to do both (§5).

`ARCHITECTURE.md` §6.4 already required "bounded input resolution" as a gate.
This is the measured value for that bound: **long edge 320 px**.

### 4.3 Two more tuning results, both counter-intuitive

**Use 6 threads, not 8.** The SoC is heterogeneous, and every ggml step waits on
its slowest thread, so scheduling onto the efficiency cores drags the whole
batch. Qwen3-VL-2B, same everything, cooled:

| `-t` | Wall |
|---|---:|
| 8 | 28.4 s |
| 6 | 21.9 s |
| 4 | 21.1 s |

**26% faster with fewer threads.** Do not "use all the cores".

**Keep `GGML_CPU_KLEIDIAI=ON`.** It was briefly suspected of being the load-time
cost, since it repacks weights at load and the load phase dominates. It is not —
disabling it made things twice as slow:

| Build | Qwen3-VL-2B wall, cooled, `-t 6` |
|---|---:|
| KleidiAI **ON** | **12.3 s** |
| KleidiAI OFF | 26.2 s |

So `GGML_LLAMAFILE` and `GGML_CPU_KLEIDIAI` are both `ON`, and they are
complementary: llamafile fixes prompt processing, KleidiAI fixes the rest.

### 4.3.1 Quantization is a *speed* decision here, not a size decision

The KleidiAI note above turned out to matter far more than "a lead". Same model,
same build, only the quantization differs — LFM2.5-VL-450M, `-t 8`, cooled:

| Quant | `pp128` | `tg32` |
|---|---:|---:|
| Q4_K_M | 394.88 ± 0.84 t/s | 169.73 ± 2.75 t/s |
| **Q4_0** | **1689.05 ± 82.47 t/s** | **190.04 ± 9.50 t/s** |

**4.3× on prompt processing, 1.12× on decode.** `Q4_K_M` is a mixture containing
`q6_K` tensors; KleidiAI implements `Q4_0` and `Q8_0` only, so the *more
sophisticated* quantization is the slower one on this hardware. That is the
opposite of the usual intuition, and it is worth checking on any new model.

**This is a problem for Qwen3-VL specifically**, because Qwen publish only F16,
Q8_0 and Q4_K_M — there is no official Q4_0. Producing one means downloading the
3.4 GB F16 and running `llama-quantize`.

> **But do not expect it to rescue load time.** Measured on the 450M, which
> exists in both quants, warm: Q4_K_M loads in **0.54 s**, Q4_0 in **0.47 s** —
> about 13%. Quantization buys compute throughput, not initialization.

### 4.3.2 Load time is initialization, and it scales badly with this architecture

| Model | Total bytes | Warm load |
|---|---:|---:|
| LFM2.5-VL-450M | 332 MB | **~0.5 s** |
| Qwen3-VL-2B | 1,552 MB | **~7.9 – 10.8 s** |
| Qwen3-VL-4B | 2,951 MB | ~15.7 s |

It is **not I/O**: the model file reads from page cache at **6.6 GB/s**, and the
second consecutive run still pays ~7.9 s. Between the 2B and the 4B the cost is
linear in bytes (1.9× bytes, 1.87× time), but between the 450M and the 2B it is
**4.7× the bytes for ~16× the time** — so this is a fixed architectural cost of
Qwen3-VL's initialization, not a size law.

**Consequence for the Class B contract (§5).** Load is paid on *every*
invocation, because §5 forbids keeping the model resident. For Qwen3-VL-2B that
is ~8 s before any pixel is looked at, which **cannot fit a sub-7 s budget at
any quantization**. Meeting that budget with a 2B-class model therefore requires
amending §5, not tuning the model.

### 4.3.3 The Q4_0 experiment on Qwen3-VL-2B — tested, and it does not rescue it

The prediction above was tested rather than assumed. Qwen publish no Q4_0, so
the 3.4 GB F16 was downloaded and requantized with `llama-quantize` **on the
phone** (4.8 s, producing 1,054,424,096 bytes). Three paired runs, cooled to
< 52 °C before each, `-t 6`, 320 px, same image and question:

| Run | Q4_K_M load / wall | Q4_0 load / wall |
|---|---|---|
| 1 | 15.3 s / 20.5 s | 13.0 s / 25.0 s |
| 2 | 13.9 s / 27.4 s | 13.7 s / 31.5 s |
| 3 | 12.5 s / 17.1 s | 14.6 s / 28.0 s |

**Load is 12.5 – 15.3 s either way — statistically indistinguishable.** The
4.3× prompt-processing win from §4.3.1 is real, but it lands on *encode*, which
was already down to ~1.2 s. It cannot touch initialization.

> **Conclusion, measured rather than projected: Qwen3-VL-2B cannot meet a
> sub-7 s budget under the §5 Class B contract, at any quantization.** Do not
> re-open this by trying another quant; the next idea worth testing is
> amending §5 to keep the model resident for the duration of an explicit Scene
> session, which removes the load entirely.

Both answers were correct, which is worth noting — the 2B is not *wrong*, it is
*late*: Q4_K_M gave "Yes, there is a bag in the image. It is a blue backpack."
and Q4_0 gave "yes, a backpack".

### 4.4 What the tuning was worth, and what still costs

Qwen3-VL-2B, one 320 px image, one sentence out, measured on this phone:

| Configuration | Wall |
|---|---:|
| First attempt — `LLAMAFILE=OFF`, 512 px, `-t 8` | **51 s** |
| Tuned — `LLAMAFILE=ON`, 320 px, `-t 6`, warm | **12.3 s** |

**A 4× improvement from build flags and one resolution bound — no model change.**
That is the reason the "measure, then pick" ladder exists: the first number was
not a property of the model, it was a property of a bad build.

> **Read these numbers with the variance in mind.** This phone swings hard in
> both directions. Sustained runs drove one thermal zone to **105 °C** and
> throttled badly; runs started from cold are *also* slow because the governor
> ramps. The same model and image encoded in **395 ms** and in **5,202 ms** in
> different runs. Quote a range, never a single figure, and say which state it
> was measured in.

**Where the remaining time goes** — cooled, `-t 6`, 320 px:

| Tier | Load | Encode | Decode | Wall |
|---|---:|---:|---:|---:|
| LFM2.5-VL-450M | ~1 s | 0.4 – 5.2 s | ~1 s | **2 – 7 s** |
| Qwen3-VL-2B | ~8 s | ~1.4 s | ~11 s | **12 – 21 s** |
| Qwen3-VL-4B | ~16 s | ~1.9 s | ~22 s | **25 – 40 s** |

Encoding is no longer the problem for any tier. **Load and decode are**, and
load is not I/O — the model file reads from page cache at 6.6 GB/s, so those
seconds are model initialization, paid **on every single invocation** because
§5 forbids keeping the model resident. For the 2B that is roughly 8 of its 12
seconds. The tier choice is therefore partly a question about the Class B
contract, not only about the models.

### 4.5 Capability at tier B — measured against the demo objects

The requirement is a **sub-7 s answer, always**, that names doors, chairs,
tables, bottles, bags, people and laptops, and reads basic text. LFM2.5-VL-450M
Q4_K_M, 320 px, `-t 6`, tested on COCO128 fixtures containing exactly those
objects:

| Check | Result |
|---|---|
| Latency | **1.35 – 1.65 s** |
| Direct object questions (bag, chair, laptop, bottle) | **4 / 4 correct** |
| Absent-object questions | **4 / 5 correct "No"** — it is not a yes-machine |
| Sign reading | **2 / 2 verbatim** — "EXIT ROOM 204", "PLATFORM 3 TRAINS" |
| Text in a photo | "cero emisiones" on the bus, correct |
| Open scene captioning | Names table, bottle, chair, laptop, suitcase; **drifts to the dominant subject and can miss small objects** |

**The shape of this result decides the prompt design, not just the tier.** The
450M missed a backpack when asked to caption freely — it answered "a busy city
square with a large monument" — but when asked *"Is there a bag in this
image?"* it replied *"Yes... It's a black backpack carried by a woman in the
foreground."* Same model, same image, same 320 px input.

> **MUST: prefer a direct question over an open caption.** Scene Mode should ask
> the model the user's actual question, and when the user asks for a general
> description it should still be given a *specific* instruction rather than
> "describe this". A small VLM is far more reliable as a detector-of-what-you-
> named than as a narrator.

> **The one caveat on "always".** The worst latency observed for tier B was
> **7.4 s**, on the first run after the phone had been cooled from 105 °C — CPU
> governor ramp, not steady state. Typical is 1.4 – 2 s. If the sub-7 s bound
> must hold even in that state, it needs designing for (warm the model at
> gesture start, speak an acknowledgement immediately), not assuming.

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

**Every tier is public — no licence gate, no account, no browser step.** That is
a deliberate property of this ladder, not a coincidence; see §2.1.

```bash
mkdir -p models/staging/vlm
# Tier B — bring-up
curl -L -o models/staging/vlm/lfm25-vl-450m-q4km.gguf "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/LFM2.5-VL-450M-Q4_K_M.gguf"
curl -L -o models/staging/vlm/lfm25-vl-450m-mmproj-q8.gguf "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/mmproj-LFM2.5-VL-450M-Q8_0.gguf"
# Tier 1
curl -L -o models/staging/vlm/qwen3vl-2b-q4km.gguf "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/Qwen3VL-2B-Instruct-Q4_K_M.gguf"
curl -L -o models/staging/vlm/qwen3vl-2b-mmproj-q8.gguf "https://huggingface.co/Qwen/Qwen3-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-2B-Instruct-Q8_0.gguf"
# Tier 2
curl -L -o models/staging/vlm/qwen3vl-4b-q4km.gguf "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/main/Qwen3VL-4B-Instruct-Q4_K_M.gguf"
curl -L -o models/staging/vlm/qwen3vl-4b-mmproj-q8.gguf "https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF/resolve/main/mmproj-Qwen3VL-4B-Instruct-Q8_0.gguf"
```

Record every SHA-256 in `models/staging/MANIFEST.sha256` (`ARCHITECTURE.md` §7.4).

> **Licence check.** Qwen3-VL is **Apache-2.0** — state that plainly if asked.
> LFM2.5-VL, used only for the tier B bring-up, is under the **LFM Open
> License**, not Apache-2.0; read it before quoting it. If tier B ever becomes
> something we ship rather than something we debug with, SmolVLM2-500M is the
> Apache-2.0 replacement at that size.

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

---

## 8. The "hang" — resolved 12 September 2026, evening

**Scene Mode runs inside the app.** Both device tests pass, four consecutive
runs, twelve answers between **1.3 and 1.8 s** on the 16 GB phone — the same
figures as `llama-mtmd-cli`. The gate is gone. This section is the record of
what the bug actually was, because the earlier diagnosis was wrong in an
instructive way.

### 8.1 What it was

Two defects stacked, and the first hid the second.

1. **The app's native build was `-O0`.** AGP passes `CMAKE_BUILD_TYPE=Debug`
   for the debug variant and `cppFlags += "-O3"` reaches C++ only. From the
   generated `compile_commands.json`, **173 C translation units** — `ggml.c`,
   `ggml-cpu.c`, `ggml-quants.c`, `arch/arm/quants.c`, every KleidiAI pack
   kernel — carried no `-O` flag at all. Every quantised dot product ran
   unoptimised. That is where the "84 s of CPU" came from, and it is why
   nothing measured against the Release CLI binaries ever transferred.
   Fixed: `-DCMAKE_BUILD_TYPE=Release` in `app/build.gradle.kts`'s cmake
   arguments, for every variant.

2. **llama.cpp b10926's CPU flash-attention kernel writes past its work
   buffer on this phone.** With the build at `-O3` the "hang" became a
   tombstone: `SIGSEGV` in `memset` inside
   `ggml_compute_forward_flash_attn_ext`, at a page boundary, i.e. the 16 KB
   `K_f32` tile of the tiled path running off the end of `wdata`. The tiled
   path is taken for any batch of 64+ rows — so the 80-token image batch in
   the decoder and the 300-patch pass in the CLIP encoder — and never for the
   short text chunks, which is exactly the "text works, image does not"
   signature. At `-O0` the same out-of-bounds write landed differently and
   wedged the process instead of faulting.
   Fixed: `flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED` on **both** the
   llama context and the mtmd (CLIP) context in `scene_vlm.cpp`. Measured cost
   on this phone: encoder 466 ms fused → 481–524 ms without; end to end within
   noise. Not upstreamed; the pin stays at b10926 and the flag stays off.

### 8.2 What the earlier analysis got wrong, so nobody repeats it

- "Blocked, not slow; CPU time frozen" was read as a deadlock. It was a
  corrupted process. The tell that was missed: an identical CPU figure across
  runs means a *deterministic amount of work then a stop*, which a memory
  fault produces just as well as a lock.
- `n_ubatch`, the thread pool, `warmup` and `image_max_tokens` were all
  "ruled out" — correctly, but each ruling-out was an evening spent on the
  wrong layer. The first move should have been reading the stuck thread
  (`/proc/<pid>/task/*/wchan` and `debuggerd -b <pid>`, both usable on a
  debuggable app without root) and checking `compile_commands.json` for the
  flags the app was actually built with.
- The comments in `scene_vlm.cpp` that blamed `n_ubatch = n_ctx` and
  `warmup = false` for the hang were describing the same bug from two random
  angles. They have been replaced.

### 8.3 Cancellation is now real

`llama_set_abort_callback` polls `SceneVlm::cancelled` between graph nodes,
so a cancel lands during prefill as well as during the token loop;
`llama_decode` returns 2 (aborted), `nativeAsk` returns null, and the
`finally` in `SceneVlm.ask()` frees the model. The device test
`cancelMidInferenceReturnsPromptlyAndReleasesTheModel` cancels 500 ms into a
call, sees the image chunk abort (`rc=2`) within ~470 ms, and gets a full
answer from the next call. `SceneDescriber`'s timeout now cancels and
**waits** for the native unwind rather than abandoning the thread.

The one window a cancel cannot cut short is the CLIP encoder — the mtmd API
exposes no abort hook for it — and that pass is under 0.6 s on this phone.

### 8.4 Operational notes, all paid for

- **`adb install -r` keeps the external files directory.** Gradle's
  `installDebug` (which uninstalls first) does not. Prefer
  `adb install -r -t -d app-debug.apk` between iterations and the six staged
  files survive.
- **From Git Bash on Windows, pass `adb.exe` a `C:/...` path, not `/c/...`.**
  With `MSYS_NO_PATHCONV=1` the latter is handed to a Windows binary
  verbatim, the install reports `Success`, and the package on the phone is
  unchanged. Verify by pulling the installed APK and comparing the `.so`'s
  md5 — the run script in this session did exactly that and caught it twice.
- The phone idles under long instrumentation runs and logcat goes quiet with
  it; `adb shell svc power stayon usb` before a batch.
- Step logging (`step: …`, per-chunk lines) is kept at INFO. It is a handful
  of lines per Ask and it is how a regression would be seen first.
- `scripts/bootstrap_llama.sh` also stages KleidiAI 1.24.0 and verifies the
  upstream archive MD5 (`2f02ebe29573d45813e671eb304f2a00`). If that local
  tree is present, CMake uses it instead of attempting a nested network fetch.

### 8.5 Live person misclassification — fixed at the viewport boundary

The final 16 GB build produced both of these answers on live camera frames:

- correct: “A man wearing a light-colored shirt and a lanyard with a badge.”
- wrong: “A wooden table with a book and a small object on it.” while the
  operator saw a person on screen.

That was not a detector fallback: Scene Mode's answer came from the VLM. It was
also not a globally broken RGB/JNI path, because the same binary recognized the
first man and every generated sign. The integration mismatch was between what
the user saw and what the model received. `PreviewView.ScaleType.FILL_CENTER`
centre-crops a 4:3 camera stream substantially on the phone's tall display, but
`ImageCapture` was sending the uncropped full sensor still. Objects outside the
visible preview could dominate a 450M captioner, while the aimed person/text was
smaller than it appeared on screen.

`CameraFramePipeline.captureStill` now records the laid-out preview aspect,
centre-crops in the encoded orientation, then rotates and applies the existing
resolution bound. The same correction applies to Scene, OCR, and consented
hazard evidence. The VLM system instruction now reports people first, mentions
only clearly visible objects/text, and admits uncertainty. The default question
is explicit: first decide whether any person is visible anywhere, then describe
the person and the main objects.

The real-image regression stages three COCO person fixtures beside the GGUFs
rather than shipping photographs in the app. One is dark and off-centre. The
old spatial wording produced the contradictory “There are no people … a person
in the foreground”; the final question rejects that answer and produced three
positive person descriptions in **1.45–1.61 s** on the 16 GB phone. The test is
`stagedPersonFixturesAreRecognisedAsPeople`.

Find is no longer open here: `TargetLocator` resolves from on-device landmark
memory and the live detector view (commits `d2897ab` / `4877d22`). The physical
Explore run on the installed build also passed end to end: the two-finger-right
gesture captured a viewport-aligned still, ML Kit decoded 50 characters at
`HIGH` quality in 141.90 ms, and the blocking speech path played the result.
Trace logs distinguish gesture, capture, recognition, and readout failures
without adding app controls or logging the recognized text.

### 8.6 Selected-language boundary

Scene's native system prompt now receives the selected answer language instead
of guessing it from the spoken question. On the 16 GB phone, the Hindi fixture
answered in Devanagari in 1.87 s and correctly included the visible `EXIT` text.

Tamil was tested more strictly rather than accepted for merely emitting Tamil
characters. LFM2.5-VL-450M repeated the Tamil question and failed to read the
fixture sign, including after an English visual-task scaffold. Per operator
direction, Tamil Scene is out of scope. The app refuses that combination with a
localized Tamil message; it does not present a fluent-looking non-answer. This
does not affect Tamil static guidance, Find cues, OCR qualification, or TTS.
