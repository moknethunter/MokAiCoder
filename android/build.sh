#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
: "${sdk:?Set ANDROID_HOME or ANDROID_SDK_ROOT}"
tools_dir="${MOKAI_ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}"
platform="$sdk/platforms/android-35/android.jar"
: "${MOKAI_ANDROID_KEYSTORE:?Set MOKAI_ANDROID_KEYSTORE to your signing keystore}"
: "${MOKAI_ANDROID_KS_PASS:?Set MOKAI_ANDROID_KS_PASS}"
export MOKAI_ANDROID_KS_PASS
destination="${1:-$source_dir/build/MokAiCoder-Android.apk}"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT
mkdir -p "$work/classes" "$work/dex" "$(dirname "$destination")"

"$tools_dir/aapt2" compile --dir "$source_dir/res" -o "$work/resources.zip"
"$tools_dir/aapt2" link -I "$platform" --manifest "$source_dir/AndroidManifest.xml" \
  -o "$work/app.apk" "$work/resources.zip"

compiler=(javac)
if [[ -n "${MOKAI_ANDROID_ECJ_JAR:-}" ]]; then
  compiler=(java -jar "$MOKAI_ANDROID_ECJ_JAR")
fi
mapfile -d '' -t sources < <(find "$source_dir/src" -type f -name '*.java' -print0)
"${compiler[@]}" -source 8 -target 8 -proc:none -encoding UTF-8 \
  -bootclasspath "$platform:$tools_dir/core-lambda-stubs.jar" -d "$work/classes" "${sources[@]}"
(cd "$work/classes" && zip -q -r "$work/classes.jar" .)
"$tools_dir/d8" --release --min-api 26 --lib "$platform" --output "$work/dex" "$work/classes.jar"
zip -q -j "$work/app.apk" "$work/dex"/classes*.dex
"$tools_dir/zipalign" -f 4 "$work/app.apk" "$work/aligned.apk"
"$tools_dir/apksigner" sign --ks "$MOKAI_ANDROID_KEYSTORE" \
  --ks-key-alias "${MOKAI_ANDROID_KEY_ALIAS:-mokai}" --ks-pass env:MOKAI_ANDROID_KS_PASS \
  --key-pass env:MOKAI_ANDROID_KS_PASS --v4-signing-enabled false \
  --out "$work/signed.apk" "$work/aligned.apk"
"$tools_dir/apksigner" verify --min-sdk-version 26 "$work/signed.apk"
install -m 644 "$work/signed.apk" "$destination"
printf '%s\n' "$destination"
