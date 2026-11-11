# Android application

DistroBoxer Android is a native Kotlin + Jetpack Compose app backed by the Rust engine through JNI.

## Current state

- Displays eight distribution profiles from the Rust engine.
- Detects device ABI and prepares app-private distribution directories.
- Includes an adaptive launcher icon.
- Includes a WorkManager download-to-install chain with HTTPS host allowlisting, SHA-256 verification, cancellation, and foreground notifications.
- Rust safely extracts gzip-compressed tar archives into a staging directory and promotes a validated rootfs atomically.
- The first install preview is Alpine 3.24.4 for ARM64 and x86_64, using official archive and adjacent SHA-256 manifest URLs.
- `DistroDownloadScheduler` requires an allowlisted HTTPS source plus either a pinned digest or same-host checksum manifest.
- PRoot is built from a pinned Termux source revision for `arm64-v8a` and `x86_64` by the reusable GitHub Actions workflow. Android CI packages both binaries into the APK assets.
- On first launch, the app copies the matching PRoot binary into private app storage and verifies its SHA-256 before marking it executable.
- The PRoot executable is integrated, but interactive terminal sessions, persistent session service, X11 server/renderer, and desktop launch flow are still under development.
- OCI layer support for Debian/Ubuntu/Fedora/openSUSE/Kali and xz/zstd extraction for Void/Arch are not implemented yet.

## Android permissions

- `INTERNET`: download trusted rootfs archives.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: show visible progress for a user-initiated archive transfer.
- `POST_NOTIFICATIONS`: allow Android 13+ to show download notifications.

The app does not request all-files access or broad shared-storage permissions. Distribution files stay in app-private storage. Android may still apply background execution limits; WorkManager handles deferrable archive transfers. A future Linux terminal session will require a separate, persistent user-visible session service.

## Build

Requirements:
- JDK 17
- Android SDK platform 35 and build-tools 35.0.0
- Android NDK 27.2.12479018
- Rust stable with `aarch64-linux-android` and `x86_64-linux-android` targets
- `cargo-ndk`
- Gradle 8.9

From the repository root:

```sh
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p distroboxer-engine
gradle :android:app:assembleDebug
```

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. Android CI first builds both PRoot ABIs, packages them as app assets with per-binary checksums, then uploads the APK as a workflow artifact. A local Gradle build can still compile without those optional runtime assets, but the PRoot installer will report them as unavailable.

## Release channels

Tagged versions trigger the GitHub prerelease workflow. Current APKs are testing builds, not production-signed releases. F-Droid publication is pending a reproducible offline build and official metadata review.
