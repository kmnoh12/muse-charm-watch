#!/bin/bash
# Build local/MuseBridge.app, the Mac-side BLE peripheral used by tools/mac_pairing.py.
set -euo pipefail
cd "$(dirname "$0")/../.."
APP=local/MuseBridge.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp tools/mac-pairing/Info.plist "$APP/Contents/Info.plist"
swiftc -O tools/mac-pairing/MacBleBridge.swift -o "$APP/Contents/MacOS/muse-ble-bridge"
codesign --force --deep -s - "$APP"
echo "built $APP"
