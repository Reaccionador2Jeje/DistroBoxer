# DistroBoxer roadmap

## 0.1.0 — Core foundation
- [x] Rust workspace and library crate
- [x] Initial distribution catalog
- [x] Distribution identifier validation
- [x] Storage path helper
- [x] Root filesystem presence check
- [x] Unit tests and CI
- [x] Android Compose preview shell

## 0.2.0 — Storage and metadata
- [x] Per-distro metadata model
- [x] Safe directory creation with symlink rejection
- [x] Installation lifecycle state model
- [x] Metadata persistence with atomic file replacement
- [x] Rootfs presence validation
- [x] Initial aarch64 and x86_64 compatibility metadata
- [ ] Storage usage reporting

## 0.3.0 — Root filesystem installation
- [ ] Select a trusted Debian rootfs source
- [ ] Download with progress and cancellation
- [x] SHA-256 file verification primitive
- [ ] Select and pin a trusted Debian rootfs source
- [ ] Verify its published checksum or signature
- [ ] Safely extract rootfs archives
- [ ] Recover cleanly from interrupted installs

## 0.4.0 — Android runtime
- [ ] Evaluate and package a compatible PRoot build
- [x] Detect Android device ABI in the preview UI
- [ ] Map Android ABIs to Linux userspace architectures in the Rust bridge
- [ ] Configure isolated runtime environment
- [ ] Start and stop a distribution process safely

## 0.5.0 — Terminal
- [ ] Interactive terminal session API
- [ ] Standard input/output streaming
- [ ] Session lifecycle and exit status
- [ ] Basic terminal UI

## 0.6.0 — Android integration
- [x] Initial Kotlin/Compose application shell
- [ ] Rust bridge
- [ ] Distribution management screens
- [ ] Installation progress and error reporting

## 1.0.0 — First public release
- [ ] Debian install and launch flow
- [ ] Documentation and troubleshooting
- [ ] Release builds and reproducible CI
