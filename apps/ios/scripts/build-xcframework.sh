#!/usr/bin/env bash
# crypto_core를 Apple 타깃으로 크로스컴파일해 XCFramework를 만든다. (macOS 전용)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"   # 저장소 루트
CORE="$ROOT/crypto_core"
cd "$CORE"

echo "▶ Rust 타깃 빌드"
for T in aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios; do
  cargo build --release --lib --target "$T"
done

echo "▶ 시뮬레이터 유니버설(arm64 + x86_64) 결합"
mkdir -p target/universal-sim
lipo -create \
  "target/aarch64-apple-ios-sim/release/libcrypto_core.a" \
  "target/x86_64-apple-ios/release/libcrypto_core.a" \
  -output "target/universal-sim/libcrypto_core.a"

echo "▶ 헤더/모듈맵 준비"
HEAD="$CORE/bindings/swift"
STAGE="$CORE/build/headers"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp "$HEAD/crypto_coreFFI.h" "$STAGE/"
cp "$HEAD/crypto_coreFFI.modulemap" "$STAGE/module.modulemap"

echo "▶ XCFramework 생성"
OUT="$CORE/ios/CryptoCore.xcframework"
rm -rf "$OUT"; mkdir -p "$CORE/ios"
xcodebuild -create-xcframework \
  -library "target/aarch64-apple-ios/release/libcrypto_core.a" -headers "$STAGE" \
  -library "target/universal-sim/libcrypto_core.a" -headers "$STAGE" \
  -output "$OUT"

echo "✔ 완료 → $OUT"
