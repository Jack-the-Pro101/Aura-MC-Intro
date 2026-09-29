#!/usr/bin/env bash
# Builds the minimal FFmpeg shared libraries that the mod bundles, for one platform.
#
# Usage: build-minimal.sh <platform>
#   platform: linux-x86_64 | linux-arm64 | windows-x86_64 | macosx-arm64 | macosx-x86_64
#
# Produces out/ffmpeg-<version>-min-<platform>.zip containing the five libraries with the exact
# file names the JavaCPP preset expects (identical to the bytedeco builds' names, since the same
# FFmpeg release is built - only with everything the player never touches disabled: Matroska/WebM
# demuxing, VP9 + Opus decoding, VP9-only hardware acceleration and the swscale/swresample
# converters). The zip is
# consumed by the Gradle build via the ffmpeg_libraries property; the GitHub Actions workflow
# (.github/workflows/ffmpeg.yml) runs this script for all five platforms.
#
# Extra configure arguments can be appended through the EXTRA_CONFIGURE environment variable
# (for example EXTRA_CONFIGURE="--disable-x86asm" for a quick functional build without nasm).
set -euo pipefail

FFMPEG_VERSION="${FFMPEG_VERSION:-8.1.2}"
PLATFORM="${1:?usage: build-minimal.sh <platform>}"
OUT_DIR="${OUT_DIR:-out}"

# Libraries that must exist under their bytedeco-compatible names once the build is done.
LINUX_LIBS=(libavcodec.so.62 libavformat.so.62 libavutil.so.60 libswresample.so.6 libswscale.so.9)
MACOS_LIBS=(libavcodec.62.dylib libavformat.62.dylib libavutil.60.dylib libswresample.6.dylib libswscale.9.dylib)
WINDOWS_LIBS=(avcodec-62.dll avformat-62.dll avutil-60.dll swresample-6.dll swscale-9.dll)

case "$PLATFORM" in
  linux-x86_64)
    CROSS=()
    LIBS=("${LINUX_LIBS[@]}")
    # VAAPI (Intel/AMD) + NVDEC (NVIDIA via nv-codec-headers, dlopen'd at runtime).
    # Needs libva-dev and the nv-codec-headers package installed; set FFMPEG_NO_HWACCEL=1
    # for a software-only build without those dependencies.
    if [ "${FFMPEG_NO_HWACCEL:-0}" != 1 ]; then
      # --enable-ffnvcodec is required even though it looks redundant:
      # configure's --disable-autodetect turns the ffnvcodec probe off, which
      # leaves --enable-nvdec/--enable-cuda unsatisfiable ("cuda requested,
      # but not all dependencies are satisfied: ffnvcodec").
      # Only the VP9 hwaccels are enabled; the blanket --enable-hwaccels would turn on every
      # hwaccel, and each of those drags its parent decoder (av1, h264, hevc, vc1, ...) into
      # libavcodec with it.
      HWACCEL=(--enable-vaapi --enable-ffnvcodec --enable-nvdec --enable-cuda
        --enable-hwaccel=vp9_vaapi --enable-hwaccel=vp9_nvdec)
    else
      HWACCEL=()
    fi
    ;;
  linux-arm64)
    CROSS=(--enable-cross-compile --cross-prefix=aarch64-linux-gnu- --arch=aarch64 --target-os=linux)
    LIBS=("${LINUX_LIBS[@]}")
    HWACCEL=()
    ;;
  windows-x86_64)
    # w32threads (the mingw default) keep the DLLs free of a libwinpthread dependency;
    # -static-libgcc keeps them free of a libgcc one. D3D11VA/DXVA2 use system headers.
    CROSS=(--enable-cross-compile --cross-prefix=x86_64-w64-mingw32- --arch=x86_64 --target-os=mingw32 --extra-ldflags=-static-libgcc)
    LIBS=("${WINDOWS_LIBS[@]}")
    HWACCEL=(--enable-d3d11va --enable-dxva2
      --enable-hwaccel=vp9_d3d11va --enable-hwaccel=vp9_d3d11va2 --enable-hwaccel=vp9_dxva2)
    ;;
  macosx-arm64|macosx-x86_64)
    CROSS=()
    LIBS=("${MACOS_LIBS[@]}")
    HWACCEL=(--enable-videotoolbox --enable-hwaccel=vp9_videotoolbox)
    ;;
  *)
    echo "unknown platform: $PLATFORM" >&2
    exit 1
    ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$REPO_ROOT/$OUT_DIR" "$REPO_ROOT/sources"
cd "$REPO_ROOT/sources"

if [ ! -d "ffmpeg-$FFMPEG_VERSION" ]; then
  # Reuse an already downloaded tarball (the workflow caches exactly that file) before
  # hitting the network.
  if [ ! -f ffmpeg.tar.xz ]; then
    echo "Downloading FFmpeg $FFMPEG_VERSION sources"
    curl -fsSL -o ffmpeg.tar.xz "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
  fi
  tar -xf ffmpeg.tar.xz
fi
cd "ffmpeg-$FFMPEG_VERSION"

# The tree may already contain build output for a *different* platform (local builds for several
# platforms share sources/, and an earlier version of the workflow even cached the built tree
# across the whole matrix). Leftover objects would be linked into this platform's libraries and
# fail with "error adding symbols: file in wrong format", so always start from a pristine tree.
make distclean >/dev/null 2>&1 || true

echo "Configuring FFmpeg $FFMPEG_VERSION for $PLATFORM"
# ${ARR[@]+"${ARR[@]}"} instead of "${ARR[@]}": macOS still ships bash 3.2, where expanding an
# empty array under `set -u` aborts with "CROSS[@]: unbound variable" (HWACCEL and CROSS are
# empty on some platforms).
./configure \
  --prefix="$REPO_ROOT/$OUT_DIR/$PLATFORM" \
  --enable-shared --disable-static \
  --disable-programs --disable-doc --disable-debug --disable-autodetect \
  --disable-everything \
  --enable-demuxer=matroska \
  --enable-decoder=vp9 --enable-decoder=opus \
  --enable-parser=vp9 --enable-parser=opus \
  --enable-protocol=file \
  --disable-network --disable-hwaccels \
  --extra-cflags=-Os \
  ${EXTRA_CONFIGURE:-} \
  ${HWACCEL[@]+"${HWACCEL[@]}"} \
  ${CROSS[@]+"${CROSS[@]}"}

make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
rm -rf "$REPO_ROOT/$OUT_DIR/$PLATFORM"
make install

# Stage the five libraries under their bytedeco-compatible names.
PREFIX="$REPO_ROOT/$OUT_DIR/$PLATFORM"
STAGE="$REPO_ROOT/$OUT_DIR/stage-$PLATFORM"
rm -rf "$STAGE"
mkdir -p "$STAGE"
if [ "$PLATFORM" = windows-x86_64 ]; then
  LIB_SOURCE_DIR="$PREFIX/bin"
else
  LIB_SOURCE_DIR="$PREFIX/lib"
fi
for lib in "${LIBS[@]}"; do
  cp -L "$LIB_SOURCE_DIR/$lib" "$STAGE/$lib"
done

# macOS: plain install names (matching bytedeco's dylibs, which JavaCPP resolves through the
# already-loaded image) and re-signing, which is mandatory after edits on Apple Silicon.
if [[ "$PLATFORM" == macosx-* ]]; then
  for lib in "${LIBS[@]}"; do
    install_name_tool -id "$lib" "$STAGE/$lib"
    # libavutil itself has no libav/sw dependency, so the grep can legitimately come up empty;
    # with `set -o pipefail` that would otherwise abort the script (grep exits 1 on no match).
    otool -L "$STAGE/$lib" | awk '{print $1}' | grep -E 'libav(codec|format|util)|libsw(resample|scale)' | while read -r dep; do
      install_name_tool -change "$dep" "$(basename "$dep")" "$STAGE/$lib"
    done || true
    codesign --force --sign - "$STAGE/$lib"
  done
fi

ZIP_PATH="$REPO_ROOT/$OUT_DIR/ffmpeg-$FFMPEG_VERSION-min-$PLATFORM.zip"
rm -f "$ZIP_PATH"
(cd "$STAGE" && zip -q -r "$ZIP_PATH" .)
echo "Built $ZIP_PATH:"
unzip -l "$ZIP_PATH"
