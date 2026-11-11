# Runtime components

## Android PRoot

DistroBoxer builds PRoot from the pinned Termux source revision documented in
`.github/workflows/proot-runtime.yml` for:

- `arm64-v8a`
- `x86_64`

The reusable workflow produces one artifact per ABI. Android CI downloads those
artifacts, extracts the executable and license into APK assets, and generates a
SHA-256 file from each exact executable.

At first app launch, `ProotRuntime` selects the matching Android ABI, copies the
asset into the app-private `filesDir/runtime/bin/proot` path, verifies the
SHA-256, and marks the executable for the app user.

PRoot does not grant Android root privileges. It provides a userspace Linux
process environment through ptrace-based translation.

## Current boundaries

The executable is packaged and installed, but this alone is not a complete
Linux desktop. Remaining work includes:

- Reliable interactive shell/session lifecycle.
- Rootfs provisioning and validation for each supported distribution.
- Persistent foreground session service and terminal I/O UI.
- A real Android X11 server/display surface and desktop process orchestration.

Do not describe XFCE, LXQt, or MATE as runnable until the display server,
rootfs integration, and session flow have been tested end-to-end.

## Licensing and provenance

The PRoot license is packaged alongside the runtime assets. The build pins the
upstream source commit and verifies the talloc source archive before compiling.
Each APK runtime asset is checksum-verified during installation.
