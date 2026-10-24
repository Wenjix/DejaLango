#!/usr/bin/env bash
# Exposes the phone's HUD server on the laptop for the /sim simulator or any browser tab.
#
#   scripts/hud_forward.sh                  laptop :8765 → phone :8765 (picks another laptop port if 8765 is busy;
#                                           reuses an existing forward to the phone, so a re-run keeps the URL)
#   scripts/hud_forward.sh 9876             laptop :9876 → phone :8765
#   scripts/hud_forward.sh 9876 8765        laptop :9876 → phone :8765 (explicit)
#   ANDROID_SERIAL=<serial> scripts/hud_forward.sh      when more than one device is plugged in
#
# The phone port is the app's HUD port, 8765; the bench shows the HUD URL while listening. Only the laptop side
# needs to change when something else on the laptop already owns the port.
set -euo pipefail
LAPTOP_PORT="${1:-8765}"
PHONE_PORT="${2:-8765}"
ALTERNATES=(8766 8767 8768 8769 9876 9877)

port_busy() {
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else (exec 3<>/dev/tcp/127.0.0.1/"$1") 2>/dev/null
  fi
}

# Prints the laptop port of a forward to the phone port (only ANDROID_SERIAL's forwards when that is set).
# Lines read "<serial> tcp:<laptop> tcp:<phone>"; if adb can't list them, nothing is reused.
forwarded_port() {
  local list
  list="$(adb forward --list 2>/dev/null)" || return 0
  awk -v serial="${ANDROID_SERIAL:-}" -v phone="tcp:$PHONE_PORT" \
    '(serial == "" || $1 == serial) && $2 ~ /^tcp:[0-9]+$/ && $3 == phone { print substr($2, 5); exit }' <<<"$list"
}

EXISTING=""
if [ -n "${1:-}" ]; then
  # Clear a stale forward to the requested laptop port first: its adb listener would make the port look busy.
  adb forward --remove "tcp:$LAPTOP_PORT" >/dev/null 2>&1 || true
else
  # Reuse a forward that already reaches the phone port, such as an earlier run's. adb holds its laptop port,
  # so that port looks busy, and a re-run would move on to the next one and print a new URL.
  EXISTING="$(forwarded_port)"
fi

if [ -n "$EXISTING" ]; then
  LAPTOP_PORT="$EXISTING"
elif port_busy "$LAPTOP_PORT"; then
  echo "laptop port $LAPTOP_PORT is in use by:"; (lsof -nP -iTCP:"$LAPTOP_PORT" -sTCP:LISTEN 2>/dev/null || true) | tail -n +1
  if [ -n "${1:-}" ]; then echo "pick another: scripts/hud_forward.sh <free laptop port> $PHONE_PORT" >&2; exit 1; fi
  FREE=""
  for p in "${ALTERNATES[@]}"; do
    if ! port_busy "$p"; then FREE="$p"; break; fi
  done
  if [ -z "$FREE" ]; then
    echo "no free laptop port among $LAPTOP_PORT ${ALTERNATES[*]}" >&2
    echo "pass one: scripts/hud_forward.sh <free laptop port> $PHONE_PORT" >&2
    exit 1
  fi
  LAPTOP_PORT="$FREE"
  echo "→ using laptop port $LAPTOP_PORT instead"
fi

if [ -n "$EXISTING" ]; then
  echo "forward: laptop :$LAPTOP_PORT → phone :$PHONE_PORT, already set up   (adb forward --list to see all)"
else
  adb forward "tcp:$LAPTOP_PORT" "tcp:$PHONE_PORT"
  echo "forward: laptop :$LAPTOP_PORT → phone :$PHONE_PORT   (adb forward --list to see all)"
fi
cat <<EOF

glasses simulator (works offline; the HUD over the laptop camera, glasses bezel, additive blend):
  open http://localhost:$LAPTOP_PORT/sim            (allow the camera; F = fullscreen)
  open http://localhost:$LAPTOP_PORT/sim?bg=photo   (no camera)   ?bg=black (plain)
  arrow keys select a reply, Enter confirms, P flips phrasebook ⇄ you, C cuts, Esc clears

raw HUD page in a tab:     open http://localhost:$LAPTOP_PORT/hud
current state as JSON:     curl -s http://localhost:$LAPTOP_PORT/state
send an action without WS: curl -s -X POST "http://localhost:$LAPTOP_PORT/action?a=%7B%22action%22%3A%22next%22%7D"

If /sim doesn't load, or shows "connecting to phone…": the app isn't listening (tap Start listening; the HUD
server starts with it), or adb lost the device (adb devices; re-run this script).
EOF
