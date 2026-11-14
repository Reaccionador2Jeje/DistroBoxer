#!/usr/bin/env bash
set -euo pipefail

TERMUX_DIR="${1:?usage: build-qemu-runtime.sh TERMUX_PACKAGES_DIR OUTPUT_DIR}"
OUTPUT_DIR="${2:?usage: build-qemu-runtime.sh TERMUX_PACKAGES_DIR OUTPUT_DIR}"
cd "$TERMUX_DIR"
# shellcheck disable=SC1091
source scripts/properties.sh

PREFIX="$TERMUX__PREFIX"
ABI_DIR="$OUTPUT_DIR/qemu/arm64-v8a"
mkdir -p "$ABI_DIR"

QEMU_DEB="$(find debs -type f -name 'qemu-system-aarch64_*.deb' -print -quit)"
test -n "$QEMU_DEB"
PACKAGE_ROOT="$(mktemp -d)"
trap 'rm -rf "$PACKAGE_ROOT"' EXIT
dpkg-deb -x "$QEMU_DEB" "$PACKAGE_ROOT"

PREFIX_REL="${PREFIX#/}"
PACKAGE_PREFIX="$PACKAGE_ROOT/$PREFIX_REL"
SYSTEM_EMULATOR="$PACKAGE_PREFIX/bin/qemu-system-aarch64"
IMAGE_TOOL="$PACKAGE_PREFIX/bin/qemu-img"
test -s "$SYSTEM_EMULATOR"
test -s "$IMAGE_TOOL"

install -m 755 "$SYSTEM_EMULATOR" "$ABI_DIR/qemu-system-aarch64"
install -m 755 "$IMAGE_TOOL" "$ABI_DIR/qemu-img"

# Bundle only non-platform shared libraries required by QEMU and qemu-img.
# Android's public bionic libraries remain provided by the operating system.
declare -A SEEN=()
QUEUE=("$ABI_DIR/qemu-system-aarch64" "$ABI_DIR/qemu-img")
SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libandroid.so libz.so libdl_android.so libEGL.so libGLESv2.so libvulkan.so libOpenSLES.so libjnigraphics.so libmediandk.so libnativewindow.so libsync.so libcamera2ndk.so libaaudio.so libbinder_ndk.so libneuralnetworks.so "

while (("${#QUEUE[@]}")); do
    CURRENT="${QUEUE[0]}"
    QUEUE=("${QUEUE[@]:1}")
    while IFS= read -r NEEDED; do
        [[ -n "$NEEDED" ]] || continue
        [[ -n "${SEEN[$NEEDED]:-}" ]] && continue
        SEEN["$NEEDED"]=1

        if [[ "$SYSTEM_LIBS" == *" $NEEDED "* ]]; then
            continue
        fi

        SOURCE_LIB="$(find "$PREFIX/lib" -maxdepth 1 \( -type f -o -type l \) -name "$NEEDED" -print -quit)"
        if [[ -z "$SOURCE_LIB" ]]; then
            NDK_SEARCH_ROOT="${ANDROID_NDK_HOME:-$HOME/.termux-build}"
            SOURCE_LIB="$(find "$NDK_SEARCH_ROOT" -path "*/sysroot/usr/lib/aarch64-linux-android/$NEEDED" -print -quit 2>/dev/null || true)"
        fi
        if [[ -z "$SOURCE_LIB" ]]; then
            echo "Unresolved Android QEMU dependency: $NEEDED" >&2
            exit 1
        fi

        DESTINATION="$ABI_DIR/$NEEDED"
        if [[ ! -e "$DESTINATION" ]]; then
            install -m 644 "$SOURCE_LIB" "$DESTINATION"
            QUEUE+=("$DESTINATION")
        fi
    done < <(readelf -d "$CURRENT" | sed -nE 's/.*Shared library: \[(.*)\].*/\1/p')
done

# Ensure all packaged ELF files are Android AArch64 and use only the platform
# dynamic linker. Remove build-prefix RPATHs: runtime libraries are colocated
# and resolved from LD_LIBRARY_PATH set by QemuRuntime.
for ELF in "$ABI_DIR"/*; do
    [[ -f "$ELF" ]] || continue
    # BUILD-INFO and SHA256SUMS are text assets, not ELF payloads.
    readelf -h "$ELF" >/dev/null 2>&1 || continue
    readelf -h "$ELF" | grep -q 'AArch64'
    if readelf -l "$ELF" | grep -q 'Requesting program interpreter'; then
        readelf -l "$ELF" | grep -q '/system/bin/linker64'
    fi
    patchelf --remove-rpath "$ELF" || true
    if readelf -d "$ELF" | grep -E 'RPATH|RUNPATH'; then
        echo "Unexpected build-time RPATH in $ELF" >&2
        exit 1
    fi
done

# Preserve the upstream QEMU license and available dependency notices.
mkdir -p "$OUTPUT_DIR/qemu/licenses"
install -m 644 "$TERMUX_DIR/x11-packages/qemu-system-aarch64/qemu-COPYING" "$OUTPUT_DIR/qemu/licenses/QEMU-COPYING" 2>/dev/null || \
    install -m 644 "$TERMUX_DIR/qemu-source/COPYING" "$OUTPUT_DIR/qemu/licenses/QEMU-COPYING"
find "$PREFIX/share/doc" -maxdepth 3 -type f \( -name copyright -o -iname 'license*' -o -iname 'copying*' \) -print 2>/dev/null | while read -r LICENSE; do
    cp -n "$LICENSE" "$OUTPUT_DIR/qemu/licenses/$(basename "$(dirname "$LICENSE")")-$(basename "$LICENSE")" || true
done

cat > "$ABI_DIR/BUILD-INFO.txt" <<EOF
DistroBoxer QEMU Android runtime
QEMU version: 11.1.3
Android ABI: arm64-v8a
Guest target: aarch64-softmmu
Accelerator: TCG
Networking: libslirp user networking
Display: headless serial console; VNC server enabled
Build source: termux/termux-packages
Termux build-system revision: 8eb3169dbd10d71ef26e2847191f9ae2a452ab2e
Application package namespace: com.atdevdev.distroboxer
EOF

(
    cd "$ABI_DIR"
    sha256sum qemu-system-aarch64 qemu-img *.so* > SHA256SUMS
)
