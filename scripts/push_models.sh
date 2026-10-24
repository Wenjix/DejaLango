#!/usr/bin/env bash
# Pushes ./models/*.litertlm into the app's internal storage (files/models/ in its data directory) on every connected
# phone, or only on $ANDROID_SERIAL when that is set; an emulator gets them only when named there, since the walkthrough
# build it runs has no inference. Each phone gets the generic bundles and those compiled for its own
# SoC (ro.soc.model, the Build.SOC_MODEL that app/.../ModelFiles.kt matches on); another chip's bundle runs only on that
# chip's accelerator, so it stays behind. A bundle already on the phone at full size is not sent again.
# Streamed through run-as, so the installed app must be a debug build. Not the external files dir
# (/sdcard/Android/data/<id>/files/models) as before: that is FUSE, and Google Tensor's TPU runtime reads Gemma's
# _Google_Tensor_Gx bundle with preadv2, which FUSE refuses (EOPNOTSUPP), so Gemma fell back to the GPU. The app
# still reads that directory, after its internal one.
# Usage: [ANDROID_SERIAL=<serial>] scripts/push_models.sh [applicationId]
#        (default dev.dejalango; the metavr flavor is dev.dejalango.metavr)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="${1:-dev.dejalango}"

shopt -s nullglob
files=("$ROOT"/models/*.litertlm)
if [ ${#files[@]} -eq 0 ]; then echo "no models in $ROOT/models — run scripts/fetch_models.sh first" >&2; exit 1; fi

# ModelFiles.normalize and ModelFiles.isSocBundle, so a phone gets exactly the bundles the app would pick from.
normalize() { printf '%s' "$1" | tr 'A-Z -' 'a-z__'; }
is_soc_bundle() { case "$1" in *_google_* | *_qualcomm_* | *_mediatek_* | *_intel_* | *tensor_g*) return 0 ;; *) return 1 ;; esac; }

# emulator-<port> is a local AVD, even before it is ready; one attached otherwise (adb connect) sets ro.boot.qemu, or
# ro.kernel.qemu before Android 12. </dev/null: adb shell would read the device list this runs inside of. Returns 2
# when it can't tell: a failed read must not pass for a phone.
is_emulator() {
  case "$1" in emulator-*) return 0 ;; esac
  [ "$2" = device ] || return 1
  local qemu
  qemu=$(adb -s "$1" shell 'getprop ro.boot.qemu; getprop ro.kernel.qemu' </dev/null | tr -d '\r') || return 2
  [[ "$qemu" == *1* ]]
}

# States come from the device list on stdout only: adb writes "* daemon not running; starting now" to stderr.
serials=() not_ready=() emulators=()
while IFS=$'\t' read -r serial state; do
  [ -z "${ANDROID_SERIAL:-}" ] || [ "$serial" = "$ANDROID_SERIAL" ] || continue
  if [ -z "${ANDROID_SERIAL:-}" ]; then
    emulator=0
    is_emulator "$serial" "$state" || emulator=$?
    if [ "$emulator" -eq 0 ]; then emulators+=("$serial"); continue; fi
    if [ "$emulator" -eq 2 ]; then not_ready+=("$serial: did not answer getprop"); continue; fi
  fi
  if [ "$state" = device ]; then serials+=("$serial"); else not_ready+=("$serial: $state"); fi
done < <(adb devices | awk -F'\t' 'NF == 2')
if [ ${#emulators[@]} -gt 0 ]; then echo "skipping emulator ${emulators[*]}: name one in ANDROID_SERIAL to push to it"; fi
if [ -n "${ANDROID_SERIAL:-}" ] && [ ${#serials[@]} -eq 0 ] && [ ${#not_ready[@]} -eq 0 ]; then
  not_ready=("$ANDROID_SERIAL: not connected")
fi
# A phone that is plugged in but not authorized would otherwise be left without its models, unnoticed.
if [ ${#not_ready[@]} -gt 0 ]; then
  printf 'not ready: %s\n' "${not_ready[@]}" >&2
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    echo "connect it, unlock it and allow USB debugging; adb devices lists the serials" >&2
  else
    echo "unlock it and allow USB debugging, or set ANDROID_SERIAL to push to one phone" >&2
  fi
  exit 1
fi
if [ ${#serials[@]} -eq 0 ]; then echo "no phone connected" >&2; exit 1; fi

push_to() {
  local serial=$1 soc key f name want got last stalls
  local skipped=()
  # errexit is off in here (push_to runs left of ||): a failed read must not pass for a phone without a SoC model.
  soc=$(adb -s "$serial" shell getprop ro.soc.model | tr -d '\r') || { echo "could not read ro.soc.model on $serial" >&2; return 1; }
  key=$(normalize "$soc")
  echo "=== $(adb -s "$serial" shell getprop ro.product.model | tr -d '\r') ($serial, ${soc:-no SoC reported})"
  adb -s "$serial" shell run-as "$APP_ID" mkdir -p files/models || { echo "install a debug build of $APP_ID on $serial first" >&2; return 1; }
  for f in "${files[@]}"; do
    name=$(basename "$f")
    # As in ModelFiles: a phone that reports no SoC gets no accelerator bundle.
    if is_soc_bundle "$(normalize "$name")" && { [ -z "$key" ] || [[ "$(normalize "$name")" != *"$key"* ]]; }; then
      skipped+=("$name")
      continue
    fi
    want=$(wc -c < "$f" | tr -d '[:space:]')
    got=$(adb -s "$serial" shell run-as "$APP_ID" stat -c %s "files/models/$name" 2>/dev/null | tr -d '\r') || true
    if [ "$got" = "$want" ]; then echo "== $name: already there"; continue; fi
    echo "== $name ($(du -h "$f" | cut -f1))"
    adb -s "$serial" exec-in run-as "$APP_ID" sh -c "cat > files/models/$name" < "$f" || return 1
    # exec-in returns once the file is sent; the phone may still be writing it. Wait for the full size, and fail if
    # it stops growing short of it (a full disk, a dropped cable).
    last=-1 stalls=0
    while :; do
      got=$(adb -s "$serial" shell run-as "$APP_ID" stat -c %s "files/models/$name" | tr -d '\r') || true
      [ "$got" = "$want" ] && break
      if [ "$got" = "$last" ]; then stalls=$((stalls + 1)); else stalls=0; fi
      if [ "$stalls" -ge 20 ]; then echo "$name stopped at ${got:-0} of $want bytes on $serial" >&2; return 1; fi
      last=$got
      sleep 0.5
    done
  done
  if [ ${#skipped[@]} -gt 0 ]; then echo "   not for ${soc:-a phone without a SoC model}: ${skipped[*]}"; fi
  adb -s "$serial" shell run-as "$APP_ID" ls -la files/models
}

failed=()
for serial in "${serials[@]}"; do push_to "$serial" || failed+=("$serial"); done
if [ ${#failed[@]} -gt 0 ]; then echo "failed on ${failed[*]}" >&2; exit 1; fi
echo "done. In the app, start listening; Settings → Developer tools → Bench shows the model and backend."
