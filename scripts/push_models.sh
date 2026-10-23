#!/usr/bin/env bash
# Pushes ./models/*.litertlm to the app's external files dir on the connected device.
# Usage: scripts/push_models.sh [applicationId]   (default dev.dejalango; the metavr flavor is dev.dejalango.metavr)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="${1:-dev.dejalango}"
DEST="/sdcard/Android/data/$APP_ID/files/models"

shopt -s nullglob
files=("$ROOT"/models/*.litertlm)
if [ ${#files[@]} -eq 0 ]; then echo "no models in $ROOT/models — run scripts/fetch_models.sh first" >&2; exit 1; fi

# The app must have been launched once so Android creates the external files dir with the right owner.
adb shell mkdir -p "$DEST"
for f in "${files[@]}"; do
  echo "== $(basename "$f") ($(du -h "$f" | cut -f1))"
  adb push "$f" "$DEST/"
done
adb shell ls -la "$DEST"
echo "done. In the app: knobs → fake model OFF → Stop → Start listening (build with -PwithLiteRtLm)."
