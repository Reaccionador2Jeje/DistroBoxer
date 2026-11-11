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

The catalog currently contains Debian, Ubuntu, Alpine, Arch Linux, Fedora, openSUSE, Void Linux, and Kali Linux. Their bootstrap labels describe intended source formats, not completed installers.

### Android application

The native Compose application loads the Rust catalog through JNI. Distribution files remain in app-private storage; broad shared-storage permissions are not required.

### Background transfers

A WorkManager CoroutineWorker provides persistent, cancellable archive downloads with:
- Connected-network constraints and exponential retry policy.
- Foreground data-sync notification and progress.
- HTTPS-only downloads from an explicit host allowlist.
- Redirect validation and a maximum archive size.
- Required SHA-256 verification before finalizing the archive.
- Temporary-file cleanup on failure or cancellation.

The worker only downloads and verifies an archive. It does not extract it, run scripts, or start Linux. Interactive Linux sessions need a separate user-visible foreground service/runtime integration and are not implemented yet.

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

An installer must use pinned, trusted source metadata and published checksums. Extraction must reject traversal, unsafe links, device nodes where not required, and writes outside the destination. OCI distributions require manifest and layer handling, including whiteouts; they must not be treated as a single ordinary tarball.

### Android runtime

PRoot is the initial candidate for running Linux userspace without root. Its source, license, ABI compatibility, and redistribution terms must be checked before packaging. Android kernel and system-call limitations still apply.

### Desktop application

The Tauri shell calls the shared Rust engine and displays the same distribution catalog. Future platform adapters may use WSL2 on Windows and suitable container facilities on Linux. The initial desktop shell does not yet install or execute distributions.

## Distribution bootstrap strategies

| Distribution | Intended bootstrap | Package manager |
|---|---|---|
| Debian | OCI root filesystem | APT |
| Ubuntu | OCI root filesystem | APT |
| Alpine | Official minirootfs tarball | APK |
| Arch Linux | Official bootstrap tarball (architecture-specific) | Pacman |
| Fedora | OCI root filesystem | DNF |
| openSUSE | OCI root filesystem | Zypper |
| Void Linux | Official rootfs tarball | XBPS |
| Kali Linux | OCI root filesystem | APT |

A profile is not installable until its exact architecture-specific source, checksum/signature, extraction rules, and runtime behavior are verified.

## Release strategy

- GitHub Releases provide tagged development builds.
- Production Android releases require a protected signing key and reproducible release build.
- F-Droid requires a network-independent, reproducible source build and a reviewed fdroiddata metadata entry.
