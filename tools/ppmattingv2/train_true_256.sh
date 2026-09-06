#!/usr/bin/env bash
set -euo pipefail

# Fine-tune PP-MattingV2/STDC1 for the real 256x256 deployment profile.
# Intended for a CUDA machine / self-hosted GPU runner. CPU is supported for smoke testing only.
#
# Inputs:
#   PADDLESEG_DIR        PaddleSeg release/2.10 checkout (required)
#   DEVICE               gpu (default) or cpu
#   ITERS                fine-tune iterations, default 20000
#   BATCH_SIZE           default 16 on GPU, 2 on CPU
#   OUTPUT_DIR           checkpoint directory, default <PaddleSeg>/Matting/output/digitor_true256
#
# Outputs:
#   best checkpoint under OUTPUT_DIR, ready for export_true_256.sh via PPMATTING_CHECKPOINT.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PADDLESEG_DIR="${PADDLESEG_DIR:?PADDLESEG_DIR must point to a PaddleSeg release/2.10 checkout}"
DEVICE="${DEVICE:-gpu}"
ITERS="${ITERS:-20000}"
if [[ "${DEVICE}" == "cpu" ]]; then
  BATCH_SIZE="${BATCH_SIZE:-2}"
else
  BATCH_SIZE="${BATCH_SIZE:-16}"
fi
OUTPUT_DIR="${OUTPUT_DIR:-${PADDLESEG_DIR}/Matting/output/digitor_true256}"
CONFIG_SRC="${ROOT_DIR}/tools/ppmattingv2/ppmattingv2-stdc1-human_256-finetune.yml"
CONFIG_DST="${PADDLESEG_DIR}/Matting/configs/ppmattingv2/digitor-ppmattingv2-stdc1-human_256.yml"
DATA_DIR="${PADDLESEG_DIR}/Matting/data/PPM-100"
PRETRAINED_DIR="${PADDLESEG_DIR}/Matting/pretrained_models"
PRETRAINED="${PRETRAINED_DIR}/ppmattingv2-stdc1-human_512.pdparams"

mkdir -p "${PADDLESEG_DIR}/Matting/data" "${PRETRAINED_DIR}"
cp "${CONFIG_SRC}" "${CONFIG_DST}"

if [[ ! -d "${DATA_DIR}" ]]; then
  tmp_zip="${PADDLESEG_DIR}/Matting/data/PPM-100.zip"
  curl -L --fail --retry 4 --retry-delay 2 \
    -o "${tmp_zip}" \
    https://paddleseg.bj.bcebos.com/matting/datasets/PPM-100.zip
  unzip -q -o "${tmp_zip}" -d "${PADDLESEG_DIR}/Matting/data"
  rm -f "${tmp_zip}"
fi

if [[ ! -f "${PRETRAINED}" ]]; then
  curl -L --fail --retry 4 --retry-delay 2 \
    -o "${PRETRAINED}" \
    https://paddleseg.bj.bcebos.com/matting/models/ppmattingv2-stdc1-human_512.pdparams
fi

rm -rf "${OUTPUT_DIR}"
mkdir -p "${OUTPUT_DIR}"

pushd "${PADDLESEG_DIR}/Matting" >/dev/null
python tools/train.py \
  --config "${CONFIG_DST}" \
  --device "${DEVICE}" \
  --iters "${ITERS}" \
  --batch_size "${BATCH_SIZE}" \
  --learning_rate 0.001 \
  --save_interval 1000 \
  --eval_begin_iters 1000 \
  --do_eval \
  --metrics sad mse grad conn \
  --save_dir "${OUTPUT_DIR}" \
  --num_workers 4 \
  --seed 20260907
popd >/dev/null

echo "True-256 fine-tune finished: ${OUTPUT_DIR}"
echo "Export the selected/best model with:"
echo "  PPMATTING_CHECKPOINT=<checkpoint.pdparams> PADDLESEG_DIR=${PADDLESEG_DIR} OUTPUT_ONNX=<out.onnx> tools/ppmattingv2/export_true_256.sh"
