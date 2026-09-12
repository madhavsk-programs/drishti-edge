# Device budget — iQOO 15, 12 GB and 16 GB

Quick reference. Full reasoning in [`../ARCHITECTURE.md` §4–§5](../ARCHITECTURE.md#4-device-budget--12-gb-iqoo-15).

> **Both variants are now measured.** The original loaner is 16 GB; current
> development uses a second iQOO 15 with 12 GB RAM plus 12 GB compressed swap.
> The walking loop and guarded YOLO NPU path run on the 12 GB phone. **Nothing
> on the safety path depends on swap or on the larger SKU** — 16 GB only buys
> headroom for the on-demand Scene Mode model
> ([`SCENE_MODE_VLM.md`](SCENE_MODE_VLM.md)).

## RAM accounting

| Consumer | **12 GB (current)** | 16 GB (original loaner) |
|---|---|---|
| Android 16 + OriginOS, idle after reboot | 3.5 – 4.5 GB | 3.5 – 4.5 GB |
| Other system services, background apps | ~1.0 GB | ~1.0 GB |
| Our app baseline — Compose, CameraX, buffers | 0.4 – 0.6 GB | 0.4 – 0.6 GB |
| **Headroom before the low-memory killer** | ~5.5 – 6.5 GB | **~9 – 10 GB** |

> **Measured now:** the current 12 GB phone reported **5,155 MB available of
> 11,205 MB** during the NPU probe. The original 16 GB phone reported 7,998 MB
> available of 15,219 MB in normal use. Re-measure immediately before loading
> any on-demand model; neither number is a promise.

> **ZRAM is not headroom.** The measured device carried 12.6 GB of compressed
> swap with 8.3 GB free. Paging a multi-gigabyte model through it is slow and
> thermally expensive, and the low-memory killer still counts the uncompressed
> working set. **MUST NOT** size a model against `SwapFree`.

## Model residency

Published rows are retained as targets; the current-device measurements below
take precedence where available.

| Stage | Resident? | Footprint | NPU latency |
|---|---|---|---|
| Detection — YOLO11n w8a16 QDQ | **Always** | 0 – 82 MB published | **3.30 ms measured, guarded HTP** |
| Segmentation — SegFormer-B0 ADE20K w8a16 | **Always** | 13 – 217 MB published | **11.33 ms measured, guarded HTP** |
| Track, spatial, risk, guidance, target memory | Always | < 20 MB, pure Kotlin | — |
| **Walk loop total** | | **~300 – 450 MB** | Both resident models fully NPU; **57.96 ms warmed live frame end to end** |
| OCR — bundled ML Kit | On demand; implemented | 150 – 250 MB conservative peak budget | CPU; **89 ms integration test case** |
| Scene Mode VLM | On demand | 1.3 – 4.4 GB peak | **CPU, not NPU** |
| Reasoning LLM | **Not on device** | — | — |

The walk loop is comfortable on either variant. Everything else is a spike.

## The rules that do not relax on either variant

- **`SAFETY_MARGIN_BYTES` stays at 800 MB.** It is not tuned down to make a demo
  work. A refused Scene query is a disappointment; a killed process mid-walk is a
  safety failure.
- **No two on-demand models are ever co-resident**, with each other or with
  anything else large.
- **Check free memory immediately before every invocation**, not once at startup.
  The number moves.
- **Return the result after the unload, never before.** Returning first and
  unloading asynchronously opens a window where a second request doubles the
  footprint.
- **Verify reclaim.** Dropping a Kotlin reference does not free native memory.
  Call the runtime's explicit release, then measure. If reclaim cannot be proved,
  host the model in a separate killable process and reclaim by killing it.

> The extra 4 GB changes what Scene Mode can load. It does not change any of the
> five rules above, and it does not change a single line of the walking loop.
