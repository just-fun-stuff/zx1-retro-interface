#!/usr/bin/env bash
# Bump the app version. Usage: scripts/bump-version.sh <new-versionName>
# e.g. scripts/bump-version.sh 0.2   - versionCode auto-increments by 1.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

NEW="${1:?usage: scripts/bump-version.sh <versionName, e.g. 0.2>}"
GRADLE=app/build.gradle

CODE=$(sed -n 's/.*versionCode \([0-9]*\).*/\1/p' "$GRADLE")
OLD=$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" "$GRADLE")
[ -n "$CODE" ] && [ -n "$OLD" ] || { echo "could not parse $GRADLE" >&2; exit 1; }

NEXT=$((CODE + 1))
perl -pi -e "s/versionCode \Q$CODE\E/versionCode $NEXT/; s/versionName '\Q$OLD\E'/versionName '$NEW'/" "$GRADLE"
echo "version: $OLD (code $CODE) -> $NEW (code $NEXT)"
echo "commit with e.g.: git commit -am 'Version $NEW'"
