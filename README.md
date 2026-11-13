# DistroBoxer

**DistroBoxer** is an open-source Linux environment manager and lightweight development workspace for Android, Windows, and Linux desktop.

The project uses a shared Rust engine, a native Kotlin + Jetpack Compose Android application, and a Tauri desktop shell. On Android, PRoot runs Linux user-space programs without granting Android root access or providing a separate Linux kernel.

## Project status

Early development — version 0.3.0.

Implemented:
- Shared Rust engine and eight distribution catalog profiles: Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE, Void Linux, and Kali Linux.
- Architecture compatibility metadata for `aarch64` and `x86_64`.
- Validated distribution IDs and isolated app-private storage.
- JSON metadata and validated installation lifecycle transitions.
- Alpine rootfs download and installation pipeline with HTTPS allowlisting, SHA-256 verification, cancellation, and foreground notifications.
- Rust gzip tar extraction with path traversal and unsafe link checks, staging validation, and rootfs promotion.
- Android PRoot builds pinned to a Termux source revision for `arm64-v8a` and `x86_64`; Android CI packages the verified binaries into the APK.
- First-launch PRoot installation into app-private storage with SHA-256 verification.
- Interactive PRoot shell wired to the Android Terminal screen.
- A small programming workspace with a Python example, source editing, private project saving, and execution inside an installed Alpine rootfs.
- Embedded Android X11 server pinned as a submodule, loopback-only TCP listener, and Compose display surface.
- Android, desktop, and tagged-release GitHub Actions workflows.
- Initial Tauri desktop application for Windows, Linux, and macOS builds.
- Initial F-Droid listing text and submission checklist.

Still in development:
- Physical-device validation of PRoot shell startup and interactive I/O.
- Persistent foreground service for Linux sessions.
- Trusted rootfs manifests and end-to-end installation for every listed distribution; Alpine is the only current Android install path.
- Full X11 compatibility, GPU acceleration, and tested XFCE/LXQt/MATE sessions. The embedded Java X11 server is a lightweight first backend, not a full X.Org replacement.
- Safe archive/OCI layer extraction and recovery from interrupted extraction for all distro formats.
- Signed production Android APK/AAB and platform installer bundles.
- Official F-Droid submission and acceptance.

The catalog profiles describe intended bootstrap formats; they do not mean that every listed distribution is currently installable.

## Repository layout

```text
android/       Native Android app, PRoot runtime, embedded X11 submodule
desktop/       Tauri desktop application and SVG brand assets
engine/        Shared Rust core
runtime/       Runtime integration notes
docs/          Architecture and roadmap
fastlane/      Android app listing metadata
fdroid/        F-Droid submission checklist
scripts/       Build-time runtime patching helpers
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

Clone with submodules so the embedded X11 source is available:

```sh
git clone --recurse-submodules https://github.com/Reaccionador2Jeje/DistroBoxer.git
bash scripts/patch-x11-server.sh
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p distroboxer-engine
gradle :android:app:assembleDebug
```

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Android CI builds PRoot for both ABIs, checks out and patches the pinned X11 submodule, then packages the APK.

## Build desktop

Install the Tauri 2 prerequisites for your operating system, then run:

```sh
cargo run --manifest-path desktop/Cargo.toml
```

See [desktop/README.md](desktop/README.md) for platform-specific requirements.

## Graphical desktop roadmap

See [docs/GRAPHICAL_DESKTOPS.md](docs/GRAPHICAL_DESKTOPS.md) for the X11 + PRoot architecture and desktop roadmap. The Android X11 screen and PRoot launch path are integrated in source, but complete desktop compatibility still requires testing on physical Android devices.

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
- Bind the embedded X11 listener to loopback only; do not expose it to Wi-Fi.
- Never run downloaded setup scripts automatically.
- Do not claim root, kernel, or full-VM capabilities where Android does not provide them.

## License

MIT for DistroBoxer. Third-party runtime components retain their own licenses; see the notices under `android/app/src/main/assets/licenses/` and the PRoot license bundled in the APK.
