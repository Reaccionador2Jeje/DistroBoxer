# Root filesystem installation design

This document defines the installation pipeline. The Alpine gzip path and a multi-distribution OCI pull/apply path are implemented as previews. The OCI path resolves platform manifests, verifies layer digests, applies whiteouts, and validates staged rootfs promotion.

## Required stages

1. Resolve a distribution from the built-in catalog.
2. Resolve an architecture supported by both the Android device and selected rootfs.
3. Resolve a reviewed per-distribution source manifest. Alpine uses a pinned minirootfs URL; OCI profiles resolve a registry tag to a content digest.
4. Download to the distribution's private `downloads/` directory.
5. Verify a SHA-256 digest obtained from a trusted, reviewed source using the engine's integrity module.
6. Inspect archive entries and reject absolute paths, parent traversal, unsafe link targets, and unsafe OCI whiteout targets.
7. Apply OCI layers in order (including whiteouts) or extract the Alpine tarball into a temporary staging directory.
8. Validate expected root filesystem structure.
9. Atomically promote the staged rootfs into `rootfs/`.
10. Persist the Installed state only after every previous step succeeds.

## Failure handling

- Preserve the previous valid rootfs until a replacement is fully validated.
- Remove only temporary files created by the current installation attempt.
- Record a concise error state in `metadata.json`.
- Support cancellation during archive and OCI downloads, removing partial downloads.
- Native layer extraction is not yet interruptible; the app must not report success until validation and promotion finish.
- Never run downloaded shell scripts as part of installation.

## Trust and supply chain

A URL alone is not sufficient validation. Alpine release metadata must use an official checksum manifest. OCI layers must match the digests in the registry manifest, and the resolved manifest digest is recorded. Floating tags are not immutable pins; production releases should add reviewed immutable digests and upstream signature verification. Do not ship placeholder checksums or silently skip verification.

## Android constraints

Arch Linux's official OCI image currently supports x86_64 only; do not substitute an unofficial ARM image under the same profile. The other current OCI profiles target ARM64 and x86_64. OCI images are container rootfs bases, not bootable VM disks.

The Android app must use app-private storage. Storage permissions should not be requested for directories that the app does not need. PRoot is a userspace compatibility layer and does not provide a separate kernel or full hardware virtualization.
