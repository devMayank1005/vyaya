#!/usr/bin/env bash
# Launch report FLAW-002: builds the signed play bundle for upload and keeps what is needed to read its crash reports.
#   - Needs the upload key in ~/.gradle/gradle.properties (vyaya.signing.*; docs/launch-checklist.md).
#   - Refuses a versionCode already released (Play needs a new one for every upload).
#   - Runs the release checks on the same build.
#   - Files the .aab and its mapping.txt in ${VYAYA_RELEASES:-~/vyaya-releases}/<versionCode>/, outside the repository.
# Usage: scripts/release.sh [extra Gradle arguments]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="${VYAYA_RELEASES:-$HOME/vyaya-releases}"
CODE="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' androidApp/build.gradle.kts | head -1)"
NAME="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' androidApp/build.gradle.kts | head -1)"
[ -n "$CODE" ] || { echo "versionCode not found in androidApp/build.gradle.kts"; exit 1; }
if [ -e "$OUT/$CODE" ]; then
  echo "versionCode $CODE was already released ($OUT/$CODE): bump versionCode in androidApp/build.gradle.kts"; exit 1
fi

./gradlew :androidApp:bundlePlayRelease :androidApp:assemblePlayRelease --console=plain -q "$@"
AAB="androidApp/build/outputs/bundle/playRelease/androidApp-play-release.aab"
APK="androidApp/build/outputs/apk/play/release/androidApp-play-release.apk"
MAP="androidApp/build/outputs/mapping/playRelease/mapping.txt"
if [ ! -f "$APK" ] || ! jarsigner -verify "$AAB" >/dev/null 2>&1; then
  echo "The build isn't signed: add the upload key to ~/.gradle/gradle.properties (docs/launch-checklist.md)"; exit 1
fi
python3 scripts/check-release.py "$APK" "$MAP"

mkdir -p "$OUT/$CODE"
cp "$AAB" "$OUT/$CODE/vyaya-$NAME-$CODE.aab"
cp "$MAP" "$OUT/$CODE/mapping.txt"
git rev-parse HEAD > "$OUT/$CODE/commit.txt"
echo "Release $NAME ($CODE) is in $OUT/$CODE: upload the .aab, and its mapping.txt in Play Console (App bundle explorer)."
