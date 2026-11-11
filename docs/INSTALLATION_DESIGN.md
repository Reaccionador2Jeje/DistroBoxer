# Root filesystem installation design

This document defines the installation pipeline before network download and archive extraction are implemented.

## Required stages

1. Resolve a distribution from the built-in catalog.
2. Resolve an architecture supported by both the Android device and selected rootfs.
3. Obtain a pinned rootfs URL from reviewed project metadata.
4. Download to the distribution's private `downloads/` directory.
5. Verify a SHA-256 digest obtained from a trusted, reviewed source.
6. Inspect archive entries and reject absolute paths, parent traversal, and unsafe link targets.
7. Extract into a temporary staging directory.
8. Validate expected root filesystem structure.
9. Atomically promote the staged rootfs into `rootfs/`.
10. Persist the Installed state only after every previous step succeeds.

## Failure handling

- Preserve the previous valid rootfs until a replacement is fully validated.
- Remove only temporary files created by the current installation attempt.
- Record a concise error state in `metadata.json`.
- Support cancellation without marking a partial installation as complete.
- Never run downloaded shell scripts as part of installation.

## Trust and supply chain

A URL alone is not sufficient validation. The release metadata must include a digest from a trusted source, and project maintainers must review redistribution terms for the selected rootfs. Do not ship a placeholder checksum or silently skip verification.

## Android constraints

The Android app must use app-private storage. Storage permissions should not be requested for directories that the app does not need. PRoot is a userspace compatibility layer and does not provide a separate kernel or full hardware virtualization.
