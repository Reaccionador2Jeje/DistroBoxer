# DistroBoxer

**DistroBoxer** is an open-source Linux environment manager and lightweight development workspace for Android, Windows, and Linux desktop.

The project uses a shared Rust engine, a native Kotlin + Jetpack Compose Android application, and a Tauri desktop shell. Android uses PRoot as its primary userspace runtime. QEMU System remains a separate experimental track and is not required to install or launch the supported distributions.

## Project status

Early development — multi-distribution OCI installation preview (0.6.0).

Implemented:
- Shared Rust engine and eight distribution catalog profiles: Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE, Void Linux, and Kali Linux.
- Architecture compatibility metadata per distribution: six OCI profiles support ARM64 and x86_64; the official Arch Linux OCI image is x86_64-only.
- Validated distribution IDs and isolated app-private storage.
- JSON metadata and validated installation lifecycle transitions.
- Initial read-only DistroBoxer Doctor API for storage, metadata, rootfs identity, shell-path, and lifecycle consistency diagnostics.
- Passive host and runtime detection for OS, architecture, QEMU System, and PRoot availability.
- Read-only storage analysis for filesystem capacity, directory usage, distribution rootfs/downloads, and permission hints.
- Advisory Repair Planner that converts Doctor findings into risk-ranked suggestions without executing repairs.
- Alpine rootfs download and installation pipeline with HTTPS allowlisting, SHA-256 verification, cancellation, and foreground notifications.
- Android OCI image pull pipeline for Debian, Ubuntu, Arch Linux, Fedora, openSUSE Tumbleweed, Void Linux, and Kali Linux, with Docker Hub/GHCR token handling and platform manifest selection.
- OCI manifest and layer digest verification, bounded layer downloads, whiteout/opaque-directory handling, safe staged extraction, and distribution identity validation.
- Rust gzip tar extraction with path traversal and unsafe link checks, staging validation, and rootfs promotion.
- Initial QEMU System runtime API for private VM storage, QCOW2 disk creation, kernel/initrd import, TCG serial boot, and loopback-only optional VNC. QEMU executable payloads and boot media are not bundled yet.
- Existing Android PRoot runtime retained temporarily as the legacy path during the QEMU migration.
- First-launch PRoot installation into app-private storage with SHA-256 verification.
- Interactive PRoot shell wired to the Android Terminal screen.
- A small programming workspace with a Python example, source editing, private project saving, and execution inside an installed Alpine rootfs.
- Embedded Android X11 server pinned as a submodule, loopback-only TCP listener, and Compose display surface.
- Android, desktop, and tagged-release GitHub Actions workflows.
- Initial Tauri desktop application for Windows, Linux, and macOS builds.
- Initial F-Droid listing text and submission checklist.

Still in development:
- Physical-device validation of every distro's PRoot smoke test and interactive I/O on ARM64 and x86_64.
- Persistent foreground service for Linux sessions.
- Physical-device validation of OCI installation for each supported architecture; Arch Linux is currently x86_64-only.
- Full X11 compatibility, GPU acceleration, and tested XFCE/LXQt/MATE sessions. The embedded Java X11 server is a lightweight first backend, not a full X.Org replacement.
- OCI registry signature verification and robust recovery from interrupted multi-layer downloads/extraction.
- Signed production Android APK/AAB and platform installer bundles.
- Official F-Droid submission and acceptance.

The Android app installs Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE Tumbleweed, Void Linux, and Kali Linux into isolated root filesystems. Each successful installation must pass a real PRoot smoke test for its shell, writable runtime directories, identity file, and package manager before WorkManager reports success. Arch Linux is x86_64-only. OCI images are base container root filesystems, not bootable VM disks; physical-device validation is still required.

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

The debug APK is at `android/app/build/outputs/apk/debug/app-debug.apk`. Android CI builds PRoot for both ABIs, checks out and patches the pinned X11 submodule, then packages the APK. QEMU is intentionally not a dependency of this Android build.

## Build desktop

Install the Tauri 2 prerequisites for your operating system, then run:

```sh
cargo run --manifest-path desktop/Cargo.toml
```

See [desktop/README.md](desktop/README.md) for platform-specific requirements.

## DistroBoxer Doctor

The shared Rust engine exposes a read-only diagnostic API. See [docs/DOCTOR.md](docs/DOCTOR.md) for supported checks, Rust examples, diagnostic codes, and safety limitations.

## Runtime Detection

The shared Rust engine exposes a read-only host and runtime discovery API. See [docs/RUNTIME_DETECTION.md](docs/RUNTIME_DETECTION.md) for details.

## Storage Analyzer

The shared Rust engine exposes a read-only storage usage and capacity report. See [docs/STORAGE_ANALYZER.md](docs/STORAGE_ANALYZER.md) for details.

## Repair Planner

The shared Rust engine converts Doctor findings into risk-ranked, non-executable repair suggestions. See [docs/REPAIR_PLANNER.md](docs/REPAIR_PLANNER.md) for details.

## Multi-distribution OCI installers

Android can install Debian, Ubuntu, Arch Linux, Fedora, openSUSE Tumbleweed, Void Linux, and Kali Linux from verified OCI layers. See [docs/OCI_INSTALLERS.md](docs/OCI_INSTALLERS.md) for image sources, architecture support, and current limitations.

## Graphical desktop roadmap

See [docs/GRAPHICAL_DESKTOPS.md](docs/GRAPHICAL_DESKTOPS.md) for the X11 + PRoot architecture and desktop roadmap. The existing Android X11 screen is experimental. The optional QEMU research track is documented in [docs/QEMU_MIGRATION.md](docs/QEMU_MIGRATION.md); it does not block the PRoot-based Android runtime.

## Background work and permissions

Android distribution archives are stored in app-private storage. The WorkManager pipelines use network connectivity constraints, foreground data-sync notifications, HTTPS-only registry access, bounded downloads, and SHA-256 verification for archive checksums, OCI manifests, and image layers. It does not request broad shared-storage access. Downloading an archive is not the same as installing or launching a distribution.

## Releases and F-Droid

Version tags trigger a GitHub prerelease workflow that builds an Android testing APK and desktop executables. These are development artifacts, not signed production installers.

F-Droid publication is not yet complete. See [fdroid/README.md](fdroid/README.md) for reproducible-build requirements and submission status.

## Security principles

- Only download over HTTPS from explicitly allowed distribution domains.
- Verify published archive checksums and OCI manifest/layer digests before accepting image content.
- Reject archive path traversal, unsafe links, and unexpected file types during extraction.
- Keep distributions inside isolated app-private directories.
- Bind the embedded X11 listener to loopback only; do not expose it to Wi-Fi.
- Never run downloaded setup scripts automatically.
- Do not claim root, kernel, or full-VM capabilities where Android does not provide them.

## License

MIT for DistroBoxer. Third-party runtime components retain their own licenses; see the notices under `android/app/src/main/assets/licenses/` and the PRoot license bundled in the APK.
