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
mkdir -p "$work/classes" "$work/dex" "$work/native" "$(dirname "$destination")"

vosk_version=0.3.75
jna_version=5.18.1
vosk_sha256=ab2f8b91ac8051561aa325546b35fed9a68b36b8121bac5c6fb927525c4adfad
jna_sha256=7f053e3ec99e14dd71259c82c1c8a02738d64a13c31226b2acc170f3060951e0
maven="https://repo.maven.apache.org/maven2"
vosk="${MOKAI_ANDROID_VOSK_AAR:-$work/vosk.aar}"
jna="${MOKAI_ANDROID_JNA_AAR:-$work/jna.aar}"
if [[ -z "${MOKAI_ANDROID_VOSK_AAR:-}" ]]; then
  curl -fsSL --retry 3 -o "$vosk" "$maven/com/alphacephei/vosk-android/$vosk_version/vosk-android-$vosk_version.aar"
fi
if [[ -z "${MOKAI_ANDROID_JNA_AAR:-}" ]]; then
  curl -fsSL --retry 3 -o "$jna" "$maven/net/java/dev/jna/jna/$jna_version/jna-$jna_version.aar"
fi

printf '%s  %s\n' "$vosk_sha256" "$vosk" "$jna_sha256" "$jna" | sha256sum -c
unzip -p "$vosk" classes.jar > "$work/vosk.jar"
unzip -p "$jna" classes.jar > "$work/jna.jar"
for abi in arm64-v8a armeabi-v7a x86_64 x86; do
  lib="$work/native/lib/$abi"
  mkdir -p "$lib"
  unzip -p "$vosk" "jni/$abi/libvosk.so" > "$lib/libvosk.so"
  unzip -p "$jna" "jni/$abi/libjnidispatch.so" > "$lib/libjnidispatch.so"
done

edition="${MOKAI_ANDROID_EDITION:-full}"
case "$edition" in
  tools) manifest="$source_dir/LocalToolsManifest.xml" ;;
  full) manifest="$source_dir/AndroidManifest.xml" ;;
  *) printf 'Unknown Android edition: %s\n' "$edition" >&2; exit 1 ;;
esac
package_options=()
if [[ -n "${MOKAI_ANDROID_APPLICATION_ID:-}" ]]; then
  package_options=(--rename-manifest-package "$MOKAI_ANDROID_APPLICATION_ID")
fi

"$tools_dir/aapt2" compile --dir "$source_dir/res" -o "$work/resources.zip"
"$tools_dir/aapt2" link -I "$platform" --manifest "$manifest" "${package_options[@]}" \
  -o "$work/app.apk" "$work/resources.zip"

compiler=(javac)
if [[ -n "${MOKAI_ANDROID_ECJ_JAR:-}" ]]; then
  compiler=(java -jar "$MOKAI_ANDROID_ECJ_JAR")
fi
if [[ "$edition" == tools ]]; then
  package="$source_dir/src/org/mokai/coder"
  sources=("$package/BotActivity.java" "$package/LocalExports.java" "$package/OfflineModel.java")
else
  mapfile -d '' -t sources < <(find "$source_dir/src" -type f -name '*.java' -print0)
fi
"${compiler[@]}" -source 8 -target 8 -proc:none -encoding UTF-8 \
  -bootclasspath "$platform:$tools_dir/core-lambda-stubs.jar" -classpath "$work/vosk.jar:$work/jna.jar" -d "$work/classes" "${sources[@]}"
(cd "$work/classes" && zip -q -r "$work/classes.jar" .)
"$tools_dir/d8" --release --min-api 26 --lib "$platform" --output "$work/dex" "$work/classes.jar" "$work/vosk.jar" "$work/jna.jar"
zip -q -j "$work/app.apk" "$work/dex"/classes*.dex
(cd "$work/native" && zip -q -0 -r "$work/app.apk" lib)
"$tools_dir/zipalign" -P 16 -f 4 "$work/app.apk" "$work/aligned.apk"
"$tools_dir/apksigner" sign --ks "$MOKAI_ANDROID_KEYSTORE" \
  --ks-key-alias "${MOKAI_ANDROID_KEY_ALIAS:-mokai}" --ks-pass env:MOKAI_ANDROID_KS_PASS \
  --key-pass env:MOKAI_ANDROID_KS_PASS --v4-signing-enabled false \
  --out "$work/signed.apk" "$work/aligned.apk"
"$tools_dir/apksigner" verify --min-sdk-version 26 "$work/signed.apk"
install -m 644 "$work/signed.apk" "$destination"
printf '%s\n' "$destination"
