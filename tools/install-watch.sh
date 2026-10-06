#!/bin/bash
# Build the watch app and install it on the connected watch (or the running emulator).
# Usage: tools/install-watch.sh            (uses whatever `adb devices` shows; pass ANDROID_SERIAL=... to pick one)
set -euo pipefail
cd "$(dirname "$0")/.."
TOOLS="$HOME/Documents/Default Project/안드로이드-에뮬레이터"
export JAVA_HOME="$TOOLS/jdk17/Contents/Home"
export ANDROID_SDK_ROOT="$TOOLS/android-sdk"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"

"$ADB" devices | sed -n '2,$p' | grep -w device >/dev/null || { echo "No device connected. See docs/CONNECT.md step 3."; exit 1; }
if ! grep -q '^muse.sdk.token=.\+' local.properties 2>/dev/null; then
  echo "Warning: no muse.sdk.token in local.properties, pairing will not work (see docs/CONNECT.md step 1)."
fi
./gradlew :watch:installDebug --console=plain
"$ADB" shell pm grant dev.kyungmin.musecharm android.permission.RECORD_AUDIO 2>/dev/null || true
"$ADB" shell pm grant dev.kyungmin.musecharm android.permission.BLUETOOTH_ADVERTISE 2>/dev/null || true
"$ADB" shell pm grant dev.kyungmin.musecharm android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
"$ADB" shell pm grant dev.kyungmin.musecharm android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
"$ADB" shell am start -n dev.kyungmin.musecharm/muse.charm.watch.MainActivity >/dev/null
echo "Installed and started. Watch the log with:  $ADB logcat -s MuseCharm MuseBle"
