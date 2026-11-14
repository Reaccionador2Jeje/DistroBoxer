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

The Android package carries QEMU executable assets under `assets/qemu/arm64-v8a/`. On first use, the app extracts `qemu-system-aarch64`, `qemu-img`, and their exact-SONAME shared-library dependencies into app-private executable storage. This avoids treating command-line programs as JNI libraries and preserves versioned library filenames. The initial build is pinned to QEMU 11.1.3 and a fixed Termux build-system revision; CI verifies the source archive SHA-256, ELF machine, Android linker, dependency closure, and runtime checksums.

## Migration phases

1. **Runtime API and private VM storage** — implemented: ABI payload discovery, safe VM IDs and paths, kernel/initrd import, QCOW2 creation, TCG launch, serial stdio, loopback-only optional VNC, and stop/status.
2. **Reproducible QEMU Android build** — initial ARM64 build recipe and reusable GitHub Actions workflow added. It builds QEMU System + qemu-img with TCG, libslirp user networking, and headless VNC support using a pinned QEMU source archive and Termux Android cross-build toolchain. CI packages the executable assets and recursively collects non-platform shared libraries. First successful workflow run is still required before the binaries can be considered verified.
3. **Terminal UI integration** — initial controls implemented: import kernel/initrd, create a 12 GiB QCOW2 disk, start/stop QEMU, toggle optional loopback VNC, and attach serial stdio to the existing terminal. General VM profiles, disk sizing UI, and VNC viewer remain.
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

The source tree now contains a reproducible ARM64 QEMU build workflow and asset loader, but the generated executables are not committed to Git; Android CI builds them as an artifact and injects them into the APK. Until that workflow completes successfully and the APK is tested on a physical device, QEMU is not considered verified. A guest kernel/initramfs and a VNC viewer are still not bundled. PRoot remains in place intentionally until the acceptance gates pass.
