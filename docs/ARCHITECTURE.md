# DistroBoxer architecture

## Goals

- Android is the first target.
- The shared core is written in Rust.
- Android uses Kotlin + Jetpack Compose and JNI.
- Windows and Linux desktop use a Tauri 2 shell backed by the same Rust engine.
- Platform runtime adapters remain separate: Android userspace is not the same as WSL2 or Linux containers.

## Components

### Rust engine

The engine owns distribution profiles, architecture compatibility, storage layout, metadata, lifecycle state, integrity verification, and future installer/runtime orchestration.

The catalog contains Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE, Void Linux, and Kali Linux. Android now has a verified OCI pull/install path for every non-Alpine profile; Alpine retains its verified minirootfs path. Arch's official OCI image is x86_64-only.

### Android application

The native Compose application loads the Rust catalog through JNI. Distribution files remain in app-private storage; broad shared-storage permissions are not required.

### Background transfers

WorkManager provides persistent, cancellable archive and OCI downloads with:
- Connected-network constraints and exponential retry policy.
- Foreground data-sync notification and progress.
- HTTPS-only downloads from an explicit host allowlist.
- Redirect validation and a maximum archive size.
- Required SHA-256 verification before finalizing the archive.
- Temporary-file cleanup on failure or cancellation.

The download workers only fetch and verify archive or OCI content. They do not execute guest scripts or start Linux. The OCI path resolves a platform-specific manifest, verifies every layer digest, and stores generated layer blobs in private storage. Interactive Linux sessions need a separate user-visible foreground service/runtime integration and are not implemented yet.

### Root filesystem installation

Each distribution will have isolated app-private storage:

```text
files/
  distros/
    debian/
      rootfs/
      downloads/
      metadata.json
    alpine/
      rootfs/
      downloads/
      metadata.json
```

The Android OCI installer uses reviewed upstream image references, resolves the requested tag to a manifest digest, verifies every layer digest, applies OCI whiteouts, rejects traversal and unsafe links, and stages the resulting rootfs before validation and promotion. Tags are moving upstream channels rather than immutable release pins; the resolved digest is recorded with the downloaded manifest. Alpine continues to use its official gzip minirootfs flow.

#See [OCI installers](OCI_INSTALLERS.md) for image references, platform support, digest validation, layer application, and remaining validation work.

## Android runtime

QEMU System is the target primary runtime for full Linux virtual machines. The initial Kotlin adapter stores QCOW2 disks and boot inputs in app-private storage, uses TCG rather than assuming KVM, exposes a serial console through process stdio, and can optionally bind VNC to loopback only. The Android QEMU executables, reproducible build pipeline, guest kernel/initramfs provisioning, and UI integration are still pending. PRoot remains temporarily as a legacy compatibility path and must not be removed before the QEMU physical-device acceptance gates pass. See [QEMU migration](QEMU_MIGRATION.md).

### Desktop application

The Tauri shell calls the shared Rust engine and displays the same distribution catalog. Future platform adapters may use WSL2 on Windows and suitable container facilities on Linux. The initial desktop shell does not yet install or execute distributions.

## Distribution bootstrap strategies

| Distribution | Intended bootstrap | Archive format | Package manager |
|---|---|---|
| Debian | OCI root filesystem | OCI | APT |
| Ubuntu | OCI root filesystem | OCI | APT |
| Alpine | Official minirootfs tarball | tar.gz (preview installer) | APK |
| Arch Linux | Official OCI base image | OCI | Pacman |
| Fedora | OCI root filesystem | OCI | DNF |
| openSUSE | OCI root filesystem | OCI | Zypper |
| Void Linux | Official full OCI image | OCI | XBPS |
| Kali Linux | OCI root filesystem | OCI | APT |

The Android OCI installation path is an implementation preview and still needs automated registry integration and physical-device tests. Arch Linux is x86_64-only; the other OCI profiles target ARM64 and x86_64. Container rootfs images are not full bootable virtual machines.

## Release strategy

- GitHub Releases provide tagged development builds.
- Production Android releases require a protected signing key and reproducible release build.
- F-Droid requires a network-independent, reproducible source build and a reviewed fdroiddata metadata entry.
