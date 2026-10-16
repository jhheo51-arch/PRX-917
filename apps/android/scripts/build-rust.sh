#!/usr/bin/env bash
# crypto_core 를 Android용 .so 로 크로스컴파일해 app/src/main/jniLibs 에 넣는다.
# 요구: rustup android 타깃, cargo-ndk, ANDROID_NDK_HOME
set -euo pipefail
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME 를 NDK 경로로 지정하세요}"

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
OUT="$ROOT/apps/android/app/src/main/jniLibs"
mkdir -p "$OUT"
cd "$ROOT/crypto_core"
cargo ndk -t arm64-v8a -t x86_64 -o "$OUT" build --release
echo "✔ jniLibs → $OUT"
