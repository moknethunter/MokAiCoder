#!/usr/bin/env bash
set -euo pipefail

source_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
ndk="${MOKAI_ANDROID_NDK:-$sdk/ndk/28.2.13676358}"
destination="${1:?Pass the output folder}"
work="$(mktemp -d)"
trap 'rm -rf -- "$work"' EXIT

llama_revision=1a03cf47f67be591699d1f0f7ca28e1ed6eb8c7e
llama_source="${MOKAI_LLAMA_SOURCE:-$work/llama}"
if [[ -z "${MOKAI_LLAMA_SOURCE:-}" ]]; then
  git clone --quiet --depth 1 --branch b9000 https://github.com/ggml-org/llama.cpp.git "$llama_source"
fi
[[ "$(git -C "$llama_source" rev-parse HEAD)" == "$llama_revision" ]]

cmake -S "$source_dir/native" -B "$work/build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static \
  -DMOKAI_LLAMA_SOURCE="$llama_source"
cmake --build "$work/build" --target mokai_llm --parallel "${MOKAI_NATIVE_JOBS:-3}"
mkdir -p "$destination"
install -m 644 "$work/build/libmokai_llm.so" "$destination/libmokai_llm.so"
