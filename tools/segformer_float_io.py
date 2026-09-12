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

The boundary promotion exposes the float values the graph already computes and
removes boundary rounding/clipping. It also evaluates the two constant
DequantizeLinear nodes feeding the final ADE20K classifier. Device profiling
proved those were the only CPU nodes left outside QNN; that constant fold is
bit-identical to the boundary-only graph and leaves every interior activation
QDQ op untouched.

    python tools/segformer_float_io.py

Reads  models/staging/segformer_base-onnx-w8a16/segformer_base.onnx
Writes models/staging/segformer_float.onnx  (single file, weights embedded)
"""

from __future__ import annotations

from pathlib import Path
import sys

import numpy as np
import onnx
import onnxruntime as ort
from onnx import TensorProto, helper, numpy_helper


REPO = Path(__file__).resolve().parents[1]
SOURCE = REPO / "models" / "staging" / "segformer_base-onnx-w8a16" / "segformer_base.onnx"
TARGET = REPO / "models" / "staging" / "segformer_float.onnx"
BASELINE = REPO / "models" / "staging" / "segformer_float_unfolded.onnx"

INPUT_NAME = "image"
OUTPUT_NAME = "class_logits"


def quantize_linear(values: np.ndarray, scale: np.ndarray, zero: np.ndarray, axis: int) -> np.ndarray:
    """Evaluate ONNX QuantizeLinear for a constant or validation tensor."""
    if scale.ndim:
        broadcast = [1] * values.ndim
        broadcast[axis] = scale.size
        scale = scale.reshape(broadcast)
        zero = zero.reshape(broadcast)
    limits = np.iinfo(zero.dtype)
    return np.clip(np.rint(values / scale) + zero, limits.min, limits.max).astype(zero.dtype)


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

    # Keep a temporary boundary-only graph so the targeted constant fold below
    # can be checked independently of the intentional public-IO promotion.
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(BASELINE), save_as_external_data=False)

    # --- fold the two constant classifier dequantizers --------------------
    # On ORT QNN 1.29 / qnn-runtime 2.42, the network becomes one QNN
    # partition except for the final classifier's constant weight and bias
    # DequantizeLinear nodes. A provider profile is the evidence for this
    # deliberately narrow rewrite; do not generalize it to all QDQ weights.
    producers = {output: node for node in graph.node for output in node.output}
    classifier = producers.get(float_output)
    if classifier is None or classifier.op_type != "Conv":
        print("expected final classifier Conv not found; refusing to guess", file=sys.stderr)
        return 1

    folded = []
    for classifier_input in classifier.input[1:]:
        dq = producers.get(classifier_input)
        if dq is None or dq.op_type != "DequantizeLinear" or len(dq.input) < 2:
            print(f"expected classifier constant DQ for {classifier_input}", file=sys.stderr)
            return 1
        initializer_by_name = {item.name: item for item in graph.initializer}
        nodes_to_remove = [dq]
        constant_inputs = list(dq.input)
        quantized_initializer = initializer_by_name.get(dq.input[0])
        if quantized_initializer is not None:
            quantized = numpy_helper.to_array(quantized_initializer).astype("float32")
        else:
            # Qualcomm's classifier weight is a constant Q -> DQ pair rather
            # than a pre-quantized initializer. Evaluate that pair exactly.
            q = producers.get(dq.input[0])
            if q is None or q.op_type != "QuantizeLinear" or any(
                name not in initializer_by_name for name in q.input
            ):
                print(f"classifier DQ {dq.name} is not constant; refusing to fold", file=sys.stderr)
                return 1
            source = numpy_helper.to_array(initializer_by_name[q.input[0]]).astype("float32")
            q_scale = numpy_helper.to_array(initializer_by_name[q.input[1]]).astype("float32")
            q_zero = numpy_helper.to_array(initializer_by_name[q.input[2]])
            q_axis_attr = next((attr for attr in q.attribute if attr.name == "axis"), None)
            q_axis = int(q_axis_attr.i) if q_axis_attr is not None else 1
            quantized = quantize_linear(source, q_scale, q_zero, q_axis).astype("float32")
            nodes_to_remove.append(q)
            constant_inputs.extend(q.input)

        if any(name not in initializer_by_name for name in dq.input[1:]):
            print(f"classifier DQ {dq.name} parameters are not constant", file=sys.stderr)
            return 1
        scale = numpy_helper.to_array(initializer_by_name[dq.input[1]]).astype("float32")
        zero = (
            numpy_helper.to_array(initializer_by_name[dq.input[2]]).astype("float32")
            if len(dq.input) > 2 else scale * 0
        )
        axis_attr = next((attr for attr in dq.attribute if attr.name == "axis"), None)
        axis = int(axis_attr.i) if axis_attr is not None else 1
        if scale.ndim:
            broadcast = [1] * quantized.ndim
            broadcast[axis] = scale.size
            scale = scale.reshape(broadcast)
            zero = zero.reshape(broadcast)
        dequantized = (quantized - zero) * scale

        for node in nodes_to_remove:
            graph.node.remove(node)
        graph.initializer.append(
            numpy_helper.from_array(dequantized.astype("float32"), classifier_input)
        )
        folded.append(dq.name)

        remaining_inputs = {name for node in graph.node for name in node.input}
        for old_name in set(constant_inputs):
            old_initializer = initializer_by_name.get(old_name)
            if old_initializer is not None and old_name not in remaining_inputs:
                graph.initializer.remove(old_initializer)

    print("folded classifier constants:")
    for name in folded:
        print(f"  {name}")

    onnx.checker.check_model(model, full_check=False)

    onnx.save(model, str(TARGET), save_as_external_data=False)

    check = onnx.load(str(TARGET), load_external_data=False)
    for value in list(check.graph.input) + list(check.graph.output):
        kind = onnx.TensorProto.DataType.Name(value.type.tensor_type.elem_type)
        dims = [d.dim_value for d in value.type.tensor_type.shape.dim]
        print(f"  {value.name}: {kind} {dims}")

    # Validate the two constant folds against the boundary-only float graph on
    # a deterministic input. This isolates the new transformation: public IO
    # promotion was already validated before the device port.
    count = 1 * 3 * 512 * 512
    sample = ((np.arange(count, dtype=np.uint32) % 65536) / 65535.0).astype("float32")
    sample = sample.reshape(1, 3, 512, 512)
    source_session = ort.InferenceSession(str(BASELINE), providers=["CPUExecutionProvider"])
    target_session = ort.InferenceSession(str(TARGET), providers=["CPUExecutionProvider"])
    source_output = source_session.run(None, {source_session.get_inputs()[0].name: sample})[0]
    target_output = target_session.run(None, {target_session.get_inputs()[0].name: sample})[0]
    max_abs = float(np.max(np.abs(source_output - target_output)))
    agreement = float(np.mean(np.argmax(source_output, axis=1) == np.argmax(target_output, axis=1)))
    tolerance = 1e-6
    if max_abs > tolerance or agreement != 1.0:
        print(
            f"rewrite parity failed: max_abs={max_abs}, tolerance={tolerance}, argmax={agreement}",
            file=sys.stderr,
        )
        return 1
    BASELINE.unlink()
    print(f"classifier-fold parity: max_abs={max_abs:.8f}, argmax agreement={agreement:.3f}")
    print(f"wrote {TARGET} ({TARGET.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
