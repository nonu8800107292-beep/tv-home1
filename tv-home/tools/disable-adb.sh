#!/usr/bin/env bash
# Same as disable.sh, but with the regular `adb` (after `adb pair` + `adb connect`). Use this on Android 11+
# Google TV, where Wireless debugging needs a pairing code and does not listen on port 5555.
# Packages are disabled for user 0, never uninstalled. Undo with tools/restore-adb.sh <same list>.
# Usage: tools/disable-adb.sh examples/realme-4k-tv-stick/debloat.txt
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/disable-adb.sh <debloat list>}"
{ grep -vE '^\s*(#|$)' "$LIST" || true; } | awk '{print $1}' | while read -r pkg; do
  echo "disable $pkg"
  # One pm call per shell command (chained calls can stop early); </dev/null keeps adb off the loop's stdin.
  adb shell pm disable-user --user 0 "$pkg" </dev/null
done
