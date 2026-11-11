# Android application

DistroBoxer Android is a native Kotlin + Jetpack Compose app backed by the Rust engine through JNI.

## Current state

- Displays eight distribution profiles from the Rust engine.
- Detects device ABI and prepares app-private distribution directories.
- Includes an adaptive launcher icon.
- Includes a WorkManager download-to-install chain with HTTPS host allowlisting, SHA-256 verification, cancellation, and foreground notifications.
- Rust safely extracts gzip-compressed tar archives into a staging directory and promotes a validated rootfs atomically.
- `DistroDownloadScheduler` requires a verified source URL and published checksum; no source manifests are pinned in the app yet.
- OCI layer support, other archive compression formats, PRoot, and interactive terminal sessions are not implemented yet.

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

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. Android CI uploads it as a workflow artifact.

## Release channels

Tagged versions trigger the GitHub prerelease workflow. Current APKs are testing builds, not production-signed releases. F-Droid publication is pending a reproducible offline build and official metadata review.
