#!/usr/bin/env bash
# Forward only the explicitly selected device. The capability comes from the app's current HUD link.
set -euo pipefail
if [[ $# -lt 1 ]]; then
  echo "Usage: scripts/hud_forward.sh <adb-serial> [local-port] [phone-port]" >&2
  exit 2
fi
hud_device_serial="$1"
hud_local_port="${2:-8765}"
hud_phone_port="${3:-8765}"
if [[ ! "$hud_local_port" =~ ^[0-9]+$ || ! "$hud_phone_port" =~ ^[0-9]+$ ]]; then
  echo "Ports must be integers." >&2
  exit 2
fi
adb -s "$hud_device_serial" forward "tcp:$hud_local_port" "tcp:$hud_phone_port"
cat <<EOF
Forwarded the selected device on localhost:$hud_local_port.

Open the current HUD link from Developer tools → Bench. Its session token is required.
For the simulator, replace /hud with /sim in that same link, retaining ?token=… .
If you chose a different local port, replace its port with $hud_local_port.
The simulator starts with a bundled synthetic café. Camera preview is an explicit button.

T / ArrowUp = Talk, C = Done, left/right = browse, S = latest-line correction,
U = Undo, P = pause display. There is no Use or operational mode key.
Tokens rotate when the listening runtime restarts; an older link cannot read or control it.
EOF
