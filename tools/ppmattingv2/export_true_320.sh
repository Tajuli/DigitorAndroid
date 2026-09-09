#!/usr/bin/env bash
set -euo pipefail

# Build a genuine fixed-shape PP-MattingV2 320 ONNX graph from PaddleSeg itself.
# 320 is useful for tight person ROI inference on mobile: materially cheaper than 384/512 while
# retaining more edge detail than 256. The first DPP feature map is 5x5, so this profile uses the
# safe [2,3,5] pyramid instead of requesting the default 6x6 pool from a 5x5 feature map.
#
# Inputs:
#   PADDLESEG_DIR         PaddleSeg release/2.10 checkout (required)
#   PPMATTING_CHECKPOINT  validated checkpoint; defaults to official human-512 weights
#   PPMATTING_CONFIG      320 config; defaults to this repository's 320 export profile
#   OUTPUT_ONNX           destination ONNX path (required)

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PADDLESEG_DIR="${PADDLESEG_DIR:?PADDLESEG_DIR must point to a PaddleSeg checkout}"
OUTPUT_ONNX="${OUTPUT_ONNX:?OUTPUT_ONNX must be set}"
PPMATTING_CONFIG="${PPMATTING_CONFIG:-${ROOT_DIR}/tools/ppmattingv2/ppmattingv2-stdc1-human_320-finetune.yml}"
DEFAULT_CHECKPOINT="${PADDLESEG_DIR}/Matting/pretrained_models/ppmattingv2-stdc1-human_512.pdparams"
PPMATTING_CHECKPOINT="${PPMATTING_CHECKPOINT:-${DEFAULT_CHECKPOINT}}"
WORK_DIR="${PADDLESEG_DIR}/Matting/output/digitor_true320_export"
CONFIG_DST="${PADDLESEG_DIR}/Matting/configs/ppmattingv2/digitor-ppmattingv2-stdc1-human_320.yml"

if [[ ! -f "${PADDLESEG_DIR}/Matting/tools/export.py" ]]; then
  echo "PaddleSeg Matting checkout not found at ${PADDLESEG_DIR}" >&2
  exit 2
fi
if [[ ! -f "${PPMATTING_CONFIG}" ]]; then
  echo "320 config missing: ${PPMATTING_CONFIG}" >&2
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
rm -f "${OUTPUT_ONNX}"

pushd "${PADDLESEG_DIR}/Matting" >/dev/null
python tools/export.py \
  --config "${CONFIG_DST}" \
  --model_path "${PPMATTING_CHECKPOINT}" \
  --save_dir "${WORK_DIR}" \
  --input_shape 1 3 320 320

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
dims = [d.dim_value for d in model.graph.input[0].type.tensor_type.shape.dim]
if dims != [1, 3, 320, 320]:
    raise SystemExit(f"Expected fixed [1,3,320,320] input, got {dims}")
if os.path.getsize(path) < 5_000_000:
    raise SystemExit(f"Generated ONNX is unexpectedly small: {os.path.getsize(path)} bytes")
print(f"Validated true PP-MattingV2 320 ONNX: {path} ({os.path.getsize(path)} bytes)")
PY
