# Android application

DistroBoxer Android is a native Kotlin + Jetpack Compose application backed by the Rust engine through JNI.

## Current state

- Displays the distribution catalog returned by Rust.
- Detects and displays the device ABI.
- Packages native Rust libraries for `arm64-v8a` and `x86_64`.
- Install actions remain disabled until the rootfs installer and PRoot runtime are implemented.

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

The debug APK is generated at:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

The GitHub Actions Android workflow builds the native libraries, assembles the APK, and uploads it as the `DistroBoxer-debug-apk` artifact.

The preview currently does not request shared-storage permissions. Distribution files will be kept in app-private storage.
