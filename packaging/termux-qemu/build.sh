TERMUX_PKG_HOMEPAGE=https://www.qemu.org
TERMUX_PKG_DESCRIPTION="QEMU AArch64 system emulator and disk image utility for DistroBoxer"
TERMUX_PKG_LICENSE="GPL-2.0"
TERMUX_PKG_MAINTAINER="Atdev Dev"
TERMUX_PKG_VERSION=11.1.3
TERMUX_PKG_SRCURL="https://download.qemu.org/qemu-${TERMUX_PKG_VERSION}.tar.xz"
TERMUX_PKG_SHA256=5a8289c7940dc77d3873c81a6c29ba3afe1446fc9d36e77fa1658cdbecb84b22
TERMUX_PKG_DEPENDS="glib, libpixman, libslirp, dtc, zlib, libbz2, libandroid-shmem"
TERMUX_PKG_BUILD_IN_SRC=true

termux_step_pre_configure() {
    # Android AArch64 setjmp compatibility workaround used by the upstream
    # Termux QEMU recipe. The workflow copies setjmp-aarch64 from that recipe.
    rm -rf "$TERMUX_PKG_BUILDDIR/_lib"
    mkdir -p "$TERMUX_PKG_BUILDDIR/_lib" "$TERMUX_PKG_BUILDDIR/_setjmp-aarch64"
    cd "$TERMUX_PKG_BUILDDIR/_setjmp-aarch64"
    local source_file
    for source_file in "$TERMUX_PKG_BUILDER_DIR"/setjmp-aarch64/{setjmp.S,private-*.h}; do
        local base_name
        base_name="$(basename "$source_file")"
        cp "$source_file" "./${base_name/-//}"
    done
    "$CC" $CFLAGS $CPPFLAGS -I. setjmp.S -c -o setjmp.o
    "$AR" rcs "$TERMUX_PKG_BUILDDIR/_lib/libandroid-setjmp.a" setjmp.o
    "$RANLIB" "$TERMUX_PKG_BUILDDIR/_lib/libandroid-setjmp.a"
    LDFLAGS+=" -L$TERMUX_PKG_BUILDDIR/_lib -l:libandroid-setjmp.a -landroid-shmem -llog"
}

termux_step_configure() {
    termux_setup_ninja
    CFLAGS+=" $CPPFLAGS"
    CXXFLAGS+=" $CPPFLAGS"

    ./configure \
        --prefix="$TERMUX_PREFIX" \
        --cross-prefix="${TERMUX_HOST_PLATFORM}-" \
        --host-cc="gcc" \
        --cc="$CC" \
        --cxx="$CXX" \
        --objcc="$CC" \
        --disable-stack-protector \
        --disable-werror \
        --disable-docs \
        --disable-guest-agent \
        --disable-gtk \
        --disable-sdl \
        --disable-sdl-image \
        --disable-opengl \
        --disable-curses \
        --enable-vnc \
        --disable-vnc-sasl \
        --disable-vnc-jpeg \
        --disable-vnc-png \
        --disable-spice \
        --disable-libusb \
        --disable-usb-redir \
        --disable-virtfs \
        --disable-xen \
        --disable-xen-pci-passthrough \
        --disable-kvm \
        --disable-hvf \
        --disable-whpx \
        --disable-seccomp \
        --disable-libnfs \
        --disable-libssh \
        --disable-gnutls \
        --disable-nettle \
        --disable-lzo \
        --disable-snappy \
        --enable-bzip2 \
        --disable-lzfse \
        --enable-fdt=system \
        --enable-slirp \
        --enable-coroutine-pool \
        --enable-trace-backends=nop \
        --disable-vhost-user \
        --disable-vhost-user-blk-server \
        --target-list="aarch64-softmmu"
}
