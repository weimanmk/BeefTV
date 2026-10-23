#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
NDK="$SDK/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin"
cd "$ROOT/web"
bun install --frozen-lockfile
BEEFTV_FULL_MEDIA_RESOURCES=0 BEEFTV_DIST_PLATFORM=android bun run build:slim
cd "$ROOT"
cp -R web/dist/. backend/cmd/mobile/webui/
cd backend
go test ./cmd/mobile
for target in arm64-v8a:arm64:aarch64 x86_64:amd64:x86_64; do
    IFS=: read -r ABI ARCH TRIPLE <<< "$target"
    OUTPUT="$ROOT/android/app/src/main/jniLibs/$ABI"
    mkdir -p "$OUTPUT"
    GOOS=android GOARCH="$ARCH" CGO_ENABLED=1 CC="$NDK/${TRIPLE}-linux-android26-clang" \
        CGO_LDFLAGS='-Wl,-z,max-page-size=16384' \
        go build -buildmode=c-shared -trimpath -ldflags='-s -w' -o "$OUTPUT/libbeeftv.so" ./cmd/mobile
done
cd "$ROOT/android"
gradle --no-daemon assembleDebug
