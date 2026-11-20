#!/usr/bin/env bash
# Publishes a locally signed release: scripts/release.sh 1.0.0
# Expects app/build/outputs/apk/release/app-release.apk built from a clean tree at HEAD (see AGENTS.md).
set -euo pipefail
V="${1:?usage: $0 X.Y.Z}"
TAG="v$V"
APK=app/build/outputs/apk/release/app-release.apk
grep -q "versionName = \"$V\"" app/build.gradle.kts || { echo "versionName in app/build.gradle.kts is not $V" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "working tree not clean" >&2; exit 1; }
[ "$APK" -nt app/build.gradle.kts ] || { echo "$APK is older than the build file: rebuild" >&2; exit 1; }
"${ANDROID_HOME:-/opt/android-sdk}"/build-tools/*/apksigner verify "$APK" >/dev/null 2>&1 || { echo "$APK is not signed" >&2; exit 1; }
CODE=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
OUT="$(mktemp -d)"
cp "$APK" "$OUT/frigate-mobile-$V.apk"
(cd "$OUT" && sha256sum "frigate-mobile-$V.apk" > "frigate-mobile-$V.apk.sha256")
git tag -a "$TAG" -m "Frigate Mobile $V"
git push origin HEAD "$TAG"
gh release create "$TAG" "$OUT/frigate-mobile-$V.apk" "$OUT/frigate-mobile-$V.apk.sha256" \
  --title "Frigate Mobile $V" --notes-file "fastlane/metadata/android/en-US/changelogs/$CODE.txt"
rm -rf "$OUT"
