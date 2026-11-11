# Runtime components

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
