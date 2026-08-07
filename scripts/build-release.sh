#!/usr/bin/env bash
# Lint + signed release build. Signing comes from an untracked
# keystore.properties in the repo root; output lands in dist/ (gitignored).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

[ -f keystore.properties ] || {
  echo "keystore.properties missing - release builds must be signed." >&2
  echo "Create it with: storeFile= / storePassword= / keyAlias= / keyPassword=" >&2
  exit 1
}

./gradlew lintDebug assembleRelease

APK=app/build/outputs/apk/release/zx1-retro-interface-release.apk
[ -f "$APK" ] || { echo "expected $APK was not produced" >&2; exit 1; }

SDK=$(sed -n 's/^sdk.dir=//p' local.properties)
APKSIGNER="$SDK/build-tools/30.0.3/apksigner"

echo
echo "== signature =="
"$APKSIGNER" verify --print-certs "$APK" | sed -n '1,4p'

VER=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)
mkdir -p dist
OUT="dist/zx1-retro-interface-$VER.apk"
cp "$APK" "$OUT"
echo
echo "release APK: $OUT ($(du -h "$OUT" | cut -f1))"
shasum -a 256 "$OUT" 2>/dev/null || sha256sum "$OUT"
