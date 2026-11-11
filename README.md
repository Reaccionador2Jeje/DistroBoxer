# DistroBoxer

**DistroBoxer** is an open-source Linux distribution manager, starting with Android and designed to expand to Windows and Linux desktop.

The project uses a Rust core and a native Kotlin + Jetpack Compose Android application. The planned Android runtime uses PRoot to run Linux userspace environments without root; this is not a full hardware virtual machine and does not provide a separate Linux kernel.

## Project status

Early development — version 0.2.0 in progress.

Implemented:
- Rust distribution catalog for Debian, Ubuntu, and Alpine.
- Architecture compatibility metadata for `aarch64` and `x86_64`.
- Validated distribution IDs and app-private storage paths.
- Isolated per-distribution storage layout with symlink rejection.
- JSON metadata and installation lifecycle states.
- SHA-256 verification primitive for downloaded artifacts.
- Root filesystem presence validation using `etc/os-release`.
- Android Compose application with device ABI detection.
- JNI bridge loading the distribution catalog directly from Rust.
- Rust and Android GitHub Actions workflows.

Not implemented yet:
- Downloading and safely extracting Linux root filesystems.
- Bundling or launching PRoot.
- Package-manager execution and interactive terminal.
- Full Android distribution installation and lifecycle UI.

## Repository layout

```text
android/       Native Android application
engine/        Cross-platform Rust core and JNI bridge
runtime/       Runtime integration notes
distros/       Distribution metadata
docs/          Architecture, roadmap, and installer design
.github/       CI workflows
```

## Build the Rust engine

Requirements: Rust stable and Cargo.

```sh
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
```

## Build the Android application

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

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions also publishes it as the `DistroBoxer-debug-apk` workflow artifact.

## Android runtime direction

The Android app calls the Rust engine through JNI to load the distribution catalog and detects the device ABI. Install actions remain disabled until the rootfs installer and Linux runtime are ready. The Rust core remains independent of Android UI code.

## Security principles

- Never run downloaded scripts automatically.
- Verify checksums from trusted metadata before extracting root filesystems.
- Reject unsafe archive paths and links during extraction.
- Keep each distribution inside its own app-private directory.
- Validate distribution IDs before constructing filesystem paths.
- Do not claim root, kernel, or full-VM capabilities where Android does not provide them.

## License

MIT. See [LICENSE](LICENSE).
