#!/usr/bin/env bash
# Pulls the per-day TurnTrace CSVs off the device into ./traces (gitignored).
# Usage: scripts/pull_traces.sh [applicationId]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_ID="${1:-dev.dejalango}"
mkdir -p "$ROOT/traces"
adb pull "/sdcard/Android/data/$APP_ID/files/traces" "$ROOT/traces/" || { echo "no traces yet (listen for a few turns first)" >&2; exit 1; }
ls -la "$ROOT/traces"/traces 2>/dev/null || true
echo
echo "columns: $(head -1 "$(ls "$ROOT"/traces/traces/*.csv | tail -1)")"
echo "quick look at transcript/replies latency (ms):"
tail -n +2 "$(ls "$ROOT"/traces/traces/*.csv | tail -1)" | awk -F, '{ if ($7!="") a=$7-$2; else a=""; if ($11!="") b=$11-$2; else b=""; printf "#%s audio=%sms transcript=%s replies=%s %s%s%s\n", $1, $3, a, b, ($19=="true"?"cancelled ":""), ($20=="true"?"timeout ":""), ($21=="true"?"parseFailed":"") }'
