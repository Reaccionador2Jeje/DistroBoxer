# NELRoot Android integration

## Decision

DistroBoxer targets NELRoot as its only Linux execution backend. The Android app must not depend on the Termux application, Termux shell, PRoot, QEMU or Docker to start a guest environment.

The earlier PRoot implementation branch is an isolated prototype and must not be merged into the product branch as the permanent runtime.

## Current capability boundary

The NELRoot repository is still in early development:

- Its native `chroot` launcher is Linux-only and requires host root/capabilities.
- Its rootless namespace launcher is also Linux-only and depends on user/mount namespace and mount operations.
- The ELF parser, mapper, relocation and stack builder are preparation components; they do not yet transfer control to a guest ELF entry point or implement a guest syscall ABI.
- Building a NELRoot Android/Termux CLI package does not mean that Linux rootfs execution works inside an ordinary Android app sandbox.

Therefore DistroBoxer must not report a Linux session as available until an Android runtime capability probe succeeds and a real guest shell passes the device acceptance tests.

## Integration shape

The Android app should load NELRoot as a native library packaged in the APK and call a stable C ABI/JNI bridge. It must not shell out to `nelroot` in Termux or require the user to install Termux.

The bridge contract should be versioned and expose:

- `runtime_capabilities()`: host ABI, runtime version, supported guest ABIs, and explicit execution availability/reason.
- `image_list()`, `image_inspect()`, `image_pull()`, `image_remove()`: use the NELRoot Image Hub and verified image manifests.
- `session_start(image_id, shell, cwd)`: create a guest session and return a stable session ID.
- `session_read(session_id)` and `session_write(session_id, bytes)`: stream terminal output and input.
- `session_resize(session_id, columns, rows)`: resize the PTY when the Android terminal view changes.
- `session_signal(session_id, signal)` and `session_stop(session_id)`: manage process lifetime.
- Versioned structured errors; do not parse human-readable CLI output as an API.

The terminal must remain disabled with an explicit capability explanation until `runtime_capabilities()` reports guest execution available and the session path is implemented. Never display a simulated shell prompt as if commands were executing.

## Android runtime gates

Before enabling sessions, NELRoot must provide and test:

1. A native Android ARM64 library that can be loaded directly by DistroBoxer.
2. An Android-specific rootless execution design that does not assume Linux user/mount namespace permissions unavailable to ordinary Android apps.
3. A real guest ELF execution path and the minimum syscall/VFS behavior needed by the chosen shell.
4. A PTY-backed interactive shell with input, output, resize, exit status, signal handling and cleanup.
5. Rootfs path confinement, app-private storage, safe extraction, and explicit host-file bindings.
6. Device tests on supported Android versions and ARM64 devices.

A Linux-only namespace success test is not an Android acceptance test.

## DistroBoxer image coverage

The current DistroBoxer catalog contains Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE, Void Linux and Kali Linux.

The NELRoot repository currently contains publisher scripts for five families:

- Ubuntu Base
- Alpine Mini Root Filesystem
- Arch Linux Bootstrap
- Fedora Container Base
- Void Linux ROOTFS

Publisher/build pipelines still need to be added for:

- Debian
- openSUSE
- Kali Linux

Each missing family needs a reproducible official-source build, source checksum/signature verification where available, architecture-specific manifests, license/provenance metadata, rootfs validation, smoke tests and publication to NELRoot Image Hub. Do not mark an image as supported merely because an archive can be downloaded.

## Terminal UX

When the Terminal tab is open, it owns the complete app content area:

- Hide the normal app header and bottom navigation.
- Keep a compact terminal title/status bar and an exit action.
- Let terminal output occupy all remaining vertical space.
- Pin the prompt/input row to the bottom and keep it above the software keyboard.
- Do not show the prompt as active until a real NELRoot session is connected.

The Android system status/navigation bars may remain visible; "full screen" here means the complete DistroBoxer app content area.
