#!/usr/bin/env bash
# Builds a static libcrypto (OpenSSL) for the Android ABIs the app ships.
# Output: openssl-android/<abi>/{include,lib/libcrypto.a}
set -euo pipefail

VER="${OPENSSL_VERSION:-3.0.15}"
API=24
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/openssl-android"
NDK="${ANDROID_NDK_ROOT:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_HOME:-}}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "Android NDK not found (set ANDROID_NDK_ROOT)"; exit 1
fi
echo "Using NDK: $NDK"

WORK="$(mktemp -d)"
curl -fsSL -o "$WORK/openssl.tar.gz" \
  "https://github.com/openssl/openssl/releases/download/openssl-$VER/openssl-$VER.tar.gz"

export ANDROID_NDK_ROOT="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"

for ABI in armeabi-v7a arm64-v8a; do
  case "$ABI" in
    armeabi-v7a) TARGET=android-arm ;;
    arm64-v8a)   TARGET=android-arm64 ;;
  esac
  SRC="$WORK/src-$ABI"
  mkdir -p "$SRC"
  tar xzf "$WORK/openssl.tar.gz" -C "$SRC" --strip-components=1
  (
    cd "$SRC"
    ./Configure "$TARGET" -D__ANDROID_API__=$API -fPIC \
      no-shared no-tests no-apps no-docs no-ui-console no-engine no-dso no-legacy \
      --prefix="$OUT/$ABI" --libdir=lib
    make -j"$(nproc)" build_libs
    make install_dev
  )
  ls -la "$OUT/$ABI/lib/libcrypto.a"
done
rm -rf "$WORK"
