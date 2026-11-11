# F-Droid publication

DistroBoxer is intended to be distributed through GitHub Releases and F-Droid. This directory records the submission checklist; it is not an official F-Droid metadata repository entry.

## Current status

**Not ready for F-Droid submission yet.** The Android app currently has a debug CI build, and the Rust engine is built with Cargo/NDK. A reproducible F-Droid build recipe must be validated before requesting inclusion.

## Required before submission

- Confirm the final application ID and stable release version.
- Keep all source code and build instructions in this public repository.
- Pin Gradle, Android Gradle Plugin, Kotlin, NDK, Rust toolchain, and Cargo dependencies.
- Vendor or otherwise make Rust crates available in the F-Droid build environment; do not rely on unrestricted network access during the build.
- Build native libraries reproducibly for each supported Android ABI.
- Ensure the release is free of proprietary bundled binaries and document licenses for any runtime component.
- Test the release APK from a clean F-Droid-style build.
- Add a valid `fdroiddata` metadata file and submit it through the official F-Droid inclusion process.

## App metadata

The human-readable listing text is maintained in `fastlane/metadata/android/en-US/`.

## Distribution channels

- GitHub Releases: tagged prerelease artifacts are built by `.github/workflows/release.yml`.
- F-Droid: submission is deferred until the reproducible build requirements above are met.

Do not describe the app as available on F-Droid until it has been accepted and published by F-Droid.
