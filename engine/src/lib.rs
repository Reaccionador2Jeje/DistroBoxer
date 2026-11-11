//! DistroBoxer cross-platform engine.
//!
//! This crate provides distribution metadata, storage management, and safe path helpers.
//! Download, extraction, and runtime execution are intentionally separate future stages.

use std::path::{Path, PathBuf};

pub mod metadata;
pub mod storage;

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
}

/// Initial supported distribution catalog.
pub const DISTROS: &[Distro] = &[
    Distro {
        id: "debian",
        name: "Debian",
        package_manager: "apt",
    },
    Distro {
        id: "alpine",
        name: "Alpine Linux",
        package_manager: "apk",
    },
    Distro {
        id: "ubuntu",
        name: "Ubuntu",
        package_manager: "apt",
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

/// Checks whether the expected root filesystem directory exists.
pub fn is_installed(base: impl AsRef<Path>, id: &str) -> bool {
    distro_path(base, id)
        .map(|path| path.join("rootfs").join("etc/os-release").is_file())
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
