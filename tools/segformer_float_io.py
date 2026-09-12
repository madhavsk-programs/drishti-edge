"""Give Qualcomm's SegFormer-B0 export float32 inputs and outputs.

WHY THIS EXISTS
---------------
The published `segformer_base.onnx` declares `image` as **uint16** and
`class_logits` as **uint16** (quantized IO). ONNX Runtime's *Java* API cannot
construct a uint16 tensor at all: `ai.onnxruntime.OnnxJavaType` has no UINT16
constant, so `createTensor` can only tag a ShortBuffer as INT16 and `Run` then
rejects it with

    Unexpected input data type. Actual: (tensor(int16)), expected: (tensor(uint16))

That is an ORT Java limitation, not a model defect, and it blocks the model on
BOTH the CPU and the NPU rung.

WHAT THIS DOES
--------------
The graph's very first node is `DequantizeLinear(image) -> image_updated`, and
its very last is `QuantizeLinear(class_logits_updated) -> class_logits`. The
tensors `image_updated` and `class_logits_updated` are ALREADY the float values
the network computes on. So instead of quantizing in Kotlin - which would mean
reproducing Qualcomm's scale/zero-point arithmetic by hand and being wrong
silently - this drops those two boundary nodes and promotes the float tensors
to be the graph's IO.

This is LOSSLESS. It removes a quantize/dequantize round-trip at the boundary
rather than adding one. Every interior QDQ op is untouched, so the graph the
NPU would compile is the same graph.

    python tools/segformer_float_io.py

Reads  models/staging/segformer_base-onnx-w8a16/segformer_base.onnx
Writes models/staging/segformer_float.onnx  (single file, weights embedded)
"""

from __future__ import annotations

from pathlib import Path
import sys

import onnx
from onnx import TensorProto, helper, numpy_helper


REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "models" / "staging" / "segformer_base-onnx-w8a16" / "segformer_base.onnx"
TARGET = REPO / "models" / "staging" / "segformer_float.onnx"

INPUT_NAME = "image"
OUTPUT_NAME = "class_logits"


def main() -> int:
    if not SOURCE.is_file():
        print(f"missing {SOURCE}", file=sys.stderr)
        return 1

    model = onnx.load(str(SOURCE))
    graph = model.graph

    # --- locate the two boundary nodes ------------------------------------
    input_dq = next(
        (n for n in graph.node if n.op_type == "DequantizeLinear" and INPUT_NAME in n.input),
        None,
    )
    output_q = next(
        (n for n in graph.node if n.op_type == "QuantizeLinear" and OUTPUT_NAME in n.output),
        None,
    )
    if input_dq is None or output_q is None:
        print("expected boundary quant nodes not found; refusing to guess", file=sys.stderr)
        return 1

    float_input = input_dq.output[0]        # image_updated
    float_output = output_q.input[0]        # class_logits_updated

    initializers = {i.name for i in graph.initializer}
    scale_name = input_dq.input[1]
    print(f"input  DequantizeLinear {input_dq.name}: {INPUT_NAME} -> {float_input}")
    print(f"output QuantizeLinear   {output_q.name}: {float_output} -> {OUTPUT_NAME}")
    if scale_name in initializers:
        scale = numpy_helper.to_array(
            next(i for i in graph.initializer if i.name == scale_name)
        )
        # 1/scale ~= 65535 confirms the documented [0,1] input range.
        print(f"  input scale {scale} -> full-scale value {1.0 / float(scale):.1f}")

    # --- drop them and re-point the graph IO ------------------------------
    graph.node.remove(input_dq)
    graph.node.remove(output_q)

    # Identity keeps the public tensor names stable, so nothing downstream has
    # to learn the internal `_updated` names.
    graph.node.insert(
        0,
        helper.make_node("Identity", [INPUT_NAME], [float_input], name="drishti_float_input"),
    )
    graph.node.append(
        helper.make_node("Identity", [float_output], [OUTPUT_NAME], name="drishti_float_output"),
    )

    def retype(values, name: str) -> None:
        for value in values:
            if value.name == name:
                value.type.tensor_type.elem_type = TensorProto.FLOAT

    retype(graph.input, INPUT_NAME)
    retype(graph.output, OUTPUT_NAME)

    # Stale value_info for the promoted tensors would contradict the new types.
    for stale in [v for v in graph.value_info if v.name in {float_input, float_output}]:
        graph.value_info.remove(stale)

    onnx.checker.check_model(model, full_check=False)

    TARGET.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(TARGET), save_as_external_data=False)

    check = onnx.load(str(TARGET), load_external_data=False)
    for value in list(check.graph.input) + list(check.graph.output):
        kind = onnx.TensorProto.DataType.Name(value.type.tensor_type.elem_type)
        dims = [d.dim_value for d in value.type.tensor_type.shape.dim]
        print(f"  {value.name}: {kind} {dims}")
    print(f"wrote {TARGET} ({TARGET.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
