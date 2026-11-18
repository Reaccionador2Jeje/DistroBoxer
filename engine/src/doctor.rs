//! Read-only diagnostics for DistroBoxer installations and runtime storage.
//!
//! The doctor never repairs, downloads, extracts, or executes guest binaries.
//! It only inspects app-private files and persisted metadata.

use crate::{find_distro, supported_distros, InstallStatus, StorageManager};
use serde::Serialize;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

/// Severity assigned to a diagnostic finding.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiagnosticLevel {
    Info,
    Warning,
    Error,
}

/// One stable, machine-readable diagnostic finding.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Diagnostic {
    pub code: String,
    pub level: DiagnosticLevel,
    pub distro_id: Option<String>,
    pub message: String,
    pub path: Option<String>,
}

/// Read-only result from a DistroBoxer Doctor scan.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DoctorReport {
    pub schema_version: u32,
    pub healthy: bool,
    pub checked_distributions: usize,
    pub diagnostics: Vec<Diagnostic>,
}

impl DoctorReport {
    fn new(checked_distributions: usize, diagnostics: Vec<Diagnostic>) -> Self {
        let healthy = !diagnostics
            .iter()
            .any(|item| item.level == DiagnosticLevel::Error);
        Self {
            schema_version: 1,
            healthy,
            checked_distributions,
            diagnostics,
        }
    }

    /// Number of findings at the requested severity.
    pub fn count(&self, level: DiagnosticLevel) -> usize {
        self.diagnostics.iter().filter(|item| item.level == level).count()
    }
}

/// Runs a non-mutating diagnostic scan over the storage manager and known distros.
pub fn run_doctor(storage: &StorageManager) -> DoctorReport {
    let mut diagnostics = Vec::new();
    let base = storage.base_dir();

    match fs::symlink_metadata(base) {
        Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {
            push(&mut diagnostics, "storage.base.ok", DiagnosticLevel::Info, None,
                "App-private storage directory is accessible.", Some(base));
        }
        Ok(_) => push(&mut diagnostics, "storage.base.invalid", DiagnosticLevel::Error, None,
            "Storage base must be a real directory, not a file or symbolic link.", Some(base)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            push(&mut diagnostics, "storage.base.missing", DiagnosticLevel::Warning, None,
                "Storage directory has not been created yet.", Some(base));
        }
        Err(error) => push(&mut diagnostics, "storage.base.unreadable", DiagnosticLevel::Error, None,
            &format!("Cannot inspect storage directory: {error}"), Some(base)),
    }

    let distros_dir = base.join("distros");
    match fs::symlink_metadata(&distros_dir) {
        Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => {}
        Ok(_) => push(&mut diagnostics, "storage.distros.invalid", DiagnosticLevel::Error, None,
            "The distros directory is not a safe real directory.", Some(&distros_dir)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            push(&mut diagnostics, "storage.distros.missing", DiagnosticLevel::Warning, None,
                "No distribution storage has been initialized yet.", Some(&distros_dir));
        }
        Err(error) => push(&mut diagnostics, "storage.distros.unreadable", DiagnosticLevel::Error, None,
            &format!("Cannot inspect distributions directory: {error}"), Some(&distros_dir)),
    }

    for distro in supported_distros() {
        inspect_distro(storage, &distro.id, &mut diagnostics);
    }

    DoctorReport::new(supported_distros().len(), diagnostics)
}

/// Runs diagnostics for one known distribution identifier.
pub fn run_distro_doctor(storage: &StorageManager, distro_id: &str) -> io::Result<DoctorReport> {
    if find_distro(distro_id).is_none() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "unsupported distribution"));
    }
    let mut diagnostics = Vec::new();
    inspect_distro(storage, distro_id, &mut diagnostics);
    Ok(DoctorReport::new(1, diagnostics))
}

fn inspect_distro(storage: &StorageManager, id: &str, diagnostics: &mut Vec<Diagnostic>) {
    let distro_dir = match storage.distro_dir(id) {
        Ok(path) => path,
        Err(error) => {
            push(diagnostics, "distro.path.invalid", DiagnosticLevel::Error, Some(id),
                &error.to_string(), None);
            return;
        }
    };

    let metadata = match storage.read_metadata(id) {
        Ok(Some(value)) => {
            push(diagnostics, "distro.metadata.ok", DiagnosticLevel::Info, Some(id),
                "Distribution metadata is readable and valid.", Some(&distro_dir.join("metadata.json")));
            Some(value)
        }
        Ok(None) => {
            push(diagnostics, "distro.not_initialized", DiagnosticLevel::Info, Some(id),
                "Distribution has no stored metadata; it has not been initialized.", Some(&distro_dir));
            None
        }
        Err(error) => {
            push(diagnostics, "distro.metadata.invalid", DiagnosticLevel::Error, Some(id),
                &format!("Distribution metadata cannot be trusted: {error}"), Some(&distro_dir.join("metadata.json")));
            None
        }
    };

    let rootfs = distro_dir.join("rootfs");
    let rootfs_meta = match fs::symlink_metadata(&rootfs) {
        Ok(meta) if meta.is_dir() && !meta.file_type().is_symlink() => Some(meta),
        Ok(_) => {
            push(diagnostics, "distro.rootfs.invalid", DiagnosticLevel::Error, Some(id),
                "Root filesystem path is not a real directory.", Some(&rootfs));
            None
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            if metadata.as_ref().is_some_and(|m| m.status == InstallStatus::Installed) {
                push(diagnostics, "distro.rootfs.missing", DiagnosticLevel::Error, Some(id),
                    "Metadata says installed, but the root filesystem directory is missing.", Some(&rootfs));
            }
            None
        }
        Err(error) => {
            push(diagnostics, "distro.rootfs.unreadable", DiagnosticLevel::Error, Some(id),
                &format!("Cannot inspect root filesystem: {error}"), Some(&rootfs));
            None
        }
    };

    let Some(_rootfs_meta) = rootfs_meta else {
        if let Some(meta) = metadata {
            if meta.status == InstallStatus::Installed {
                push(diagnostics, "distro.state.inconsistent", DiagnosticLevel::Error, Some(id),
                    "Installation state is installed but no valid root filesystem is available.", Some(&distro_dir));
            } else if meta.status == InstallStatus::Failed {
                push(diagnostics, "distro.install.failed", DiagnosticLevel::Warning, Some(id),
                    meta.last_error.as_deref().unwrap_or("Previous installation failed; no error detail was saved."),
                    Some(&distro_dir.join("metadata.json")));
            }
        }
        return;
    };

    let release = rootfs.join("etc/os-release");
    let release_meta = match fs::symlink_metadata(&release) {
        Ok(meta) if meta.is_file() || meta.file_type().is_symlink() => meta,
        Ok(_) => {
            push(diagnostics, "distro.os_release.invalid", DiagnosticLevel::Error, Some(id),
                "etc/os-release is not a regular file or supported symlink.", Some(&release));
            return;
        }
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            push(diagnostics, "distro.os_release.missing", DiagnosticLevel::Error, Some(id),
                "Root filesystem is missing etc/os-release.", Some(&release));
            return;
        }
        Err(error) => {
            push(diagnostics, "distro.os_release.unreadable", DiagnosticLevel::Error, Some(id),
                &format!("Cannot inspect os-release: {error}"), Some(&release));
            return;
        }
    };
    let _ = release_meta;

    let canonical_root = match fs::canonicalize(&rootfs) {
        Ok(path) => path,
        Err(error) => {
            push(diagnostics, "distro.rootfs.resolve_failed", DiagnosticLevel::Error, Some(id),
                &format!("Cannot resolve root filesystem path: {error}"), Some(&rootfs));
            return;
        }
    };
    let canonical_release = match fs::canonicalize(&release) {
        Ok(path) if path.starts_with(&canonical_root) => path,
        Ok(_) => {
            push(diagnostics, "distro.os_release.escape", DiagnosticLevel::Error, Some(id),
                "os-release resolves outside the distribution root filesystem.", Some(&release));
            return;
        }
        Err(error) => {
            push(diagnostics, "distro.os_release.resolve_failed", DiagnosticLevel::Error, Some(id),
                &format!("Cannot resolve os-release: {error}"), Some(&release));
            return;
        }
    };

    let contents = match fs::read_to_string(&canonical_release) {
        Ok(value) => value,
        Err(error) => {
            push(diagnostics, "distro.os_release.unreadable", DiagnosticLevel::Error, Some(id),
                &format!("Cannot read os-release: {error}"), Some(&canonical_release));
            return;
        }
    };
    let actual_id = contents.lines().find_map(|line| {
        let (key, value) = line.split_once('=')?;
        (key.trim() == "ID").then(|| value.trim().trim_matches('"').trim_matches('\'').to_ascii_lowercase())
    });
    if actual_id.as_deref() != Some(id) {
        push(diagnostics, "distro.identity.mismatch", DiagnosticLevel::Error, Some(id),
            &format!("Expected os-release ID '{id}', found '{}'.", actual_id.as_deref().unwrap_or("missing")),
            Some(&canonical_release));
    } else {
        push(diagnostics, "distro.rootfs.ok", DiagnosticLevel::Info, Some(id),
            "Root filesystem and distribution identity checks passed.", Some(&rootfs));
    }

    let shell = ["bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash"]
        .iter()
        .map(|relative| rootfs.join(relative))
        .find(|path| path.exists());
    if shell.is_none() {
        push(diagnostics, "distro.shell.missing", DiagnosticLevel::Warning, Some(id),
            "No common shell path was found; interactive startup may fail.", Some(&rootfs));
    }
    if let Some(meta) = metadata {
        match meta.status {
            InstallStatus::Installed => {}
            InstallStatus::Failed => push(diagnostics, "distro.state.failed", DiagnosticLevel::Warning, Some(id),
                meta.last_error.as_deref().unwrap_or("Metadata records a failed installation."), Some(&distro_dir)),
            _ => push(diagnostics, "distro.state.not_installed", DiagnosticLevel::Warning, Some(id),
                "A root filesystem exists, but metadata does not mark the distribution as installed.", Some(&distro_dir)),
        }
    }
}

fn push(
    diagnostics: &mut Vec<Diagnostic>,
    code: &str,
    level: DiagnosticLevel,
    distro_id: Option<&str>,
    message: &str,
    path: Option<&Path>,
) {
    diagnostics.push(Diagnostic {
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
        let nonce = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        std::env::temp_dir().join(format!("distroboxer-doctor-{nonce}"))
    }

    #[test]
    fn empty_storage_is_reported_without_creating_files() {
        let base = temp_dir();
        let report = run_doctor(&StorageManager::new(&base));
        assert!(!report.diagnostics.is_empty());
        assert!(!base.exists(), "doctor must be read-only");
    }

    #[test]
    fn detects_installed_metadata_without_rootfs() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);
        manager.prepare_distro("alpine").unwrap();
        manager.set_status("alpine", InstallStatus::Installed, None).unwrap();

        let report = run_distro_doctor(&manager, "alpine").unwrap();
        assert!(report.diagnostics.iter().any(|item| item.code == "distro.rootfs.missing"));
        assert!(!report.healthy);
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn rejects_unknown_distribution() {
        let base = temp_dir();
        let result = run_distro_doctor(&StorageManager::new(&base), "../outside");
        assert_eq!(result.unwrap_err().kind(), io::ErrorKind::InvalidInput);
        assert!(!base.exists());
    }

    #[test]
    fn detects_wrong_distribution_identity() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);
        let rootfs = manager.prepare_distro("alpine").unwrap().join("rootfs");
        fs::create_dir_all(rootfs.join("etc")).unwrap();
        fs::write(rootfs.join("etc/os-release"), "ID=debian\n").unwrap();
        manager.set_status("alpine", InstallStatus::Installed, None).unwrap();

        let report = run_distro_doctor(&manager);
        assert!(report.diagnostics.iter().any(|item| item.code == "distro.identity.mismatch"));
        assert!(!report.healthy);
        fs::remove_dir_all(base).unwrap();
    }
}
