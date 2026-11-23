# Android PRoot runtime

DistroBoxer packages the Termux-maintained PRoot executable for Android and launches it as a child process from the app sandbox.

## Build provenance

- Upstream: https://github.com/termux/proot
- Pinned source commit: `52d65a4dc140ca554d241a1438ddd4f6b00c9813`
- Dependency: talloc `2.4.4`, statically linked and checked against SHA-256 in `.github/workflows/proot-runtime.yml`
- License: GPL-2.0-or-later as provided by the upstream PRoot source tree; the upstream COPYING file is included in the APK assets as `runtime/COPYING-PROOT`
- Android ABIs: `arm64-v8a` and `x86_64`

The reusable workflow builds one ABI-specific PRoot executable per matrix job. Android CI downloads those artifacts, verifies that each binary is present, places it in the matching `jniLibs/<abi>/libproot.so` directory, and includes a copy and checksum under `assets/runtime/<abi>/`.

The manifest sets `android:extractNativeLibs="true"` so Android extracts the executable into `nativeLibraryDir` with its executable mode. The Kotlin runtime starts that exact file; it does not copy an executable into an app data directory that may be mounted no-exec.

## Runtime behavior

- Root filesystems stay under the app-private `filesDir/distros/<id>/rootfs` directory.
- PRoot's host temporary files stay under the app-private cache directory and are passed through `PROOT_TMP_DIR`.
- The initial shell is `/bin/sh -i` (or `/usr/bin/sh -i` when needed).
- The runtime uses `--kill-on-exit`, `--link2symlink`, and `-0`. The last option only emulates UID/GID 0 inside the guest; it does not grant Android root access.
- Only readable `/dev`, `/proc`, and `/sys` host paths are bound. Android's `/system` and `/apex` are deliberately not exposed inside the guest.
- Rootfs paths, the release file, the guest shell, and temporary directories are canonicalized and checked before execution.
- The terminal reader runs off the UI thread and sends output callbacks back to the main thread.

## Current limitations

- Alpine is currently the only Android distribution with a complete download and install path.
- The interactive shell is foreground-only. A persistent session service is not implemented.
- X11 and graphical Linux desktops are not part of this runtime milestone.
- PRoot depends on Android ptrace behavior and device policy. A successful APK build is not proof that every physical device can start a guest shell.
- CI build and physical-device acceptance tests must pass before describing the runtime as production-ready.

PRoot is a userspace compatibility layer, not a virtual machine, separate kernel, security boundary equivalent to a VM, or real privilege-escalation mechanism.
