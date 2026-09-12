"""Prepare and compare a public SegFormer device fixture.

prepare IMAGE: writes the app's zero-padded, nearest-neighbour 512px NCHW
float input. check: compares guarded-QNN and CPU class-logit files pulled from
the probe. This is an integration parity check, not dataset accuracy or a
field-safety evaluation.
"""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from export_yolo_qdq import ROOT, digest


def pixels(path: Path) -> np.ndarray:
    """Match SegFormerSegmenter.fillInput and Letterbox.sourcePixelFor."""
    with Image.open(path) as image:
        rgb = np.asarray(image.convert("RGB"))
    height, width = rgb.shape[:2]
    scale = min(512 / width, 512 / height)
    scaled_width = int(width * scale + 0.5)
    scaled_height = int(height * scale + 0.5)
    xs = np.minimum((np.arange(scaled_width) / scale).astype(int), width - 1)
    ys = np.minimum((np.arange(scaled_height) / scale).astype(int), height - 1)
    output = np.zeros((512, 512, 3), dtype=np.uint8)
    pad_x = (512 - scaled_width) // 2
    pad_y = (512 - scaled_height) // 2
    output[pad_y:pad_y + scaled_height, pad_x:pad_x + scaled_width] = rgb[ys[:, None], xs]
    return np.ascontiguousarray(output.transpose(2, 0, 1)[None], dtype=np.float32) / 255


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["prepare", "check"])
    parser.add_argument("image", nargs="?", type=Path)
    args = parser.parse_args()
    staging = ROOT / "models/staging"

    if args.mode == "prepare":
        if args.image is None:
            parser.error("prepare requires IMAGE")
        pixels(args.image).astype("<f4").tofile(staging / "segformer_input.f32")
        with Image.open(args.image) as image:
            width, height = image.size
        (staging / "segformer_input.json").write_text(
            json.dumps(
                {"image": args.image.name, "sha256": digest(args.image), "width": width, "height": height},
                indent=2,
            ) + "\n"
        )
        return

    results = staging / "probe-results"
    npu_path = results / "segformer_float.onnx.NPU_GUARDED.f32"
    cpu_path = results / "segformer_float.onnx.CPU.f32"
    shape = (1, 150, 128, 128)
    npu = np.fromfile(npu_path, dtype="<f4").reshape(shape)
    cpu = np.fromfile(cpu_path, dtype="<f4").reshape(shape)
    if not np.isfinite(npu).all() or not np.isfinite(cpu).all():
        raise ValueError("Nonfinite SegFormer fixture output")

    npu_classes = np.argmax(npu, axis=1)
    cpu_classes = np.argmax(cpu, axis=1)
    agreement = float(np.mean(npu_classes == cpu_classes))
    config = json.loads((staging / "ade20k_config.json").read_text())
    labels = {int(key): value for key, value in config["id2label"].items()}
    non_walkable = {
        "wall", "building", "ceiling", "door", "screen door", "windowpane",
        "cabinet", "wardrobe", "column", "pillar", "table", "desk", "chair",
        "armchair", "sofa", "shelf", "bookcase", "railing", "bannister", "fence",
        "pole", "person", "rider", "car", "truck", "bus", "train", "motorcycle",
        "minibike", "bicycle", "van", "stairs", "stairway", "step", "escalator",
    }
    walkable = {"floor", "flooring", "rug", "carpet", "path", "sidewalk", "pavement"}
    road = {"road", "route"}
    hazard = {"stairs", "stairway", "step", "escalator"}

    def tokens(class_id: int) -> set[str]:
        return {part.strip().lower() for part in labels[class_id].split(",") if part.strip()}

    def kind(class_id: int) -> int:
        label_tokens = tokens(class_id)
        if label_tokens & non_walkable:
            return 2
        if label_tokens & walkable:
            return 1
        if label_tokens & road:
            return 3
        return 0

    kinds = np.asarray([kind(class_id) for class_id in range(150)])
    hazards = np.asarray([bool(tokens(class_id) & hazard) for class_id in range(150)])
    walls = np.asarray(["wall" in tokens(class_id) for class_id in range(150)])
    surface_agreement = float(np.mean(kinds[npu_classes] == kinds[cpu_classes]))
    hazard_differences = int(np.count_nonzero(hazards[npu_classes] != hazards[cpu_classes]))
    wall_differences = int(np.count_nonzero(walls[npu_classes] != walls[cpu_classes]))

    fixture = json.loads((staging / "segformer_input.json").read_text())
    scale = min(512 / fixture["width"], 512 / fixture["height"])
    scaled_width = int(fixture["width"] * scale + 0.5)
    scaled_height = int(fixture["height"] * scale + 0.5)
    pad_x, pad_y = (512 - scaled_width) // 2, (512 - scaled_height) // 2
    top_left, top_right = 0.42, 0.58
    bottom_left, bottom_right = 0.08, 0.92

    def point(fraction: float, top: bool) -> tuple[float, float]:
        left, right = (top_left, top_right) if top else (bottom_left, bottom_right)
        return left + (right - left) * fraction, 0.38 if top else 1.0

    corridor_polygons = {
        "left": [point(0, True), point(1 / 3, True), point(1 / 3, False), point(0, False)],
        "centre": [point(1 / 3, True), point(2 / 3, True), point(2 / 3, False), point(1 / 3, False)],
        "right": [point(2 / 3, True), point(1, True), point(1, False), point(2 / 3, False)],
    }

    def rasterize(normalized: list[tuple[float, float]]) -> np.ndarray:
        polygon = [
            ((x * scaled_width + pad_x) / 4, (y * scaled_height + pad_y) / 4)
            for x, y in normalized
        ]
        signed_area = sum(
            polygon[i][0] * polygon[(i + 1) % 4][1] -
            polygon[(i + 1) % 4][0] * polygon[i][1]
            for i in range(4)
        ) / 2
        if signed_area < 0:
            polygon.reverse()
        mask = np.zeros((128, 128), dtype=bool)
        for y in range(max(0, int(min(p[1] for p in polygon))), min(127, int(max(p[1] for p in polygon))) + 1):
            for x in range(max(0, int(min(p[0] for p in polygon))), min(127, int(max(p[0] for p in polygon))) + 1):
                inside = True
                for index, a in enumerate(polygon):
                    b = polygon[(index + 1) % 4]
                    cross = (b[0] - a[0]) * (y + 0.5 - a[1]) - (b[1] - a[1]) * (x + 0.5 - a[0])
                    if cross < 0:
                        inside = False
                        break
                mask[y, x] = inside
        return mask

    def evidence(classes: np.ndarray, mask: np.ndarray) -> dict[str, float]:
        class_map = classes[0]
        kind_map = kinds[class_map]
        total = max(1, int(mask.sum()))
        result = {
            "walkable": float(np.count_nonzero(mask & (kind_map == 1)) / total),
            "road": float(np.count_nonzero(mask & (kind_map == 3)) / total),
            "non_walkable": float(np.count_nonzero(mask & (kind_map == 2)) / total),
            "unknown": float(np.count_nonzero(mask & (kind_map == 0)) / total),
            "wall": float(np.count_nonzero(mask & walls[class_map]) / total),
            "stairs": float(np.count_nonzero(mask & hazards[class_map]) / total),
        }
        extents = []
        for x in range(128):
            rows = np.flatnonzero(mask[:, x])
            if not rows.size:
                continue
            run = 0
            for y in range(int(rows[-1]), int(rows[0]) - 1, -1):
                if not mask[y, x] or kind_map[y, x] != 1:
                    break
                run += 1
            extents.append(run / max(1, int(rows[-1] - rows[0] + 1)))
        result["floor_extent"] = float(np.median(extents)) if extents else 0.0
        return result

    corridor_evidence = {}
    maximum_ratio_drift = 0.0
    threshold_states_match = True
    for name, polygon in corridor_polygons.items():
        mask = rasterize(polygon)
        cpu_evidence = evidence(cpu_classes, mask)
        npu_evidence = evidence(npu_classes, mask)
        maximum_ratio_drift = max(
            maximum_ratio_drift,
            *(abs(cpu_evidence[key] - npu_evidence[key]) for key in cpu_evidence),
        )
        cpu_state = {
            "walkable": cpu_evidence["walkable"] >= 0.25,
            "uncertain": cpu_evidence["walkable"] < 0.25 and cpu_evidence["non_walkable"] < 0.20,
            "blocked": cpu_evidence["non_walkable"] + 0.10 * cpu_evidence["unknown"] >= 0.40,
        }
        npu_state = {
            "walkable": npu_evidence["walkable"] >= 0.25,
            "uncertain": npu_evidence["walkable"] < 0.25 and npu_evidence["non_walkable"] < 0.20,
            "blocked": npu_evidence["non_walkable"] + 0.10 * npu_evidence["unknown"] >= 0.40,
        }
        threshold_states_match &= cpu_state == npu_state
        corridor_evidence[name] = {"cpu": cpu_evidence, "npu": npu_evidence, "state": npu_state}

    def decision_summary(backend: str) -> dict[str, object]:
        values = {name: pair[backend] for name, pair in corridor_evidence.items()}
        costs = {
            name: value["non_walkable"] + 0.10 * value["unknown"]
            for name, value in values.items()
        }
        ranked = sorted(costs, key=costs.get)
        preferred = None if costs[ranked[1]] - costs[ranked[0]] < 0.10 else ranked[0]
        if preferred is None and values["centre"]["walkable"] >= 0.25 and costs["centre"] < 0.40:
            preferred = "centre"
        wall_dead_end = (
            values["centre"]["floor_extent"] <= 0.12 and
            values["left"]["floor_extent"] < 0.30 and
            values["right"]["floor_extent"] < 0.30 and
            values["centre"]["wall"] >= 0.35
        )
        return {"preferred": preferred, "wall_dead_end": wall_dead_end}

    cpu_decision = decision_summary("cpu")
    npu_decision = decision_summary("npu")
    threshold_states_match &= cpu_decision == npu_decision
    report = {
        "fixture": fixture,
        "model_sha256": digest(staging / "segformer_float.onnx"),
        "pixel_argmax_agreement": agreement,
        "different_pixels": int(np.count_nonzero(npu_classes != cpu_classes)),
        "total_pixels": int(npu_classes.size),
        "surface_kind_agreement": surface_agreement,
        "hazard_flag_differences": hazard_differences,
        "wall_flag_differences": wall_differences,
        "corridor_evidence": corridor_evidence,
        "cpu_corridor_decision": cpu_decision,
        "npu_corridor_decision": npu_decision,
        "maximum_corridor_ratio_drift": maximum_ratio_drift,
        "corridor_threshold_states_match": threshold_states_match,
        "max_logit_abs_difference": float(np.max(np.abs(npu - cpu))),
        "scope": "single public fixture; not dataset accuracy or field-safety validation",
    }
    if (
        agreement < 0.99 or surface_agreement < 0.995 or hazard_differences != 0 or
        maximum_ratio_drift > 0.02 or not threshold_states_match
    ):
        raise AssertionError(json.dumps(report, indent=2))
    (results / "segformer-fixture-check.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
