#!/usr/bin/env bash
# Downloads the model weights into ./models (gitignored). Needs network and a Hugging Face login that has
# accepted the Gemma terms — run this BEFORE the event; the hackathon floor has no cloud in the loop.
#
#   scripts/fetch_models.sh [generic|tensor_g6|tensor_g5|sm8750] ...
#
#   generic    gemma-4-E2B-it.litertlm (2.6 GB) — CPU/GPU on any phone. Always downloaded.
#   tensor_g6  + gemma-4-E2B-it_Google_Tensor_G6.litertlm (3.3 GB) — Pixel 11 TPU (Backend.GOOGLE_TENSOR / NPU)
#   tensor_g5  + gemma-4-E2B-it_Google_Tensor_G5.litertlm (3.1 GB) — Pixel 10 TPU
#   sm8750     + gemma-4-E2B-it_qualcomm_sm8750.litertlm (3.0 GB) — Galaxy S25 (Snapdragon 8 Elite) NPU
#
# Not sure which SoC a phone has?  adb shell getprop ro.soc.model
#
# File names are kept as published; app/.../ModelFiles.kt picks the SoC bundle by Build.SOC_MODEL and falls
# back to the generic one. Repo ids are from huggingface.co/litert-community; override with env vars.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="$ROOT/models"
mkdir -p "$MODELS"

GEMMA_REPO="${GEMMA_REPO:-litert-community/gemma-4-E2B-it-litert-lm}"
EMBED_REPO="${EMBED_REPO:-}"   # auto-detected below unless set

if ! command -v hf >/dev/null 2>&1 && ! command -v huggingface-cli >/dev/null 2>&1; then
  echo "install the Hugging Face CLI first: pip install -U huggingface_hub  (then: hf auth login)" >&2; exit 1
fi
HF=$(command -v hf || command -v huggingface-cli)

include=("gemma-4-E2B-it.litertlm")
for v in "${@:-generic}"; do
  case "$v" in
    generic) ;;
    tensor_g6) include+=("gemma-4-E2B-it_Google_Tensor_G6.litertlm") ;;
    tensor_g5) include+=("gemma-4-E2B-it_Google_Tensor_G5.litertlm") ;;
    sm8750)    include+=("gemma-4-E2B-it_qualcomm_sm8750.litertlm") ;;
    *) echo "unknown variant: $v (generic|tensor_g6|tensor_g5|sm8750)" >&2; exit 1 ;;
  esac
done

echo "== Gemma 4 E2B from $GEMMA_REPO: ${include[*]}"
args=()
for f in "${include[@]}"; do args+=(--include "$f"); done
"$HF" download "$GEMMA_REPO" "${args[@]}" --local-dir "$MODELS"

# EmbeddingGemma 2 (text). The repo name under litert-community is resolved by search so a rename doesn't
# break this script; set EMBED_REPO to skip the search.
if [ -z "$EMBED_REPO" ]; then
  EMBED_REPO=$(python3 - <<'PY'
from huggingface_hub import HfApi
api = HfApi()
cands = [m.id for m in api.list_models(author="litert-community", search="embeddinggemma", limit=50)]
# prefer: v2 + text + litert-lm bundle
def score(i):
    s = i.lower()
    return (("embeddinggemma-2" in s or "embeddinggemma2" in s), ("text" in s), ("litert-lm" in s or "litertlm" in s))
cands.sort(key=score, reverse=True)
print(cands[0] if cands else "")
PY
)
fi
if [ -z "$EMBED_REPO" ]; then
  echo "could not find an EmbeddingGemma repo under litert-community; set EMBED_REPO=<owner/repo> and rerun" >&2
  exit 1
fi
echo "== EmbeddingGemma from $EMBED_REPO (.litertlm files)"
"$HF" download "$EMBED_REPO" --include "*.litertlm" --local-dir "$MODELS/_embed"
shopt -s nullglob
emb=("$MODELS"/_embed/*.litertlm)
if [ ${#emb[@]} -gt 0 ]; then
  # Prefer a generic (non-SoC) text bundle; keep its published name but also provide the canonical one.
  pick="${emb[0]}"
  for f in "${emb[@]}"; do case "$(basename "$f")" in *_Google_*|*_qualcomm_*|*_mediatek_*|*_intel_*) ;; *) pick="$f"; break ;; esac; done
  cp -f "$pick" "$MODELS/embeddinggemma-2-text.litertlm"
fi
rm -rf "$MODELS/_embed"

ls -lh "$MODELS"
echo "next: scripts/push_models.sh"
