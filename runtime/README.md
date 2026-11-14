# Runtime components

## QEMU System (primary migration target)

`android/app/src/main/java/com/atdevdev/distroboxer/QemuRuntime.kt` introduces the first QEMU runtime API. It keeps VM state under `filesDir/qemu/vms/<vm-id>`, imports only fixed-name kernel/initrd files into private storage, creates sparse QCOW2 disks with `qemu-img`, and launches QEMU System with TCG, a serial stdio console, virtio disk/network devices, and optional VNC bound to `127.0.0.1` only.

The reusable `.github/workflows/qemu-runtime.yml` workflow builds pinned QEMU 11.1.3 for Android ARM64 using the Termux Android cross-build system. It verifies the QEMU source checksum, ELF ABI, Android linker, shared-library dependency closure, and runtime checksums before publishing an artifact that Android CI injects into APK assets. `QemuRuntime` extracts those assets into app-private storage and sets `LD_LIBRARY_PATH` for QEMU processes. The first successful workflow run and physical-device validation are still required. Guest kernel/initramfs provisioning and a VNC viewer remain pending. PRoot stays available until all migration acceptance gates pass. See [QEMU migration](../docs/QEMU_MIGRATION.md).


## Android PRoot

DistroBoxer builds PRoot from a pinned Termux source revision for:

- `arm64-v8a`
- `x86_64`

Android CI downloads the ABI artifacts, verifies the build output, packages each executable and its SHA-256 digest into APK assets, and includes the PRoot license.

At first launch, `ProotRuntime` selects the matching Android ABI, copies the asset into app-private storage at `filesDir/runtime/bin/proot`, verifies SHA-256, and marks the executable for the app user.

PRoot does not grant Android root privileges. It provides a Linux userspace process environment through ptrace-based translation.

## Interactive terminal and programming workspace

The Android Terminal screen starts an interactive shell through `ProotRuntime.startShell()`. `TerminalSession` reads process output off the UI thread and forwards typed commands to the shell.

The Programar screen provides a small source editor and private project workspace. It can save source files in app-private storage and copy the selected project into `/root/projects` in an installed Alpine rootfs before invoking Python. Python itself must be installed inside Alpine (for example, through Alpine's package manager). This is an initial coding workflow, not a full IDE yet.

## Embedded X11

The Android app includes a pinned submodule of the MIT-licensed Java Android X11 server. Its Android `ScreenView` is embedded in the Compose desktop screen. The build patches the upstream TCP listener to bind only to `127.0.0.1:6001`; Linux GUI processes use `DISPLAY=127.0.0.1:1`.

The server is lightweight and is not a full X.Org implementation. Do not assume GLX, GPU acceleration, or full XFCE/LXQt/MATE compatibility. Those require device testing and may need additional X11 extensions or a different backend.

## Current boundaries

- Alpine is the only current Android rootfs installation path.
- Shell startup and I/O are integrated in source but still need physical-device validation.
- A persistent foreground service for long-running Linux sessions is not implemented.
- The X11 server and Android screen surface are integrated, but full desktop sessions are experimental and not yet certified.
- Other distro bootstrap formats and robust recovery flows remain in development.

## Licensing and provenance

- PRoot is built from a pinned upstream Termux source revision; the PRoot license is bundled with the APK.
- The Java X11 server source is pinned as a Git submodule and retains its MIT license and original attribution.
- The X11 loopback-only bind change is applied by `scripts/patch-x11-server.sh` before Android compilation.
- Each bundled PRoot executable is checksum-verified during app installation.
