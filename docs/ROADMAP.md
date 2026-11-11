# DistroBoxer roadmap

## 0.3.0 — Cross-platform foundation
- [x] Shared Rust engine and distribution catalog
- [x] Add profiles for Debian, Ubuntu, Alpine, Arch, Fedora, openSUSE, Void, and Kali
- [x] Android JNI catalog integration
- [x] Android adaptive launcher icon
- [x] WorkManager verified archive download pipeline
- [x] Foreground download progress notification
- [x] Tauri desktop shell and shared catalog UI
- [x] Linux, Windows, and macOS desktop CI
- [x] Tagged GitHub prerelease workflow
- [x] Android app listing text and F-Droid preparation checklist

## 0.4.0 — Trusted installer foundation
- [ ] Publish pinned, architecture-specific source manifests for every distro
- [ ] Confirm official archive provenance and checksums/signatures
- [x] Implement secure gzip tar extraction with traversal and link validation
- [ ] Add xz and zstd archive support
- [ ] Implement OCI manifest, layer, and whiteout handling
- [x] Stage and validate gzip rootfs before promoting it
- [ ] Recover cleanly from interrupted downloads and extraction
- [ ] Add storage usage reporting and install/uninstall UI
- [ ] Pin an official Alpine minirootfs URL and checksum
- [ ] Complete and test Alpine first-install flow

## 0.5.0 — Android runtime
- [ ] Review and package a compatible PRoot runtime with license notices
- [ ] Configure isolated runtime environment and bind mounts
- [ ] Start and stop a distribution process safely
- [ ] Add user-visible foreground service for active Linux sessions
- [ ] Handle Android process recreation and session recovery
- [ ] Interactive terminal session API and UI

## 0.6.0 — Additional distro installers
- [ ] Debian and Ubuntu OCI installers
- [ ] Fedora and openSUSE OCI installers
- [ ] Arch Linux bootstrap installer
- [ ] Void Linux rootfs installer
- [ ] Kali Linux OCI installer
- [ ] Automated install tests per ABI

## 0.7.0 — Desktop runtime
- [ ] Windows WSL2 integration and capability detection
- [ ] Linux container/runtime integration
- [ ] Desktop installation and terminal UI
- [ ] Platform-specific packaged installers and icons

## 0.8.0 — Publication
- [ ] Production signing configuration for Android
- [ ] Signed APK/AAB release pipeline
- [ ] Reproducible F-Droid build without network dependency
- [ ] Submit and pass F-Droid inclusion review
- [ ] Publish Windows and Linux desktop packages
- [ ] User documentation, privacy policy, and license inventory
