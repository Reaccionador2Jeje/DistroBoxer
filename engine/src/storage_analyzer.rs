//! Read-only storage usage, capacity, and permission analysis.
//!
//! The analyzer never creates, deletes, repairs, or follows symbolic links.

use crate::{supported_distros, StorageManager};
use fs2::{available_space, total_space};
use serde::Serialize;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// A machine-readable storage finding.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct StorageFinding {
    pub code: String,
    pub level: StorageFindingLevel,
    pub distro_id: Option<String>,
    pub message: String,
    pub path: Option<String>,
}

/// Severity for storage findings.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum StorageFindingLevel {
    Info,
    Warning,
    Error,
}

/// Usage and permission snapshot for one directory.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DirectoryUsage {
    pub path: String,
    pub bytes: u64,
    pub files: u64,
    pub directories: u64,
    pub symlinks: u64,
    /// Whether the directory permissions indicate that the current user may write.
    /// This is a permission-bit hint, not an actual write attempt.
    pub writable_by_mode: bool,
}

/// Storage analysis for the app-private base directory.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct StorageReport {
    pub schema_version: u32,
    pub storage_exists: bool,
    pub total_bytes: Option<u64>,
    pub available_bytes: Option<u64>,
    pub app_storage: Option<DirectoryUsage>,
    pub distributions: Vec<DistributionStorage>,
    pub findings: Vec<StorageFinding>,
}

/// Storage details for a known distribution.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DistributionStorage {
    pub distro_id: String,
    pub exists: bool,
    pub rootfs: Option<DirectoryUsage>,
    pub downloads: Option<DirectoryUsage>,
}

impl StorageReport {
    pub fn has_errors(&self) -> bool {
        self.findings
            .iter()
            .any(|finding| finding.level == StorageFindingLevel::Error)
    }
}

/// Analyzes storage without changing files or following symlinks.
pub fn analyze_storage(storage: &StorageManager) -> StorageReport {
    let base = storage.base_dir();
    let mut findings = Vec::new();

    let base_metadata = match fs::symlink_metadata(base) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => Some(metadata),
        Ok(_) => {
            finding(
                &mut findings,
                "storage.base.invalid",
                StorageFindingLevel::Error,
                None,
                "Storage base is not a real directory.",
                Some(base),
            );
            None
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            finding(
                &mut findings,
                "storage.base.missing",
                StorageFindingLevel::Warning,
                None,
                "Storage has not been initialized.",
                Some(base),
            );
            None
        }
        Err(error) => {
            finding(
                &mut findings,
                "storage.base.unreadable",
                StorageFindingLevel::Error,
                None,
                &format!("Cannot inspect storage base: {error}"),
                Some(base),
            );
            None
        }
    };

    let app_storage = base_metadata
        .as_ref()
        .and_then(|_| match measure_directory(base) {
            Ok(usage) => Some(usage),
            Err(error) => {
                finding(
                    &mut findings,
                    "storage.scan.failed",
                    StorageFindingLevel::Error,
                    None,
                    &format!("Cannot scan app storage: {error}"),
                    Some(base),
                );
                None
            }
        });

    let total_bytes = base_metadata
        .as_ref()
        .and_then(|_| match total_space(base) {
            Ok(value) => Some(value),
            Err(error) => {
                finding(
                    &mut findings,
                    "storage.capacity.unavailable",
                    StorageFindingLevel::Warning,
                    None,
                    &format!("Filesystem capacity is unavailable: {error}"),
                    Some(base),
                );
                None
            }
        });
    let available_bytes = base_metadata
        .as_ref()
        .and_then(|_| match available_space(base) {
            Ok(value) => Some(value),
            Err(error) => {
                finding(
                    &mut findings,
                    "storage.available.unavailable",
                    StorageFindingLevel::Warning,
                    None,
                    &format!("Available space is unavailable: {error}"),
                    Some(base),
                );
                None
            }
        });

    if let (Some(total), Some(available)) = (total_bytes, available_bytes) {
        if total > 0 && available.saturating_mul(100) / total < 10 {
            finding(
                &mut findings,
                "storage.space.low",
                StorageFindingLevel::Warning,
                None,
                "Less than 10% of the filesystem space is available.",
                Some(base),
            );
        }
    }

    let mut distributions = Vec::new();
    for distro in supported_distros() {
        let distro_dir = match storage.distro_dir(distro.id) {
            Ok(path) => path,
            Err(error) => {
                finding(
                    &mut findings,
                    "storage.distro.path.invalid",
                    StorageFindingLevel::Error,
                    Some(distro.id),
                    &error.to_string(),
                    None,
                );
                continue;
            }
        };
        let exists = match fs::symlink_metadata(&distro_dir) {
            Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => true,
            Ok(_) => {
                finding(
                    &mut findings,
                    "storage.distro.directory.invalid",
                    StorageFindingLevel::Error,
                    Some(distro.id),
                    "Distribution path is not a real directory.",
                    Some(&distro_dir),
                );
                false
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => false,
            Err(error) => {
                finding(
                    &mut findings,
                    "storage.distro.directory.unreadable",
                    StorageFindingLevel::Error,
                    Some(distro.id),
                    &format!("Cannot inspect distribution directory: {error}"),
                    Some(&distro_dir),
                );
                false
            }
        };

        let rootfs = if exists {
            measure_optional_child(&distro_dir, "rootfs", distro.id, &mut findings)
        } else {
            None
        };
        let downloads = if exists {
            measure_optional_child(&distro_dir, "downloads", distro.id, &mut findings)
        } else {
            None
        };

        distributions.push(DistributionStorage {
            distro_id: distro.id.to_owned(),
            exists,
            rootfs,
            downloads,
        });
    }

    StorageReport {
        schema_version: 1,
        storage_exists: base_metadata.is_some(),
        total_bytes,
        available_bytes,
        app_storage,
        distributions,
        findings,
    }
}

fn measure_optional_child(
    parent: &Path,
    name: &str,
    distro_id: &str,
    findings: &mut Vec<StorageFinding>,
) -> Option<DirectoryUsage> {
    let path = parent.join(name);
    match fs::symlink_metadata(&path) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {
            match measure_directory(&path) {
                Ok(usage) => Some(usage),
                Err(error) => {
                    finding(
                        findings,
                        "storage.distro.scan.failed",
                        StorageFindingLevel::Error,
                        Some(distro_id),
                        &format!("Cannot scan {name}: {error}"),
                        Some(&path),
                    );
                    None
                }
            }
        }
        Ok(_) => {
            finding(
                findings,
                "storage.distro.path.unsafe",
                StorageFindingLevel::Error,
                Some(distro_id),
                &format!("{name} is not a real directory; it was not followed."),
                Some(&path),
            );
            None
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => None,
        Err(error) => {
            finding(
                findings,
                "storage.distro.path.unreadable",
                StorageFindingLevel::Error,
                Some(distro_id),
                &format!("Cannot inspect {name}: {error}"),
                Some(&path),
            );
            None
        }
    }
}

fn measure_directory(root: &Path) -> io::Result<DirectoryUsage> {
    let mut usage = DirectoryUsage {
        path: root.display().to_string(),
        bytes: 0,
        files: 0,
        directories: 0,
        symlinks: 0,
        writable_by_mode: writable_by_mode(&fs::metadata(root)?),
    };
    scan_directory(root, &mut usage)?;
    Ok(usage)
}

fn scan_directory(directory: &Path, usage: &mut DirectoryUsage) -> io::Result<()> {
    for entry in fs::read_dir(directory)? {
        let entry = entry?;
        let path: PathBuf = entry.path();
        let metadata = fs::symlink_metadata(&path)?;
        let kind = metadata.file_type();

        if kind.is_symlink() {
            usage.symlinks = usage.symlinks.saturating_add(1);
        } else if kind.is_dir() {
            usage.directories = usage.directories.saturating_add(1);
            scan_directory(&path, usage)?;
        } else if kind.is_file() {
            usage.files = usage.files.saturating_add(1);
            usage.bytes = usage.bytes.saturating_add(metadata.len());
        }
    }
    Ok(())
}

#[cfg(unix)]
fn writable_by_mode(metadata: &fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;
    metadata.permissions().mode() & 0o222 != 0
}

#[cfg(not(unix))]
fn writable_by_mode(metadata: &fs::Metadata) -> bool {
    !metadata.permissions().readonly()
}

fn finding(
    findings: &mut Vec<StorageFinding>,
    code: &str,
    level: StorageFindingLevel,
    distro_id: Option<&str>,
    message: &str,
    path: Option<&Path>,
) {
    findings.push(StorageFinding {
        code: code.to_owned(),
        level,
        distro_id: distro_id.map(str::to_owned),
        message: message.to_owned(),
        path: path.map(|value| value.display().to_string()),
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir() -> PathBuf {
        let id = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        std::env::temp_dir().join(format!("distroboxer-storage-analyzer-{id}"))
    }

    #[test]
    fn missing_storage_is_reported_without_creating_it() {
        let base = temp_dir();
        let report = analyze_storage(&StorageManager::new(&base));
        assert!(!report.storage_exists);
        assert!(!base.exists());
        assert!(report
            .findings
            .iter()
            .any(|item| item.code == "storage.base.missing"));
    }

    #[test]
    fn measures_distribution_files_and_bytes() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);
        let distro = manager.prepare_distro("alpine").unwrap();
        fs::write(distro.join("rootfs/sample.txt"), b"hello").unwrap();

        let report = analyze_storage(&manager);
        let alpine = report
            .distributions
            .iter()
            .find(|item| item.distro_id == "alpine")
            .unwrap();
        let rootfs = alpine.rootfs.as_ref().unwrap();
        assert_eq!(rootfs.bytes, 5);
        assert_eq!(rootfs.files, 1);
        assert!(report.total_bytes.is_some());
        assert!(report.available_bytes.is_some());

        fs::remove_dir_all(base).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn counts_but_does_not_follow_symlinks() {
        use std::os::unix::fs::symlink;
        let base = temp_dir();
        let outside = temp_dir();
        fs::create_dir_all(&outside).unwrap();
        fs::write(outside.join("secret"), b"outside").unwrap();
        let manager = StorageManager::new(&base);
        let distro = manager.prepare_distro("alpine").unwrap();
        symlink(&outside, distro.join("rootfs/external")).unwrap();

        let report = analyze_storage(&manager);
        let alpine = report
            .distributions
            .iter()
            .find(|item| item.distro_id == "alpine")
            .unwrap();
        let rootfs = alpine.rootfs.as_ref().unwrap();
        assert_eq!(rootfs.symlinks, 1);
        assert_eq!(rootfs.bytes, 0);

        fs::remove_dir_all(base).unwrap();
        fs::remove_dir_all(outside).unwrap();
    }
}
