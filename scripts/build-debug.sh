#!/usr/bin/env bash
# Lint + debug build: the project's single check gate.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

./gradlew lintDebug assembleDebug

APK=LaLauncher/build/outputs/apk/debug/LaLauncher-debug.apk
echo
echo "debug APK: $APK ($(du -h "$APK" | cut -f1))"
echo "install  : adb install -r $APK"
