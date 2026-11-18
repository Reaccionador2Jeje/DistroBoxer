# Multi-distribution OCI installers

DistroBoxer now has an Android OCI pull-and-install path for the non-Alpine catalog profiles. Alpine keeps its existing official minirootfs archive flow.

## Images

| DistroBoxer profile | Registry image | Tag | Android ARM64 | Android x86_64 |
|---|---|---|---:|---:|
| Debian | `library/debian` | `stable-slim` | Yes | Yes |
| Ubuntu | `library/ubuntu` | `latest` | Yes | Yes |
| Arch Linux | `ghcr.io/archlinux/archlinux` | `latest` | No | Yes |
| Fedora | `library/fedora` | `latest` | Yes | Yes |
| openSUSE Tumbleweed | `opensuse/tumbleweed` | `latest` | Yes | Yes |
| Void Linux | `ghcr.io/void-linux/void-glibc-full` | `latest` | Yes | Yes |
| Kali Linux | `kalilinux/kali-rolling` | `latest` | Yes | Yes |

Upstream references: [Debian](https://hub.docker.com/_/debian), [Ubuntu](https://hub.docker.com/_/ubuntu), [Arch Linux](https://github.com/archlinux/archlinux-docker), [Fedora](https://hub.docker.com/_/fedora), [openSUSE Tumbleweed](https://hub.docker.com/r/opensuse/tumbleweed), [Void Linux](https://voidlinux.org/download/), and [Kali Linux](https://www.kali.org/docs/containers/official-kalilinux-docker-images/).

The image references are maintained in `android/app/src/main/assets/distro-manifests/` and mirrored in `distros/*.json`. Tags are upstream rolling/stable channels, not immutable release pins. At pull time DistroBoxer resolves the tag to an OCI manifest and records its digest; every layer descriptor is SHA-256 verified.

Arch Linux publishes an official OCI image for x86_64, not ARM64. DistroBoxer deliberately reports that limitation instead of installing an unrelated or incompatible ARM image.

## Download and install flow

1. The user selects a distribution in the Android app.
2. WorkManager obtains an anonymous pull token from Docker Hub or GitHub Container Registry.
3. The worker resolves the image tag, selects the `linux/arm64` or `linux/amd64` manifest, and verifies the manifest digest when supplied by the registry.
4. Each layer is downloaded into that distribution's app-private `downloads/oci/` directory. The worker checks declared size and SHA-256 before accepting it.
5. The native Rust engine verifies each layer digest again.
6. Layers are applied in order to a staging rootfs. OCI whiteouts and opaque-directory markers are processed so deleted files do not reappear from lower layers.
7. The engine validates `etc/os-release` against the selected distribution (including openSUSE's release IDs) and atomically promotes the rootfs.
8. Installation metadata is marked Installed only after validation and promotion succeed.

No downloaded setup script is executed. The OCI downloader supports Docker Hub and GHCR public images, gzip-compressed and uncompressed tar layers, and a bounded image size/layer count. Unsupported media types fail closed.

## What these images are

These are base container root filesystems, not bootable VM disks. They do not provide a separate kernel, guarantee systemd, or make every distribution feature available under Android PRoot. The app's current runtime and device compatibility still determine what can run.

The official Kali rolling image is a minimal container base and does not include Kali's default metapackage. The Void image selected here is the official full glibc container variant. openSUSE is Tumbleweed.

## Security and remaining validation

- HTTPS is required for registry and token requests.
- Registry redirects for blobs are restricted to known HTTPS registry/CDN hosts.
- Registry manifest digests and every layer SHA-256 are checked.
- Rust repeats layer verification before extraction.
- Archive path traversal and unsafe symlink targets are rejected by the shared tar extractor.
- OCI whiteouts remove only paths contained within the staging rootfs.
- Existing rootfs data is preserved until the new staging tree validates.

The current code is an implementation preview. It still needs automated registry integration tests, physical-device installation tests for each supported ABI, upstream signature/cosign verification, and recovery tests for interrupted pulls. Floating tags also mean two installations at different times may resolve to different upstream snapshots.
