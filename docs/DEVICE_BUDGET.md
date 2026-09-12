# Device budget — iQOO 15, 16 GB

Quick reference. Full reasoning in [`../ARCHITECTURE.md` §4–§5](../ARCHITECTURE.md#4-device-budget--12-gb-iqoo-15).

> **The variant is confirmed: 16 GB.** Everything in this project was designed
> against 12 GB so that drawing the smaller SKU could never break the demo. That
> constraint held and is now slack. **Nothing on the safety path changes** — the
> only thing 16 GB buys is headroom for the on-demand Scene Mode model
> ([`SCENE_MODE_VLM.md`](SCENE_MODE_VLM.md)).

## RAM accounting

| Consumer | 12 GB (design target) | **16 GB (actual)** |
|---|---|---|
| Android 16 + OriginOS, idle after reboot | 3.5 – 4.5 GB | 3.5 – 4.5 GB |
| Other system services, background apps | ~1.0 GB | ~1.0 GB |
| Our app baseline — Compose, CameraX, buffers | 0.4 – 0.6 GB | 0.4 – 0.6 GB |
| **Headroom before the low-memory killer** | ~5.5 – 6.5 GB | **~9 – 10 GB** |

> **MEASURE** `availMem` on the loaner in the state it will be in at demo time —
> **not** freshly rebooted. On a measured 12 GB device in normal daily use, free
> memory was **3.6 GB**, not the 5.5 GB the post-reboot figure suggests. Expect
> the same gap here: real free memory will be well under the 9–10 GB row.

> **ZRAM is not headroom.** The measured device carried 12.6 GB of compressed
> swap with 8.3 GB free. Paging a multi-gigabyte model through it is slow and
> thermally expensive, and the low-memory killer still counts the uncompressed
> working set. **MUST NOT** size a model against `SwapFree`.

## Model residency

Measured on Qualcomm's published Snapdragon 8 Elite Gen 5 NPU profiles, not
estimated.

| Stage | Resident? | Footprint | NPU latency |
|---|---|---|---|
| Detection — YOLO11n w8a16 | **Always** | 0 – 82 MB | **2.27 ms** |
| Segmentation — SegFormer-B0 ADE20K w8a16 | **Always** | 13 – 217 MB | **5.66 ms** |
| Track, spatial, risk, guidance, target memory | Always | < 20 MB, pure Kotlin | — |
| **Walk loop total** | | **~300 – 450 MB** | **~8 ms/frame** |
| OCR — ML Kit | On demand | 150 – 250 MB peak | CPU |
| Scene Mode VLM | On demand | 1.3 – 4.4 GB peak | **CPU, not NPU** |
| Reasoning LLM | **Not on device** | — | — |

The walk loop is comfortable on either variant. Everything else is a spike.

## The rules that do not relax on 16 GB

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
