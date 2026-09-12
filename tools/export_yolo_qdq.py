"""Export portable YOLO11n QDQ for on-device QNN compilation (no EPContext).

Run with the local .venv-export Python. Requires torch (CPU is enough),
ultralytics, onnx, onnxruntime, onnxslim and Pillow. Uses COCO128 or a larger
representative image directory; random-input calibration is deliberately absent.
Artifacts and a provenance manifest go under models/staging (gitignored).
This creates an experimental model; guarded HTP execution and accuracy checks
are required before deploying it in the walking loop.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
from onnxruntime.quantization import CalibrationDataReader, QuantType, quantize
from onnxruntime.quantization.execution_providers.qnn import get_qnn_qdq_config, qnn_preprocess_model
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
WEIGHTS_SHA256 = "0ebbc80d4a7680d14987a577cd21342b65ecfd94632bd9a8da63ae6417644ee1"


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def pixels(path: Path) -> np.ndarray:
    """Match the app's Letterbox.sourcePixelFor: RGB, nearest, 114 padding."""
    with Image.open(path) as image:
        rgb = np.asarray(image.convert("RGB"))
    h, w = rgb.shape[:2]
    scale = min(640 / w, 640 / h)
    sw, sh = int(w * scale + 0.5), int(h * scale + 0.5)
    xs = np.minimum((np.arange(sw) / scale).astype(int), w - 1)
    ys = np.minimum((np.arange(sh) / scale).astype(int), h - 1)
    out = np.full((640, 640, 3), 114, dtype=np.uint8)
    px, py = (640 - sw) // 2, (640 - sh) // 2
    out[py:py + sh, px:px + sw] = rgb[ys[:, None], xs]
    return np.ascontiguousarray(out.transpose(2, 0, 1)[None], dtype=np.float32) / 255


class Images(CalibrationDataReader):
    def __init__(self, paths: list[Path], input_name: str):
        self.paths, self.input_name = paths, input_name
        self.rewind()

    def get_next(self):
        path = next(self.iterator, None)
        return None if path is None else {self.input_name: pixels(path)}

    def rewind(self):
        self.iterator = iter(self.paths)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--images", type=Path, required=True)
    args = parser.parse_args()
    paths = sorted(p for p in args.images.rglob("*") if p.suffix.lower() in {".jpg", ".jpeg", ".png"})
    if len(paths) < 128:
        raise SystemExit(f"Need at least 128 calibration images, found {len(paths)}")
    staging = ROOT / "models/staging"
    weights = staging / "yolo11n.pt"
    if digest(weights) != WEIGHTS_SHA256:
        raise SystemExit("YOLO11n weight checksum differs from the handoff; refusing export")
    fp32 = staging / "yolo11n_fp32_nchw.onnx"
    qdq = staging / "yolo11n_qdq.onnx"
    if qdq.exists():
        raise SystemExit(f"Refusing to overwrite {qdq}; move the previous experiment first")
    if not fp32.exists():
        from ultralytics import YOLO
        exported = Path(YOLO(str(weights)).export(format="onnx", imgsz=640, batch=1,
                                                dynamic=False, simplify=True, opset=20, device="cpu"))
        exported.rename(fp32)
    model = onnx.load(fp32)
    assert not any(n.op_type == "EPContext" for n in model.graph.node)
    assert [d.dim_value for d in model.graph.input[0].type.tensor_type.shape.dim] == [1, 3, 640, 640]
    preproc = staging / "yolo11n_preproc.onnx"
    # ORT 1.29/1.30's preprocessor calls dict.update on protobuf entries.
    # Preserve the class-name metadata around that helper without patching ORT.
    metadata = {item.key: item.value for item in model.metadata_props}
    del model.metadata_props[:]
    changed = qnn_preprocess_model(model, str(preproc))
    # The helper mutates its input when writing temporary external weights;
    # reload the original if it did not write a transformed graph.
    processed = onnx.load(preproc) if changed else onnx.load(fp32)
    onnx.external_data_helper.convert_model_from_external_data(processed)
    onnx.helper.set_model_props(processed, {**{p.key: p.value for p in processed.metadata_props}, **metadata})
    onnx.save(processed, preproc)
    source = preproc
    reader = Images(paths, model.graph.input[0].name)
    print(f"Calibrating w8a16 on {len(paths)} real images", flush=True)
    config = get_qnn_qdq_config(str(source), reader, activation_type=QuantType.QUInt16,
                                weight_type=QuantType.QUInt8)
    quantize(str(source), str(qdq), config)
    result = onnx.load(qdq)
    counts = Counter(n.op_type for n in result.graph.node)
    assert not counts["EPContext"] and counts["QuantizeLinear"] and counts["DequantizeLinear"]
    assert all(v.type.tensor_type.elem_type == onnx.TensorProto.FLOAT
               for v in list(result.graph.input) + list(result.graph.output))
    onnx.checker.check_model(result)
    # CPU execution verifies public float IO and finite output, not NPU support.
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 4
    session = ort.InferenceSession(str(qdq), sess_options=opts, providers=["CPUExecutionProvider"])
    output = session.run(None, {session.get_inputs()[0].name: pixels(paths[0])})[0]
    assert output.shape == (1, 84, 8400) and np.isfinite(output).all()
    report = {
        "weights_sha256": digest(weights), "fp32_sha256": digest(fp32),
        "qdq_sha256": digest(qdq), "onnxruntime": ort.__version__,
        "calibration": [{"name": p.relative_to(args.images).as_posix(), "sha256": digest(p)} for p in paths],
        "preprocess": "RGB, app nearest-neighbour letterbox, 114 padding, NCHW float32 /255",
        "nodes": dict(counts), "cpu_smoke_output_shape": list(output.shape),
        "npu_verified": False, "accuracy_validated": False,
    }
    qdq.with_suffix(".json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Wrote {qdq}: {qdq.stat().st_size} bytes; no EPContext; float32 IO", flush=True)


if __name__ == "__main__":
    main()
