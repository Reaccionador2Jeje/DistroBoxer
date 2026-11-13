# Android application

DistroBoxer Android is a native Kotlin + Jetpack Compose app backed by the Rust engine through JNI.

## Current state

- Displays eight distribution profiles from the Rust engine.
- Detects device ABI and prepares app-private distribution directories.
- Includes an adaptive launcher icon.
- Includes a WorkManager download-to-install chain with HTTPS host allowlisting, SHA-256 verification, cancellation, and foreground notifications.
- Rust safely extracts gzip-compressed tar archives into a staging directory and promotes a validated rootfs atomically.
- The first Android install path is Alpine 3.24.4 for ARM64 and x86_64, using official archive and adjacent SHA-256 manifest URLs.
- PRoot is built from a pinned Termux source revision for `arm64-v8a` and `x86_64`; Android CI bundles the binaries into the APK.
- On first launch, the app copies the matching PRoot binary into private app storage and verifies SHA-256.
- The Terminal screen is connected to an interactive PRoot shell and supports sending commands and displaying output.
- The Programar screen includes a Python source editor, private project saving, and a path that copies the file into Alpine and runs it through the shell.
- A pinned Java X11 server is embedded in the Android app. The display surface is shown inside Compose and the TCP listener is patched to bind only to loopback.
- XFCE is the first experimental desktop target; full X11 extension and desktop compatibility is not yet verified.

## Known limitations

- Alpine is currently the only Android rootfs installation path; other catalog distributions are not yet installable end-to-end.
- The PRoot shell and terminal UI are integrated in source but still require physical-device validation.
- Python must be installed inside Alpine before running Python projects (for example, `apk add python3`).
- A persistent foreground service for long-running Linux sessions is not implemented.
- The embedded Java X11 server is lightweight, not a full X.Org replacement; GLX, GPU acceleration, and complete XFCE/LXQt/MATE support are not promised.
- OCI layer support for Debian/Ubuntu/Fedora/openSUSE/Kali and xz/zstd extraction for Void/Arch remain in development.

## Android permissions

- `INTERNET`: download trusted rootfs archives.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: show visible progress for a user-initiated archive transfer.
- `POST_NOTIFICATIONS`: allow Android 13+ to show download notifications.

The app does not request all-files access or broad shared-storage permissions. Distribution files stay in app-private storage. Android may still apply background execution limits; WorkManager handles deferrable archive transfers.

## Build

Requirements:
- JDK 17
- Android SDK platform 35 and build-tools 35.0.0
- Android NDK 27.2.12479018
- Rust stable with `aarch64-linux-android` and `x86_64-linux-android` targets
- `cargo-ndk`
- Gradle 8.9
- Git with submodule support

From the repository root, initialize the pinned X11 source and apply the loopback-only patch:

```sh
git submodule update --init --recursive
bash scripts/patch-x11-server.sh
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p distroboxer-engine
gradle :android:app:assembleDebug
```

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. Android CI builds both PRoot ABIs, checks out the pinned X11 submodule, applies the loopback-only patch, and uploads the APK as a workflow artifact.

## Release channels

Tagged versions trigger the GitHub prerelease workflow. Current APKs are testing builds, not production-signed releases. F-Droid publication is pending a reproducible offline build and official metadata review.
