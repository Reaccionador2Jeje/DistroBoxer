# DistroBoxer architecture

## Goals

- Android is the first target.
- The core is written in Rust and remains independent of the UI.
- Desktop support can be added without rewriting distribution metadata and core logic.
- The first Android runtime is an unprivileged userspace environment, not a full virtual machine.

## Components

### Android application

A native Kotlin application using Jetpack Compose is planned for the UI, lifecycle, permissions, and app-private storage. It will communicate with the Rust core through a JNI-compatible interface.

### Rust engine

The engine owns:
- Distribution catalog and validation.
- Storage layout and installation state.
- Runtime configuration.
- Process lifecycle abstractions.
- Download and extraction orchestration in a later milestone.

### Android runtime

PRoot is the initial candidate for running a Linux userspace without root. It must be packaged and integrated as a separately maintained runtime component. Android kernel and system-call limitations still apply.

### Root filesystem manager

Each distribution will have an isolated app-private directory:

```text
files/
  distros/
    debian/
      rootfs/
      metadata.json
    alpine/
      rootfs/
      metadata.json
```

Downloads must be verified before extraction. Extraction must reject path traversal entries and must not execute downloaded setup scripts automatically.

### Desktop adapters

Future platform adapters may use WSL2 on Windows and suitable namespaces/container facilities on Linux. These are platform-specific execution backends; they are not interchangeable with Android's PRoot backend.

## Current implementation

Version 0.1.0 contains only a Rust catalog, identifier validation, path construction, and installation-state detection. No Linux distribution is downloaded or executed yet.
