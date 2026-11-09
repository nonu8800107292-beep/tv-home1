#!/usr/bin/env bash
# Build the launcher APK with the bare SDK tools (no Gradle) and install it on the TV as the home screen.
#   launcher/build-and-install.sh            build + install with the bundled Python ADB client (TV listening on :5555)
#   launcher/build-and-install.sh --adb      build + install with the regular `adb` (after `adb pair` / `adb connect`)
#   launcher/build-and-install.sh --build    build only (this is what the GitHub "Build APK" workflow runs)
set -euo pipefail
cd "$(dirname "$0")"

# JDK 17 and the Android SDK (platform 34 + build-tools 34.0.0). Override with JAVA_HOME / ANDROID_HOME.
if [ -z "${JAVA_HOME:-}" ]; then
  for c in /opt/homebrew/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/temurin-17-jdk-amd64; do
    if [ -d "$c" ]; then JAVA_HOME="$c"; break; fi
  done
fi
if [ -n "${JAVA_HOME:-}" ]; then export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"; fi
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}}"
BT="$SDK/build-tools/34.0.0"
JAR="$SDK/platforms/android-34/android.jar"
# Google TV stock launcher; older Android TV sets use com.google.android.tvlauncher.
STOCK_LAUNCHER="${STOCK_LAUNCHER:-com.google.android.apps.tv.launcherx}"
B=build

rm -rf "$B" && mkdir -p "$B/gen" "$B/classes" "$B/dex"
"$BT/aapt2" compile --dir res -o "$B/res.zip"
"$BT/aapt2" link -o "$B/base.apk" -I "$JAR" --manifest AndroidManifest.xml --java "$B/gen" \
  -A assets --min-sdk-version 30 --target-sdk-version 30 "$B/res.zip"
javac -nowarn -Xlint:-options --release 11 -classpath "$JAR" -d "$B/classes" \
  $(find src "$B/gen" -name '*.java')
"$BT/d8" --min-api 30 --lib "$JAR" --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B/dex" && zip -q -u ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 "$B/unsigned.apk" "$B/aligned.apk"
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$B/tvhome.apk" "$B/aligned.apk"
echo "built $B/tvhome.apk ($(wc -c < "$B/tvhome.apk") bytes)"
MODE="${1:-}"
[ "$MODE" = "--build" ] && exit 0

# Make it the home screen. The stock launcher is disabled, never uninstalled; tools/restore*.sh undo this.
HOME_COMMANDS=(
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity"
  "pm disable-user --user 0 $STOCK_LAUNCHER"
  "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
  "cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity"
  "am force-stop com.android.tv.settings"
  "input keyevent KEYCODE_HOME"
)

if [ "$MODE" = "--adb" ]; then
  adb install -r "$B/tvhome.apk" || {
    echo "If this says INSTALL_FAILED_UPDATE_INCOMPATIBLE, an APK signed with another key is installed:" >&2
    echo "  adb uninstall com.mihir.tvhome   and run this again." >&2
    exit 1
  }
  for c in "${HOME_COMMANDS[@]}"; do
    # shellcheck disable=SC2086
    adb shell $c
  done
  echo "Installed. Press Home on the remote."
  exit 0
fi

PY=../tools/venv/bin/python
"$PY" -I ../tools/tv.py --push-b64 "$B/tvhome.apk" /data/local/tmp/tvhome.apk
{
  echo "pm install -r /data/local/tmp/tvhome.apk"
  echo "rm -f /data/local/tmp/tvhome.apk"
  printf '%s\n' "${HOME_COMMANDS[@]}"
} | "$PY" -I ../tools/tv.py --batch
echo "Installed. Press Home on the remote."
