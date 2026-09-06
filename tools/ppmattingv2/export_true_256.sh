#!/usr/bin/env bash
set -euo pipefail

# Build a *fixed-shape* PP-MattingV2 256 ONNX graph from PaddleSeg itself.
# This deliberately does not resize the already-exported 512 ONNX graph and does not feed a 512
# ncnn graph with a 256 tensor. Both of those approaches preserve 512-specific shape constants and
# produced corrupted mattes on the Symphony Z60.
#
# Inputs:
#   PADDLESEG_DIR         PaddleSeg release/2.10 checkout (required)
#   PPMATTING_CHECKPOINT  Fine-tuned 256 checkpoint when available; defaults to official 512 warm start
#   PPMATTING_CONFIG      256 config; defaults to this repository's fine-tune profile
#   OUTPUT_ONNX           destination ONNX path (required)
#
# The official 512 checkpoint is weight-compatible with the 256 architecture and is useful for
# converter/integration validation. Production-quality 256 weights should be produced with
# train_true_256.sh and supplied through PPMATTING_CHECKPOINT.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PADDLESEG_DIR="${PADDLESEG_DIR:?PADDLESEG_DIR must point to a PaddleSeg checkout}"
OUTPUT_ONNX="${OUTPUT_ONNX:?OUTPUT_ONNX must be set}"
PPMATTING_CONFIG="${PPMATTING_CONFIG:-${ROOT_DIR}/tools/ppmattingv2/ppmattingv2-stdc1-human_256-finetune.yml}"
DEFAULT_CHECKPOINT="${PADDLESEG_DIR}/Matting/pretrained_models/ppmattingv2-stdc1-human_512.pdparams"
PPMATTING_CHECKPOINT="${PPMATTING_CHECKPOINT:-${DEFAULT_CHECKPOINT}}"
WORK_DIR="${PADDLESEG_DIR}/Matting/output/digitor_true256_export"
CONFIG_DST="${PADDLESEG_DIR}/Matting/configs/ppmattingv2/digitor-ppmattingv2-stdc1-human_256.yml"

if [[ ! -f "${PADDLESEG_DIR}/Matting/tools/export.py" ]]; then
  echo "PaddleSeg Matting checkout not found at ${PADDLESEG_DIR}" >&2
  exit 2
fi
if [[ ! -f "${PPMATTING_CONFIG}" ]]; then
  echo "256 config missing: ${PPMATTING_CONFIG}" >&2
  exit 2
fi
if [[ ! -f "${PPMATTING_CHECKPOINT}" ]]; then
  echo "PP-Matting checkpoint missing: ${PPMATTING_CHECKPOINT}" >&2
  exit 2
fi

mkdir -p "$(dirname "${OUTPUT_ONNX}")"
cp "${PPMATTING_CONFIG}" "${CONFIG_DST}"
rm -rf "${WORK_DIR}"
mkdir -p "${WORK_DIR}"

pushd "${PADDLESEG_DIR}/Matting" >/dev/null
python tools/export.py \
  --config "${CONFIG_DST}" \
  --model_path "${PPMATTING_CHECKPOINT}" \
  --save_dir "${WORK_DIR}" \
  --input_shape 1 3 256 256

paddle2onnx \
  --model_dir "${WORK_DIR}" \
  --model_filename model.pdmodel \
  --params_filename model.pdiparams \
  --opset_version 11 \
  --save_file "${OUTPUT_ONNX}"
popd >/dev/null

python - "${OUTPUT_ONNX}" <<'PY'
import os
import sys
import onnx

path = sys.argv[1]
model = onnx.load(path)
onnx.checker.check_model(model)
input_tensor = model.graph.input[0]
dims = [d.dim_value for d in input_tensor.type.tensor_type.shape.dim]
if dims != [1, 3, 256, 256]:
    raise SystemExit(f"Expected fixed [1,3,256,256] input, got {dims}")
if os.path.getsize(path) < 5_000_000:
    raise SystemExit(f"Generated ONNX is unexpectedly small: {os.path.getsize(path)} bytes")
print(f"Validated true PP-MattingV2 256 ONNX: {path} ({os.path.getsize(path)} bytes)")
PY
