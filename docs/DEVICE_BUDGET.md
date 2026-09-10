# Device budget — 12 GB iQOO 15

Quick reference. Full reasoning in [`../ARCHITECTURE.md` §4–§5](../ARCHITECTURE.md#4-device-budget--12-gb-iqoo-15).

## Why 12 GB is the planning assumption

The loaner variant is unconfirmed. The iQOO 15 ships in 12 GB and 16 GB
configurations, and one flagship loaner is handed over per person at check-in
without a stated SKU.

**Every must-have item fits 12 GB.** A demo that depends on drawing the 16 GB
variant is a demo that can fail at check-in for reasons entirely outside our
control.

## RAM accounting

| Consumer | Estimate |
|---|---|
| Android 16 + OriginOS, idle after reboot | 3.5 – 4.5 GB · **measure on the loaner** |
| Other system services, background apps | ~1.0 GB · reducible |
| Our app baseline — Compose, CameraX, buffers | 0.4 – 0.6 GB |
| **Headroom before the low-memory killer** | **~5.5 – 6.5 GB** |

> **Planning ceiling: 3.5 GB total model residency. Hard ceiling 4.5 GB.**
> Above that the low-memory killer starts reclaiming, and the failure mode is our
> process being killed mid-walk — silent until it happens.

## Model residency

| Stage | Resident? | Footprint |
|---|---|---|
| Detection — YOLO11n, quantized | **Always** | 120 – 180 MB |
| Segmentation — SegFormer-B0 ADE20K, quantized | **Always, if its gate passes** | 150 – 250 MB |
| Track, spatial, risk, guidance, target memory | Always | < 20 MB, pure Kotlin |
| **Walk loop total** | | **~300 – 450 MB** |
| OCR | On demand | 150 – 250 MB peak |
| Phone VLM — Qwen3-VL-2B INT4, optional | On demand | 2.0 – 2.7 GB peak |
| Reasoning LLM — Qwen3-4B INT4 | **Not on device** | 3.0 – 3.5 GB peak |

The walk loop is comfortable. Everything else is a spike.

## The rule that keeps it safe

> **No two on-demand models are ever co-resident, with each other or with
> anything else large.** On 12 GB, `2.5 GB + 3.2 GB` exceeds the ceiling and the
> process dies.

On-demand models are strictly: check free memory → refuse if below floor → load →
one inference → **unload** → *then* return the result.

Returning before the unload leaves a window where a second request doubles the
footprint.

Dropping a reference is not the same as freeing native memory. A native runtime
may retain arenas, contexts, and graph memory after the object is gone. Call the
runtime's explicit close API, then **measure reclaimed memory**. A runtime that
cannot be proved to release belongs in a separate killable process.

`SAFETY_MARGIN_BYTES = 800 MB`, and it is not tuned down to make a demo work. A
refused Scene query is a minor disappointment; a killed process mid-walk is a
safety failure.

## Quantization reference

Weight storage only — real runtime memory is higher once KV cache, activations,
buffers, tokenizer and vision encoder are counted.

| Precision | Bytes / parameter | A 2 B model | A 4 B model |
|---|---|---|---|
| FP16 | 2 | 4.0 GB | 8.0 GB |
| INT8 | 1 | 2.0 GB | 4.0 GB |
| INT4 | 0.5 | 1.0 GB | 2.0 GB |
| INT2 | 0.25 | 0.5 GB | 1.0 GB |

Snapdragon 8 Elite Gen 5 adds INT2 and FP8 support. INT4 is the working
assumption for language and vision-language models.

## The gate that changes everything

The parent research notes NexaSDK's Android path documents a **16 GB RAM floor**.
On a 12 GB device it may simply be unavailable.

| Outcome | Response |
|---|---|
| Initialises, NPU backend | Candidate for OCR and the optional phone VLM |
| Initialises, CPU only | Do not use it. CPU inference anywhere near the walk loop is a thermal and latency disaster. |
| Does not initialise | AI Hub / QNN only. The phone VLM drops to optional, with the laptop snapshot locator as its fallback. |

Test this before designing around it. It reshapes the rest of the plan.

## What the budget does not include

The optional locator fallback runs on the laptop, not the phone, and costs the
phone nothing. It is *laptop-assisted target localization* — an explicit single
snapshot in, one normalized box out — and it is never part of the on-device
claim. Full reasoning in
[`../ARCHITECTURE.md` §6.4](../ARCHITECTURE.md#64-the-locator-and-scene-vlm--optional-proof-never-a-prerequisite).
