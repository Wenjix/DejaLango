#!/usr/bin/env bash
# Downloads the model weights into ./models (gitignored). Needs network and a Hugging Face login that has
# accepted the Gemma terms — run this BEFORE the event; the hackathon floor has no cloud in the loop.
#
#   scripts/fetch_models.sh [generic|tensor_g6|tensor_g5|sm8750] ...
#
#   generic    gemma-4-E2B-it.litertlm (2.6 GB) — CPU/GPU on any phone. Always downloaded.
#              + embeddinggemma-2-text-270m.litertlm (165 MB) — the embedder's CPU fallback. Always downloaded.
#   tensor_g6  + gemma-4-E2B-it_Google_Tensor_G6.litertlm (3.3 GB) — Pixel 11 TPU (Backend.GOOGLE_TENSOR / NPU)
#              + embeddinggemma-2-text-270m_Google_Tensor_G6.litertlm (200 MB) — the embedder on the Pixel 11 TPU
#   tensor_g5  + gemma-4-E2B-it_Google_Tensor_G5.litertlm (3.1 GB) — Pixel 10 TPU
#              + embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm (166 MB)
#   sm8750     + gemma-4-E2B-it_qualcomm_sm8750.litertlm (3.0 GB) — Galaxy S25 (Snapdragon 8 Elite) NPU
#              + embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm (188 MB)
#
# Not sure which SoC a phone has?  adb shell getprop ro.soc.model
#
# File names are kept as published; app/.../ModelFiles.kt picks the SoC bundle by Build.SOC_MODEL and falls
# back to the generic one. Never rename one: a renamed vendor build can't be told from the generic file (an
# Intel NPU embedder once posed as it). Repo ids are from huggingface.co/litert-community; override with env vars.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="$ROOT/models"
mkdir -p "$MODELS"

GEMMA_REPO="${GEMMA_REPO:-litert-community/gemma-4-E2B-it-litert-lm}"
EMBED_REPO="${EMBED_REPO:-litert-community/embeddinggemma-2-text-270m-litert-lm}"

if ! command -v hf >/dev/null 2>&1 && ! command -v huggingface-cli >/dev/null 2>&1; then
  echo "install the Hugging Face CLI first: pip install -U huggingface_hub  (then: hf auth login)" >&2; exit 1
fi
HF=$(command -v hf || command -v huggingface-cli)

include=("gemma-4-E2B-it.litertlm")
embed=("embeddinggemma-2-text-270m.litertlm")
for v in "${@:-generic}"; do
  case "$v" in
    generic) ;;
    tensor_g6) include+=("gemma-4-E2B-it_Google_Tensor_G6.litertlm"); embed+=("embeddinggemma-2-text-270m_Google_Tensor_G6.litertlm") ;;
    tensor_g5) include+=("gemma-4-E2B-it_Google_Tensor_G5.litertlm"); embed+=("embeddinggemma-2-text-270m_Google_Tensor_G5.litertlm") ;;
    sm8750)    include+=("gemma-4-E2B-it_qualcomm_sm8750.litertlm"); embed+=("embeddinggemma-2-text-270m_Qualcomm_SM8750.litertlm") ;;
    *) echo "unknown variant: $v (generic|tensor_g6|tensor_g5|sm8750)" >&2; exit 1 ;;
  esac
done

echo "== Gemma 4 E2B from $GEMMA_REPO: ${include[*]}"
args=()
for f in "${include[@]}"; do args+=(--include "$f"); done
"$HF" download "$GEMMA_REPO" "${args[@]}" --local-dir "$MODELS"

# EmbeddingGemma 2 text (270M), by name: the repo also holds every other vendor's build (2.7 GB in all).
echo "== EmbeddingGemma 2 text from $EMBED_REPO: ${embed[*]}"
args=()
for f in "${embed[@]}"; do args+=(--include "$f"); done
"$HF" download "$EMBED_REPO" "${args[@]}" --local-dir "$MODELS"
for f in "${embed[@]}"; do
  [ -f "$MODELS/$f" ] || { echo "$f did not arrive from $EMBED_REPO" >&2; exit 1; }
done

ls -lh "$MODELS"
echo "next: scripts/push_models.sh"
