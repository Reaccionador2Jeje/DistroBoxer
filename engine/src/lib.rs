//! DistroBoxer cross-platform engine.
//!
//! This crate provides distribution metadata, storage management, and safe path helpers.
//! Download, extraction, and runtime execution are intentionally separate future stages.

use std::path::{Path, PathBuf};

pub mod integrity;
pub mod metadata;
pub mod storage;

pub use integrity::{sha256_file, verify_sha256};

pub use metadata::{DistroMetadata, InstallStatus};
pub use storage::StorageManager;

/// Metadata for a supported Linux distribution.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Distro {
    /// Stable identifier used in configuration and storage paths.
    pub id: &'static str,
    /// Human-readable distribution name.
    pub name: &'static str,
    /// Native package manager command.
    pub package_manager: &'static str,
    /// Supported Linux userspace architectures.
    pub architectures: &'static [&'static str],
}

/// Initial supported distribution catalog.
pub const DISTROS: &[Distro] = &[
    Distro {
        id: "debian",
        name: "Debian",
        package_manager: "apt",
        architectures: &["aarch64", "x86_64"],
    },
    Distro {
        id: "alpine",
        name: "Alpine Linux",
        package_manager: "apk",
        architectures: &["aarch64", "x86_64"],
    },
    Distro {
        id: "ubuntu",
        name: "Ubuntu",
        package_manager: "apt",
        architectures: &["aarch64", "x86_64"],
    },
];

/// Returns the supported distribution catalog.
pub fn supported_distros() -> &'static [Distro] {
    DISTROS
}

/// Finds a distribution by its stable identifier.
pub fn find_distro(id: &str) -> Option<&'static Distro> {
    DISTROS.iter().find(|distro| distro.id == id)
}

/// Returns the supported architectures for a distribution.
pub fn supported_architectures(id: &str) -> Option<&'static [&'static str]> {
    find_distro(id).map(|distro| distro.architectures)
}

/// Checks whether a distribution supports a Linux architecture name.
pub fn supports_architecture(id: &str, architecture: &str) -> bool {
    supported_architectures(id)
        .map(|architectures| architectures.contains(&architecture))
        .unwrap_or(false)
}

/// Returns whether an identifier exists in the supported catalog.
pub fn is_supported(id: &str) -> bool {
    find_distro(id).is_some()
}

/// Builds the app-private directory for a supported distribution.
///
/// Unknown identifiers are rejected rather than being used as path input.
pub fn distro_path(base: impl AsRef<Path>, id: &str) -> Option<PathBuf> {
    is_supported(id).then(|| base.as_ref().join("distros").join(id))
}

/// Checks whether a valid root filesystem is present in app-private storage.
pub fn is_installed(base: impl AsRef<Path>, id: &str) -> bool {
    StorageManager::new(base.as_ref())
        .is_installed(id)
        .unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temporary_directory() -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock should be after UNIX epoch")
            .as_nanos();

        std::env::temp_dir().join(format!("distroboxer-test-{nonce}"))
    }

    #[test]
    fn catalog_contains_initial_distributions() {
        assert_eq!(supported_distros().len(), 3);
        assert!(is_supported("debian"));
        assert!(is_supported("alpine"));
        assert!(is_supported("ubuntu"));
        assert!(supports_architecture("debian", "aarch64"));
        assert!(supports_architecture("alpine", "x86_64"));
        assert!(!supports_architecture("ubuntu", "armv7l"));
    }

    #[test]
    fn unknown_distribution_is_rejected() {
        assert!(find_distro("unknown").is_none());
        assert!(!is_supported("../outside"));
        assert!(distro_path("/tmp/distroboxer", "../outside").is_none());
    }

    #[test]
    fn builds_distribution_path() {
        let path = distro_path("/data/app", "debian").expect("Debian is supported");
        assert_eq!(path, PathBuf::from("/data/app/distros/debian"));
    }

    #[test]
    fn detects_rootfs_directory() {
        let base = temporary_directory();
        let rootfs = distro_path(&base, "debian")
            .expect("Debian is supported")
            .join("rootfs");

        assert!(!is_installed(&base, "debian"));

        fs::create_dir_all(rootfs.join("etc")).expect("rootfs directories should be created");
        fs::write(rootfs.join("etc/os-release"), "ID=debian\n")
            .expect("release file should be written");
        assert!(is_installed(&base, "debian"));

        fs::remove_dir_all(&base).expect("test directory should be removed");
    }
}
