#!/usr/bin/env bash
# Builds Stockfish for 64-bit ARM Android phones and puts the executable where
# the app packages it (app/src/main/jniLibs/arm64-v8a/libstockfish.so).
#
# Android only lets apps run executables that live in their native library
# directory, so the engine ships under a lib*.so name and is launched as a
# separate process that the app talks to over UCI.
#
# Usage: scripts/build-stockfish.sh   (needs git, curl and the Android NDK)
# Env:   SF_TAG (default sf_19), ANDROID_NDK_HOME or ANDROID_HOME, MIN_API (default 26)
set -euo pipefail

SF_TAG="${SF_TAG:-sf_19}"
MIN_API="${MIN_API:-26}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/stockfish-$SF_TAG"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a/libstockfish.so"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  ANDROID_NDK_HOME="$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)"
fi
case "$(uname -s)" in
  Darwin) HOST=darwin-x86_64 ;;
  *) HOST=linux-x86_64 ;;
esac
BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST/bin"
CXX="$BIN/aarch64-linux-android$MIN_API-clang++"
[ -x "$CXX" ] || { echo "NDK compiler not found at $CXX" >&2; exit 1; }

if [ ! -d "$WORK/src" ]; then
  rm -rf "$WORK"
  git clone --quiet --depth 1 --branch "$SF_TAG" https://github.com/official-stockfish/Stockfish "$WORK"
fi
cd "$WORK/src"

# The network is embedded into the binary, so fetch it (and check its hash,
# which is part of its file name) before compiling.
NET="$(sed -n 's/.*#define EvalFileDefaultName *"\(nn-[a-z0-9]*\.nnue\)".*/\1/p' evaluate.h)"
if [ ! -f "$NET" ] || [ "nn-$(sha256sum "$NET" | cut -c1-12).nnue" != "$NET" ]; then
  curl -fsSL --retry 3 --max-time 600 -o "$NET" "https://tests.stockfishchess.org/api/nn/$NET"
  [ "nn-$(sha256sum "$NET" | cut -c1-12).nnue" = "$NET" ] || { echo "bad network hash" >&2; exit 1; }
fi

# Same source list and flags as `make build ARCH=armv8 COMP=ndk`.
SRCS="$(sed -n '/^SRCS = /,/^$/p' Makefile | sed 's/^SRCS = //; s/\\//g')"
FLAGS=(-std=c++17 -stdlib=libc++ -fno-exceptions -Wall -O3 -funroll-loops -flto=full
  -DNDEBUG -DIS_64BIT -DUSE_POPCNT -DUSE_NEON=8 -DARCH=armv8 -fPIE)

mkdir -p obj
OBJS=()
for f in $SRCS; do
  o="obj/$(echo "$f" | tr '/' '_').o"
  OBJS+=("$o")
  "$CXX" "${FLAGS[@]}" -c "$f" -o "$o" &
  while [ "$(jobs -rp | wc -l)" -ge "$(nproc)" ]; do wait -n; done
done
wait

mkdir -p "$(dirname "$OUT")"
"$CXX" "${FLAGS[@]}" -pie -fuse-ld=lld -static-libstdc++ "${OBJS[@]}" -o "$OUT"
"$BIN/llvm-strip" "$OUT"
echo "Built $OUT ($(du -h "$OUT" | cut -f1))"
