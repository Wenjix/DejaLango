#!/usr/bin/env bash
# Pushes ./models/*.litertlm into the app's internal storage on the connected device (files/models/ in its data
# directory), streamed through run-as, so the installed app must be a debug build. Not the external files dir
# (/sdcard/Android/data/<id>/files/models) as before: that is FUSE, and Google Tensor's TPU runtime reads Gemma's
# _Google_Tensor_Gx bundle with preadv2, which FUSE refuses (EOPNOTSUPP), so Gemma fell back to the GPU. The app
# still reads that directory, after its internal one.
# Usage: scripts/push_models.sh [applicationId]   (default dev.dejalango; the metavr flavor is dev.dejalango.metavr)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="${1:-dev.dejalango}"

shopt -s nullglob
files=("$ROOT"/models/*.litertlm)
if [ ${#files[@]} -eq 0 ]; then echo "no models in $ROOT/models — run scripts/fetch_models.sh first" >&2; exit 1; fi

adb shell run-as "$APP_ID" mkdir -p files/models
for f in "${files[@]}"; do
  name=$(basename "$f")
  echo "== $name ($(du -h "$f" | cut -f1))"
  adb exec-in run-as "$APP_ID" sh -c "cat > files/models/$name" < "$f"
  # exec-in returns once the file is sent; the phone may still be writing it. Wait for the full size, and fail if
  # it stops growing short of it (a full disk, a dropped cable).
  want=$(wc -c < "$f" | tr -d '[:space:]')
  last=-1 stalls=0
  while :; do
    got=$(adb shell run-as "$APP_ID" stat -c %s "files/models/$name" | tr -d '\r')
    [ "$got" = "$want" ] && break
    if [ "$got" = "$last" ]; then stalls=$((stalls + 1)); else stalls=0; fi
    if [ "$stalls" -ge 20 ]; then echo "$name stopped at $got of $want bytes" >&2; exit 1; fi
    last=$got
    sleep 0.5
  done
done
adb shell run-as "$APP_ID" ls -la files/models
echo "done. In the app: knobs → fake model OFF → Stop → Start listening (build with -PwithLiteRtLm)."
