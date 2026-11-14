# QEMU migration plan

## Decision

QEMU System becomes the target primary Android runtime. PRoot remains temporarily as a legacy compatibility path until QEMU binaries, boot media, VM lifecycle, terminal I/O, and optional VNC are verified on physical Android devices. Do not delete PRoot assets or installer assumptions before the acceptance gates below pass.

## Runtime architecture

- Android app-private VM data: `filesDir/qemu/vms/<vm-id>/`.
- Sparse virtual disks: QCOW2, created with the matching `qemu-img` executable.
- First guest target: AArch64 on ARM64 Android using QEMU TCG. KVM is not assumed; it generally requires device/kernel support and permissions unavailable to ordinary apps.
- Second guest target: x86_64 through TCG after the AArch64 path is validated.
- Console: QEMU serial stdio connected to the existing terminal process adapter.
- Optional graphical path: VNC bound strictly to `127.0.0.1`; never expose an unauthenticated VNC listener to Wi-Fi.
- Boot inputs: a matching Linux kernel and initramfs imported into private app storage. A rootfs tarball is not itself a bootable virtual machine disk.

## Binary packaging contract

The Android package must provide executable QEMU payloads in `nativeLibraryDir`:

- `libqemu-system-aarch64.so`
- `libqemu-system-x86_64.so`
- `libqemu-img.so`

These are executable payloads, not JNI libraries. The build pipeline must compile or obtain them from a pinned, license-reviewed source, verify SHA-256 and ELF machine/ABI, and package matching dependencies. Never add placeholder binaries or silently fall back to a host executable.

## Migration phases

1. **Runtime API and private VM storage** — implemented in `QemuRuntime.kt`: ABI payload discovery, safe VM IDs and paths, kernel/initrd import, QCOW2 creation, TCG launch, serial stdio, loopback-only optional VNC, and stop/status.
2. **Reproducible QEMU Android build** — build QEMU for Android ARM64 (then x86_64), pin all source/dependency revisions, package executable payloads, and verify ELF/dependencies in CI.
3. **Terminal UI integration** — add VM creation, kernel/initrd selection, disk sizing, start/stop controls, and attach the serial process to the existing terminal.
4. **Guest provisioning** — provide a supported bootable Linux kernel/initramfs + disk installation flow. Do not reuse the current Alpine rootfs tarball as if it were a boot disk.
5. **Optional VNC client** — connect only to the local VNC endpoint and validate touch/keyboard input.
6. **Physical-device acceptance** — test cold boot, terminal input/output, networking, disk persistence, stop/restart, memory pressure, and Android process lifecycle.
7. **PRoot retirement** — only after the above passes for supported devices, remove PRoot binaries, build workflow, runtime class, docs, and UI references in a separate reviewed change.

## Acceptance gates before PRoot removal

- QEMU System and qemu-img are real, pinned, checksummed Android executables for each supported ABI.
- CI confirms ELF architecture and all runtime dependencies.
- An official, redistributable guest kernel/initramfs or documented user-import flow boots successfully.
- Serial terminal input/output works on a physical Android device.
- QCOW2 data survives app restart and VM shutdown.
- VNC, when enabled, listens on loopback only.
- No silent PRoot fallback remains in the primary path.
- At least one maintainer verifies the end-to-end flow and its logs.

## Current limitations

The first implementation supplies the QEMU runtime API only. It does not yet bundle QEMU executables, boot firmware, a guest kernel/initramfs, a VNC viewer, or a complete VM creation UI. Consequently, QEMU cannot yet boot a guest from this commit alone. PRoot remains in place intentionally until the acceptance gates pass.
