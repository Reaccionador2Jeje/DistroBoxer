# DistroBoxer

**DistroBoxer** is an open-source Linux distribution environment manager for Android, Windows, and Linux desktop.

The project uses a shared Rust engine, a native Kotlin + Jetpack Compose Android application, and a Tauri desktop shell. The Android runtime is planned around PRoot or a compatible unprivileged userspace runtime. It is not a full virtual machine and does not provide a separate Linux kernel.

## Project status

Early development — version 0.3.0.

Implemented:
- Shared Rust engine and eight distribution catalog profiles: Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE, Void Linux, and Kali Linux.
- Architecture compatibility metadata for `aarch64` and `x86_64`.
- Validated distribution IDs and isolated app-private storage.
- JSON metadata and validated installation lifecycle transitions.
- SHA-256 verification primitive.
- Android Compose application and JNI bridge to Rust.
- Android adaptive launcher icon and app identity assets.
- WorkManager archive-download pipeline with HTTPS host allowlisting, SHA-256 verification, cancellation, and foreground progress notifications.
- Initial Tauri desktop application for Windows, Linux, and macOS builds.
- Android, desktop, and tagged-release GitHub Actions workflows.
- Initial F-Droid listing text and submission checklist.

Not implemented yet:
- Trusted, pinned rootfs manifests and end-to-end installation for each distribution.
- Safe archive/OCI layer extraction and recovery from interrupted extraction.
- Bundled PRoot runtime and interactive Linux terminal.
- Persistent background Linux sessions (the current background worker handles downloads only).
- Signed production Android APK/AAB and platform installer bundles.
- Official F-Droid submission and acceptance.

The catalog profiles describe intended bootstrap formats; they do not mean that every listed distribution is currently installable.

## Repository layout

```text
android/       Native Android app, WorkManager download pipeline, JNI
desktop/       Tauri desktop application and SVG brand assets
engine/        Shared Rust core
runtime/       Runtime integration notes
docs/          Architecture and roadmap
fastlane/      Android app listing metadata
fdroid/        F-Droid submission checklist
.github/       Android, desktop, and release workflows
```

## Build the Rust engine

```sh
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
```

## Build Android

Requirements: JDK 17, Android SDK platform 35, NDK 27.2.12479018, Rust stable, Android Rust targets, cargo-ndk, and Gradle 8.9.

```sh
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p distroboxer-engine
gradle :android:app:assembleDebug
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`.

## Build desktop

Install the Tauri 2 prerequisites for your operating system, then run:

```sh
cargo run --manifest-path desktop/Cargo.toml
```

See [desktop/README.md](desktop/README.md) for platform-specific requirements.

## Background work and permissions

Android distribution archives are stored in app-private storage. The WorkManager pipeline uses network connectivity constraints, a foreground data-sync notification, HTTPS source allowlisting, and a required SHA-256 digest. It does not request broad shared-storage access. Downloading an archive is not the same as installing or launching a distribution.

## Releases and F-Droid

Version tags trigger a GitHub prerelease workflow that builds an Android testing APK and desktop executables. These are development artifacts, not signed production installers.

F-Droid publication is not yet complete. See [fdroid/README.md](fdroid/README.md) for reproducible-build requirements and submission status.

## Security principles

- Only download over HTTPS from explicitly allowed distribution domains.
- Verify a published checksum before accepting an archive.
- Reject archive path traversal, unsafe links, and unexpected file types during extraction.
- Keep distributions inside isolated app-private directories.
- Never run downloaded setup scripts automatically.
- Do not claim root, kernel, or full-VM capabilities where Android does not provide them.

## License

MIT. See [LICENSE](LICENSE).
