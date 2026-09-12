"""Prepare a public image fixture or compare YOLO results copied from the phone.

prepare IMAGE: writes models/staging/yolo_input.f32 using the app preprocessing.
check: reads models/staging/probe-results/*; fails if quantization loses an FP32
detection (confidence .35), adds one, changes its class or moves its box >10% IoU.
This is a fixture regression check, not dataset accuracy or field validation.
"""
import argparse
import json
from pathlib import Path

import numpy as np

from export_yolo_qdq import ROOT, digest, pixels


def iou(a, b):
    overlap = np.maximum(0, np.minimum(a[2:], b[2:]) - np.maximum(a[:2], b[:2])).prod()
    area_a, area_b = np.maximum(0, a[2:] - a[:2]).prod(), np.maximum(0, b[2:] - b[:2]).prod()
    return float(overlap / max(area_a + area_b - overlap, 1e-9))


def detections(path):
    head = np.fromfile(path, dtype="<f4").reshape(84, 8400).T
    if not np.isfinite(head).all():
        raise ValueError(f"Nonfinite output in {path}")
    scores, labels = head[:, 4:].max(axis=1), head[:, 4:].argmax(axis=1)
    ids = np.flatnonzero(scores >= .35)
    ids = ids[np.argsort(-scores[ids], kind="stable")]
    corners = np.concatenate((head[:, :2] - head[:, 2:4] / 2, head[:, :2] + head[:, 2:4] / 2), axis=1)
    kept = []
    for idx in ids:
        if not any(labels[idx] == labels[k] and iou(corners[idx], corners[k]) > .45 for k in kept):
            kept.append(idx)
    return [(int(labels[k]), float(scores[k]), corners[k]) for k in kept]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "check"])
    parser.add_argument("image", nargs="?", type=Path)
    args = parser.parse_args()
    staging = ROOT / "models/staging"
    if args.mode == "prepare":
        if args.image is None:
            parser.error("prepare requires IMAGE")
        pixels(args.image).astype("<f4").tofile(staging / "yolo_input.f32")
        (staging / "yolo_input.json").write_text(json.dumps({"image": args.image.name, "sha256": digest(args.image)}, indent=2))
        return
    result_dir = staging / "probe-results"
    reference = detections(result_dir / "yolo11n_fp32_nchw.onnx.CPU.f32")
    candidate = detections(result_dir / "yolo11n_qdq.onnx.NPU_GUARDED.f32")
    matches, available = [], list(candidate)
    for label, score, box in reference:
        possible = [(iou(box, c[2]), idx) for idx, c in enumerate(available) if c[0] == label]
        if not possible:
            raise AssertionError(f"Missing class {label}, FP32 score {score}")
        overlap, idx = max(possible)
        other = available.pop(idx)
        assert overlap >= .9, (label, overlap)
        assert abs(score - other[1]) <= .1, (score, other[1])
        matches.append({"class": label, "iou": overlap, "fp32_score": score, "npu_score": other[1]})
    assert reference and not available, "Empty reference or extra NPU detections"
    report = {"fixture": json.loads((staging / "yolo_input.json").read_text()),
              "qdq_sha256": digest(staging / "yolo11n_qdq.onnx"), "matches": matches,
              "scope": "single public fixture; not mAP or field accuracy"}
    (result_dir / "fixture-check.json").write_text(json.dumps(report, indent=2) + "\n")
    manifest_path = staging / "yolo11n_qdq.json"
    manifest = json.loads(manifest_path.read_text())
    manifest.update({
        "npu_verified": True,
        "fixture_validated": True,
        # A single fixture guards the integration path; it is not a dataset
        # accuracy evaluation and must not promote this stronger claim.
        "accuracy_validated": False,
        "fixture_check": report,
    })
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
