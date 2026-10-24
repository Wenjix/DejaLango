#!/usr/bin/env bash
# Exposes the phone's HUD server on the laptop for the /sim simulator, hud.xyz, or any browser tab.
#
#   scripts/hud_forward.sh                  laptop :8765 → phone :8765 (picks another laptop port if 8765 is busy)
#   scripts/hud_forward.sh 9876             laptop :9876 → phone :8765
#   scripts/hud_forward.sh 9876 8765        laptop :9876 → phone :8765 (explicit)
#   ANDROID_SERIAL=<serial> scripts/hud_forward.sh      when more than one device is plugged in
#
# The phone port is the "HUD port" in the bench knobs (default 8765); only the laptop side needs to change
# when something else on the laptop already owns the port.
set -euo pipefail
LAPTOP_PORT="${1:-8765}"
PHONE_PORT="${2:-8765}"

port_busy() {
  if command -v lsof >/dev/null 2>&1; then lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else (exec 3<>/dev/tcp/127.0.0.1/"$1") 2>/dev/null
  fi
}

# Clear a stale forward to the requested laptop port first (adb keeps forwards across reconnects).
adb forward --remove "tcp:$LAPTOP_PORT" >/dev/null 2>&1 || true

if port_busy "$LAPTOP_PORT"; then
  echo "laptop port $LAPTOP_PORT is in use by:"; (lsof -nP -iTCP:"$LAPTOP_PORT" -sTCP:LISTEN 2>/dev/null || true) | tail -n +1
  if [ -n "${1:-}" ]; then echo "pick another: scripts/hud_forward.sh <free laptop port> $PHONE_PORT" >&2; exit 1; fi
  for p in 8766 8767 8768 8769 9876 9877; do
    if ! port_busy "$p"; then LAPTOP_PORT="$p"; break; fi
  done
  echo "→ using laptop port $LAPTOP_PORT instead"
fi

adb forward "tcp:$LAPTOP_PORT" "tcp:$PHONE_PORT"
echo "forward: laptop :$LAPTOP_PORT → phone :$PHONE_PORT   (adb forward --list to see all)"
cat <<EOF

OUR simulator (works offline; the HUD over the laptop camera, glasses bezel, additive blend):
  open http://localhost:$LAPTOP_PORT/sim            (allow the camera; F = fullscreen)
  open http://localhost:$LAPTOP_PORT/sim?bg=photo   (no camera)   ?bg=black (plain)
  arrow keys select a reply, Enter confirms, P flips phrasebook ⇄ you, C cuts, Esc clears

hud.xyz (Meta Ray-Ban Display simulator, 600×600, D-pad) — when it is up:
  1. open https://hud.xyz  (redirects to the simulator)
  2. enter http://localhost:$LAPTOP_PORT/hud as the app URL

raw HUD page in a tab:     open http://localhost:$LAPTOP_PORT/hud
current state as JSON:     curl -s http://localhost:$LAPTOP_PORT/state
send an action without WS: curl -s "http://localhost:$LAPTOP_PORT/action?a=%7B%22action%22%3A%22next%22%7D"

If the page shows "connecting to phone…" forever: the app isn't listening (tap Start listening), or the phone
HUD port isn't $PHONE_PORT (bench → knobs), or adb lost the device (adb devices; re-run this script).
EOF
