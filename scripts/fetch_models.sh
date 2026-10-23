#!/usr/bin/env bash
# Downloads the model weights into ./models (gitignored). Needs network and a Hugging Face token that has
# accepted the Gemma terms — run this BEFORE the event; the hackathon floor has no cloud in the loop.
#
#   HF_TOKEN=hf_xxx scripts/fetch_models.sh
#
# The LiteRT community uploads rename files occasionally. The repo ids below are the ones to verify on
# https://huggingface.co/litert-community (search "gemma-4-E2B" and "embeddinggemma-2"); override with env vars.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODELS="$ROOT/models"
mkdir -p "$MODELS"

GEMMA_REPO="${GEMMA_REPO:-litert-community/gemma-4-E2B-it-litert-lm}"          # VERIFY
EMBED_REPO="${EMBED_REPO:-litert-community/embeddinggemma-2-text-litert-lm}"   # VERIFY

if ! command -v hf >/dev/null 2>&1 && ! command -v huggingface-cli >/dev/null 2>&1; then
  echo "install the Hugging Face CLI first: pip install -U huggingface_hub" >&2; exit 1
fi
HF=$(command -v hf || command -v huggingface-cli)

echo "== Gemma 4 E2B (.litertlm) from $GEMMA_REPO"
"$HF" download "$GEMMA_REPO" --include "*.litertlm" --local-dir "$MODELS/_gemma"
echo "== EmbeddingGemma 2 text (.litertlm) from $EMBED_REPO"
"$HF" download "$EMBED_REPO" --include "*.litertlm" --local-dir "$MODELS/_embed"

# Flatten to the canonical names ModelFiles.kt looks for first (it also matches loosely, so any *.litertlm works).
shopt -s nullglob
for f in "$MODELS"/_gemma/*.litertlm; do mv -f "$f" "$MODELS/gemma-4-E2B-it.litertlm"; break; done
for f in "$MODELS"/_embed/*.litertlm; do mv -f "$f" "$MODELS/embeddinggemma-2-text.litertlm"; break; done
rm -rf "$MODELS/_gemma" "$MODELS/_embed"

ls -lh "$MODELS"
echo "next: scripts/push_models.sh"
