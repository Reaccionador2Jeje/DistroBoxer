# PRoot runtime and X11 integration

## PRoot runtime added

The Android app now has a process-level PRoot session manager. It:

- Uses only the app-private `filesDir` runtime and distro directories.
- Requires a checksum-verified PRoot executable at `runtime/bin/proot`.
- Validates the selected distro ID, rootfs path, `etc/os-release`, and shell.
- Starts an interactive login shell with a minimal environment.
- Exposes the process streams to a future terminal view and supports stopping it.
- Does not grant real root access; PRoot's `-0` option only emulates root identity.

The executable is not yet bundled or downloaded by this release. The stream installer accepts a SHA-256 digest, but a pinned Android-compatible binary source and license/notices review must be completed before the app can run PRoot on-device. Do not substitute a random Linux desktop binary: Android's Bionic runtime and ABI compatibility matter.

## X11 boundary

The app now defines a display backend contract and a private session configuration. The embedded backend remains unavailable until an Android-native X server and Surface renderer are included in the APK.

A separate Termux:X11 installation is not automatically usable by DistroBoxer: Android applications have separate private storage and SELinux contexts. Simply opening the Termux:X11 activity or setting `DISPLAY=:1` does not share the Unix socket. A supported external backend would need an explicit, permissioned bridge and a tested socket transport; the app must not claim that X11 is running before that bridge exists.

## Required next engineering steps

1. Pin and build an Android-compatible PRoot executable for `arm64-v8a` and `x86_64`, verify its SHA-256 in CI, and ship its GPL-2.0 license and source offer/notices.
2. Add process supervision in a foreground service for long-running Linux sessions.
3. Integrate an Android-native X server with a Surface renderer, keeping its Unix socket in app-private storage.
4. Launch XFCE with a session D-Bus inside the PRoot rootfs and pass the private display socket into the guest.
5. Test on Android 8+, Android 13+ notification behavior, touch, keyboard, resize, process cleanup, and low-memory termination.

## Current capability status

| Capability | Status |
|---|---|
| Rootfs extraction | Implemented for supported archive format |
| PRoot process/session manager | Implemented; needs a packaged Android-compatible executable |
| Interactive terminal UI | Not connected yet |
| X11 backend contract | Implemented |
| Embedded X server and renderer | Not implemented |
| XFCE/LXQt/MATE launch | Not available until PRoot and X11 are shipped |
