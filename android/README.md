# Android application

DistroBoxer Android is a native Kotlin + Jetpack Compose app backed by the Rust engine through JNI.

## Current state

- Displays eight distribution profiles from the Rust engine.
- Detects device ABI and prepares app-private distribution directories.
- Includes an adaptive launcher icon.
- Includes WorkManager archive and OCI download-to-install chains with HTTPS-only registry access, digest verification, cancellation, and foreground notifications.
- Rust safely extracts Alpine gzip tarballs and applies verified OCI image layers into a staging directory, including whiteouts, then validates and promotes the rootfs atomically.
- Alpine 3.24.4 installs from the official ARM64/x86_64 minirootfs source.
- Debian, Ubuntu, Arch Linux, Fedora, openSUSE Tumbleweed, Void Linux, and Kali Linux have OCI registry install manifests and WorkManager download/install chains. Arch's official OCI image is x86_64-only; the other profiles support ARM64 and x86_64.
- QEMU is the target primary Android runtime. `QemuRuntime` now provides private VM directories, safe kernel/initrd import, QCOW2 creation, TCG boot command construction, serial stdio, and optional loopback-only VNC.
- PRoot remains in the app temporarily as a legacy path; it will not be removed until QEMU passes the physical-device acceptance gates.
- The ABI-matched PRoot executable is packaged as an Android native payload in `nativeLibraryDir`; startup verifies its Unix execute bits and Android execute access.
- The Terminal screen is connected to an interactive PRoot shell and supports sending commands and displaying output. Startup repairs only required guest shell traversal bits, prepares `/root` and `/tmp`, and uses a tested app-private temporary directory.
- PRoot binds only `/dev`, `/proc`, and `/sys` when available; Android `/system` and `/apex` are not exposed to the guest. Early process exits surface their actual output instead of appearing as successful connections.
- The Programar screen includes a Python source editor, private project saving, and a path that copies the file into Alpine and runs it through the shell.
- A pinned Java X11 server is embedded in the Android app. The display surface is shown inside Compose and the TCP listener is patched to bind only to loopback.
- XFCE is the first experimental desktop target; full X11 extension and desktop compatibility is not yet verified.

## Known limitations

- Multi-distro OCI installation is an implementation preview. Automated registry integration tests and physical-device installation tests remain pending. OCI images are container base root filesystems, not full bootable VM images. Arch is x86_64-only.
- The reusable `qemu-runtime.yml` workflow builds pinned QEMU 11.1.3 for Android ARM64 through a pinned Termux cross-build system. It packages `qemu-system-aarch64`, `qemu-img`, and their shared-library closure as APK assets. `QemuRuntime` extracts them into app-private storage and supplies `LD_LIBRARY_PATH`. The first successful CI run and physical-device validation are still required; a guest kernel/initramfs and VNC viewer are not bundled yet.
- The PRoot shell remains the currently wired terminal path during this migration. Permission hardening is implemented, but a successful boot still needs physical-device testing; Android SELinux and ptrace restrictions cannot be bypassed by chmod.
- Python must be installed inside Alpine before running Python projects (for example, `apk add python3`).
- A persistent foreground service for long-running Linux sessions is not implemented.
- The embedded Java X11 server is lightweight, not a full X.Org replacement; GLX, GPU acceleration, and complete XFCE/LXQt/MATE support are not promised.
- OCI image signature verification and robust recovery from interrupted multi-layer downloads/extraction remain in development.
- Kali's official rolling container image is a minimal base and does not include the default Kali metapackage; Void uses the official full glibc OCI image.

## Android permissions

- `INTERNET`: download trusted rootfs archives.
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC`: show visible progress for a user-initiated archive transfer.
- `POST_NOTIFICATIONS`: allow Android 13+ to show download notifications.

The app does not request all-files access or broad shared-storage permissions. Distribution files stay in app-private storage. Android may still apply background execution limits; WorkManager handles deferrable archive transfers.

## Build

Requirements:
- JDK 17
- Android SDK platform 35 and build-tools 35.0.0
- Android NDK 27.2.12479018
- Rust stable with `aarch64-linux-android` and `x86_64-linux-android` targets
- `cargo-ndk`
- Gradle 8.9
- Git with submodule support

From the repository root, initialize the pinned X11 source and apply the loopback-only patch:

```sh
git submodule update --init --recursive
bash scripts/patch-x11-server.sh
cargo ndk -t arm64-v8a -t x86_64 -o android/app/src/main/jniLibs build --release -p distroboxer-engine
gradle :android:app:assembleDebug
```

The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk`. Android CI builds the legacy PRoot payload and the QEMU ARM64 runtime artifact before assembling the APK. See [QEMU migration](../docs/QEMU_MIGRATION.md).

## Release channels

Tagged versions trigger the GitHub prerelease workflow. Current APKs are testing builds, not production-signed releases. F-Droid publication is pending a reproducible offline build and official metadata review.
