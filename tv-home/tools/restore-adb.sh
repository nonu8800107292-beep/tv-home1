#!/usr/bin/env bash
# Undo everything with the regular `adb`: re-enable every package in the debloat list, bring back the stock
# launcher (and Google TV's setup app) and make the stock launcher the default home again.
# Usage: tools/restore-adb.sh <debloat list>
#   STOCK_LAUNCHER=com.google.android.tvlauncher/.MainActivity tools/restore-adb.sh list.txt   (older Android TV)
set -euo pipefail
cd "$(dirname "$0")/.."
LIST="${1:?usage: tools/restore-adb.sh <debloat list>}"
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
{ grep -vE '^\s*(#|$)' "$LIST" || true; } | awk '{print $1}' | while read -r pkg; do
  echo "enable $pkg"
  adb shell pm enable "$pkg" </dev/null
done
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell pm enable "${STOCK_LAUNCHER%%/*}"
adb shell cmd package set-home-activity --user 0 "$STOCK_LAUNCHER"
adb shell input keyevent KEYCODE_HOME
echo "Restored. Press Home on the remote."
