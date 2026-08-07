#!/usr/bin/env bash
# Build, verify, and publish a GitHub release with the APK attached.
# Requires: a clean tree, HEAD pushed, and the active gh account owning the
# repo (publishing releases is a maintainer action).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

OWNER=$(gh repo view --json owner --jq .owner.login 2>/dev/null || true)
[ -n "$OWNER" ] || { echo "cannot resolve the GitHub repo (is 'origin' set and gh authenticated?)" >&2; exit 1; }
LOGIN=$(gh api user --jq .login 2>/dev/null || true)
[ "$LOGIN" = "$OWNER" ] || {
  echo "BLOCKED: active gh account is '${LOGIN:-none}' but the repo is owned by '$OWNER'." >&2
  echo "Run: gh auth switch --user $OWNER" >&2
  exit 1
}

[ -z "$(git status --porcelain)" ] || { echo "BLOCKED: working tree is not clean." >&2; exit 1; }

git fetch -q origin
UPSTREAM=$(git rev-parse '@{u}' 2>/dev/null || true)
[ "$(git rev-parse HEAD)" = "$UPSTREAM" ] || {
  echo "BLOCKED: local HEAD is not pushed (or has no upstream). Push first." >&2
  exit 1
}

scripts/build-release.sh

VER=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)
TAG="v$VER"
APK="dist/zx1-retro-interface-$VER.apk"

echo
echo "publishing $TAG with $APK ..."
gh release create "$TAG" "$APK" --title "ZX1 Retro Interface $VER" --generate-notes
echo "published: $TAG"
