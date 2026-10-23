#!/usr/bin/env bash
# Exposes the phone's HUD server on the laptop as http://localhost:PORT/hud, for the hud.xyz
# Ray-Ban Display simulator or any browser tab. Usage: scripts/hud_forward.sh [port]
set -euo pipefail
PORT="${1:-8765}"
adb forward "tcp:$PORT" "tcp:$PORT"
cat <<EOF
forwarded: http://localhost:$PORT/hud

hud.xyz (Meta Ray-Ban Display simulator, 600×600, D-pad):
  1. open https://hud.xyz  (redirects to the simulator)
  2. enter http://localhost:$PORT/hud as the app URL
  3. D-pad / arrow keys select a reply, Enter confirms, P flips phrasebook ⇄ you, Esc clears

plain browser tab:         open http://localhost:$PORT/hud
current state as JSON:     curl -s http://localhost:$PORT/state
send an action without WS: curl -s "http://localhost:$PORT/action?a=%7B%22action%22%3A%22next%22%7D"
EOF
