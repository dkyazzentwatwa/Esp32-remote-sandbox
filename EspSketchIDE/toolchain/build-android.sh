#!/bin/bash
# Builds Espressif's xtensa-esp-elf host tools (gcc 14.2.0, binutils 2.43.1) to run on Android.
#
# Only the programs a sketch build runs are produced (gcc/g++ drivers, cc1, cc1plus, collect2,
# as, ld, ar, objcopy, size) plus the xtensa dynconfig plugin. Target libraries (libgcc,
# libstdc++, newlib, linker scripts) are reused from Espressif's own release, which was built
# from the same sources and configure options.
#
# Usage: toolchain/build-android.sh <arm64-v8a|armeabi-v7a> [stage...]
#   stages: sources deps plugin binutils gcc package (default: all, in that order)
# Env:    ANDROID_NDK_HOME (required), WORK (default: ./toolchain-work), JOBS (default: nproc),
#         BUILD_XTENSA_BIN: bin/ of Espressif's desktop xtensa-esp-elf toolchain of the same
#         version (required for the gcc stage: a canadian cross runs it to generate specs).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=versions.sh
source "$HERE/versions.sh"

ABI="${1:?usage: $0 <arm64-v8a|armeabi-v7a> [stage...]}"
shift
STAGES=("$@")
[ ${#STAGES[@]} -eq 0 ] && STAGES=(sources deps plugin binutils gcc package)

case "$ABI" in
  arm64-v8a)   TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) TRIPLE=armv7a-linux-androideabi ;;
  *) echo "unknown ABI $ABI" >&2; exit 2 ;;
esac

NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME}"
LLVM="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
WORK="${WORK:-$PWD/toolchain-work}"
JOBS="${JOBS:-$(nproc)}"
SRC="$WORK/src"
BUILD="$WORK/build-$ABI"
DEPS="$WORK/deps-$ABI"
STAGE="$WORK/stage-$ABI"     # install root, laid out like Espressif's toolchain
OUT="$WORK/out"

export CC="$LLVM/${TRIPLE}${ANDROID_API}-clang"
export CXX="$LLVM/${TRIPLE}${ANDROID_API}-clang++"
export AR="$LLVM/llvm-ar" RANLIB="$LLVM/llvm-ranlib" STRIP="$LLVM/llvm-strip"
export NM="$LLVM/llvm-nm" OBJCOPY="$LLVM/llvm-objcopy" OBJDUMP="$LLVM/llvm-objdump"
# Build-machine compilers for gcc's generator programs.
export CC_FOR_BUILD=gcc CXX_FOR_BUILD=g++
export CFLAGS="-O2" CXXFLAGS="-O2"
# Link libc++ into each program so they only need Android's own libc/libm/libdl.
export LDFLAGS="-static-libstdc++"

log() { echo "==> [$ABI] $*"; }

git_at() { # repo ref dir: shallow checkout of a tag or commit from github.com/espressif
  local repo=$1 ref=$2 dir=$3
  if [ -d "$dir/.git" ]; then return 0; fi
  mkdir -p "$dir"
  git -C "$dir" init -q
  git -C "$dir" remote add origin "https://github.com/espressif/$repo"
  git -C "$dir" fetch -q --depth 1 origin "$ref"
  git -C "$dir" -c advice.detachedHead=false checkout -q FETCH_HEAD
}

tarball() { # url sha256 dir
  local url=$1 sum=$2 dir=$3 file
  file="$SRC/$(basename "$url")"
  [ -d "$dir" ] && return 0
  [ -f "$file" ] || curl -fsSL -o "$file" "$url"
  echo "$sum  $file" | sha256sum -c --quiet
  tar -C "$SRC" -xf "$file"
}

stage_sources() {
  log "sources"
  mkdir -p "$SRC"
  git_at gcc "$ESP_GCC_REF" "$SRC/gcc"
  git_at binutils-gdb "$ESP_BINUTILS_REF" "$SRC/binutils-gdb"
  git_at xtensa-dynconfig "$ESP_DYNCONFIG_REF" "$SRC/xtensa-dynconfig"
  git_at xtensa-overlays "$ESP_OVERLAYS_REF" "$SRC/xtensa-overlays"
  tarball "$GMP_URL" "$GMP_SHA256" "$SRC/gmp-$GMP_VERSION"
  tarball "$MPFR_URL" "$MPFR_SHA256" "$SRC/mpfr-$MPFR_VERSION"
  tarball "$MPC_URL" "$MPC_SHA256" "$SRC/mpc-$MPC_VERSION"
}

stage_deps() {
  log "gmp/mpfr/mpc (static, for the Android host)"
  # --with-pic: they are linked into PIE programs. GMP's 32-bit ARM assembly isn't PIC, so it
  # is disabled there (lld: "R_ARM_ABS32 cannot be used against symbol").
  local common=(--host="$TRIPLE" --prefix="$DEPS" --disable-shared --enable-static --with-pic)
  for lib in "gmp-$GMP_VERSION" "mpfr-$MPFR_VERSION" "mpc-$MPC_VERSION"; do
    [ -f "$DEPS/.done-$lib" ] && continue
    rm -rf "$BUILD/$lib" && mkdir -p "$BUILD/$lib"
    local extra=()
    case "$lib" in
      gmp-*)  extra=(--enable-cxx=no); [ "$ABI" = armeabi-v7a ] && extra+=(--disable-assembly) ;;
      mpfr-*) extra=(--with-gmp="$DEPS") ;;
      mpc-*)  extra=(--with-gmp="$DEPS" --with-mpfr="$DEPS") ;;
    esac
    (cd "$BUILD/$lib" && "$SRC/$lib/configure" "${common[@]}" "${extra[@]}" >configure.log \
      && make -j"$JOBS" >make.log && make install >install.log)
    touch "$DEPS/.done-$lib"
  done
}

stage_plugin() {
  log "xtensa dynconfig plugins"
  mkdir -p "$STAGE/lib"
  for chip in $XTENSA_CHIPS; do
    make -s -C "$SRC/xtensa-dynconfig" CC="$CC" CONF_DIR="$SRC/xtensa-overlays" "xtensa_$chip.so"
    mv "$SRC/xtensa-dynconfig/xtensa_$chip.so" "$STAGE/lib/"
  done
}

stage_binutils() {
  log "binutils (as, ld, ar, objcopy, size)"
  # --enable-plugins is required: bfd loads the xtensa dynconfig only with plugin support.
  local dir="$BUILD/binutils"
  rm -rf "$dir" && mkdir -p "$dir"
  (cd "$dir" && "$SRC/binutils-gdb/configure" \
      --build=x86_64-linux-gnu --host="$TRIPLE" --target=xtensa-esp-elf \
      --prefix=/ --with-sysroot=/xtensa-esp-elf \
      --disable-gdb --disable-gdbserver --disable-sim --disable-gprofng \
      --disable-nls --disable-werror --enable-plugins --disable-shared \
      --without-debuginfod --without-zstd --with-system-zlib=no >configure.log \
    && make -j"$JOBS" all-binutils all-gas all-ld >make.log \
    && make DESTDIR="$STAGE" install-binutils install-gas install-ld >install.log)
}

stage_gcc() {
  log "gcc (drivers, cc1, cc1plus, collect2)"
  local xgcc="${BUILD_XTENSA_BIN:?set BUILD_XTENSA_BIN to the desktop xtensa-esp-elf toolchain bin/}"
  "$xgcc/xtensa-esp-elf-gcc" -dumpversion | grep -qx "$GCC_VERSION" \
    || { echo "BUILD_XTENSA_BIN gcc is not $GCC_VERSION" >&2; exit 1; }
  export PATH="$xgcc:$PATH"
  local dir="$BUILD/gcc"
  rm -rf "$dir" && mkdir -p "$dir"
  # Options mirror `xtensa-esp-elf-gcc -v` of Espressif's release, minus build-machine paths
  # and LTO (Arduino-ESP32 doesn't use it). --enable-plugin is required: gcc loads the xtensa
  # dynconfig through its plugin machinery and refuses XTENSA_GNU_CONFIG without it.
  # --enable-host-pie: gcc 14 otherwise builds
  # its programs with -fno-PIE, which Android won't run and lld can't link against bionic.
  (cd "$dir" && "$SRC/gcc/configure" \
      --build=x86_64-linux-gnu --host="$TRIPLE" --target=xtensa-esp-elf \
      --prefix=/ --with-sysroot=/xtensa-esp-elf --with-native-system-header-dir=/include \
      --with-gmp="$DEPS" --with-mpfr="$DEPS" --with-mpc="$DEPS" --without-isl --without-zstd \
      --with-newlib --enable-threads=no --disable-shared --disable-__cxa_atexit \
      --enable-cxx-flags='-ffunction-sections -fdata-sections' \
      --disable-libgomp --disable-libmudflap --disable-libmpx --disable-libssp \
      --disable-libquadmath --disable-libquadmath-support --disable-libstdcxx-verbose \
      --enable-target-optspace --without-long-double-128 --disable-nls --enable-multiarch \
      --enable-languages=c,c++ --enable-threads=posix --enable-libstdcxx-time=yes \
      --disable-lto --enable-plugin --disable-werror \
      --enable-host-pie \
      --with-pkgversion="EspSketchIDE android $ESP_GCC_REF" >configure.log \
    && make -j"$JOBS" all-gcc >make.log \
    && make DESTDIR="$STAGE" install-gcc >install.log)
}

stage_package() {
  log "package"
  local pkg="$OUT/xtensa-esp-elf-host-$ESP_GCC_REF-$ABI"
  rm -rf "$pkg" && mkdir -p "$pkg"
  local keep=(
    bin/xtensa-esp-elf-gcc bin/xtensa-esp-elf-g++ bin/xtensa-esp-elf-as bin/xtensa-esp-elf-ld
    bin/xtensa-esp-elf-ar bin/xtensa-esp-elf-objcopy bin/xtensa-esp-elf-size
    "libexec/gcc/xtensa-esp-elf/$GCC_VERSION/cc1" "libexec/gcc/xtensa-esp-elf/$GCC_VERSION/cc1plus"
    "libexec/gcc/xtensa-esp-elf/$GCC_VERSION/collect2"
  )
  for f in "${keep[@]}"; do
    install -D "$STAGE/$f" "$pkg/$f"
    "$STRIP" "$pkg/$f"
  done
  for chip in $XTENSA_CHIPS; do
    install -D "$STAGE/lib/xtensa_$chip.so" "$pkg/lib/xtensa_$chip.so"
    "$STRIP" "$pkg/lib/xtensa_$chip.so"
  done
  cp "$HERE/versions.sh" "$pkg/VERSIONS"
  (cd "$OUT" && tar -cJf "$(basename "$pkg").tar.xz" "$(basename "$pkg")")
  log "wrote $pkg.tar.xz"
  du -sh "$pkg" "$pkg.tar.xz"
}

for s in "${STAGES[@]}"; do "stage_$s"; done
