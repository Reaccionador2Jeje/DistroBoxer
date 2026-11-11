# DistroBoxer

**DistroBoxer** is an open-source project to manage Linux distribution environments on Android first, with a path toward Windows and Linux desktop support.

The project uses a Rust core and a native Android application. On Android, the initial runtime direction is an unprivileged userspace environment using PRoot; this is not a full hardware virtual machine and does not require rooting the device.

## Project status

Early development — version 0.2.0 in progress.

Current scope:
- Rust engine with a small, validated distribution catalog.
- Safe calculation of per-distribution storage paths.
- Isolated per-distribution storage layout.
- JSON metadata and installation lifecycle states.
- SHA-256 verification primitive for downloaded artifacts.
- Root filesystem validation using the standard `etc/os-release` marker.
- Architecture and roadmap documentation.
- CI checks for formatting, compilation, and tests.

Not implemented yet:
- Downloading or extracting root filesystems (design documented, implementation pending).
- Bundling or launching PRoot.
- Package-manager execution.
- Android UI or JNI integration.

## Repository layout

```text
android/       Android application integration (planned)
engine/        Cross-platform Rust core
runtime/       Runtime components and integration notes
distros/       Distribution metadata
docs/          Architecture and roadmap
.github/       CI workflows
```

## Build the Rust engine

Requirements: Rust stable and Cargo.

```sh
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
```

## Android direction

The Android app will eventually call the Rust core through a JNI-compatible bridge. The Rust core will remain independent of Android UI code. Runtime binaries and root filesystems will be handled as separate, verifiable components.

## Security principles

- Never run downloaded scripts automatically.
- Verify checksums and trusted metadata before extracting root filesystems.
- Keep each distribution inside its own app-private directory.
- Validate distribution IDs before constructing filesystem paths.
- Do not claim root, kernel, or full-VM capabilities where Android does not provide them.

## License

MIT. See [LICENSE](LICENSE).
