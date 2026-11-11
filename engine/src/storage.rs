//! App-private storage management for Linux distributions.

use crate::{distro_path, find_distro, is_supported};
use crate::metadata::{DistroMetadata, InstallStatus};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};

/// Manages per-distribution directories beneath an application-private base.
#[derive(Debug, Clone)]
pub struct StorageManager {
    base_dir: PathBuf,
}

impl StorageManager {
    /// Creates a manager rooted at the app-private files directory.
    pub fn new(base_dir: impl Into<PathBuf>) -> Self {
        Self {
            base_dir: base_dir.into(),
        }
    }

    /// Returns the configured storage base directory.
    pub fn base_dir(&self) -> &Path {
        &self.base_dir
    }

    /// Creates the common storage layout if it does not already exist.
    pub fn initialize(&self) -> io::Result<()> {
        ensure_real_directory(&self.base_dir)?;
        ensure_real_directory(&self.base_dir.join("distros"))?;
        Ok(())
    }

    /// Returns the directory assigned to a supported distribution.
    pub fn distro_dir(&self, id: &str) -> io::Result<PathBuf> {
        distro_path(&self.base_dir, id)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "unsupported distribution"))
    }

    /// Prepares an isolated directory layout without downloading or executing anything.
    pub fn prepare_distro(&self, id: &str) -> io::Result<PathBuf> {
        self.initialize()?;

        let distro_dir = self.distro_dir(id)?;
        ensure_real_directory(&distro_dir)?;
        ensure_real_directory(&distro_dir.join("rootfs"))?;
        ensure_real_directory(&distro_dir.join("downloads"))?;

        if self.read_metadata(id)?.is_none() {
            let distro = find_distro(id).expect("validated distribution must exist");
            let metadata = DistroMetadata::new(
                distro.id,
                distro.name,
                distro.package_manager,
            );
            self.write_metadata(id, &metadata)?;
        }

        Ok(distro_dir)
    }

    /// Reads persisted metadata. Missing metadata is not an error.
    pub fn read_metadata(&self, id: &str) -> io::Result<Option<DistroMetadata>> {
        let path = self.distro_dir(id)?.join("metadata.json");
        match fs::read_to_string(path) {
            Ok(contents) => {
                let metadata = serde_json::from_str(&contents)
                    .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;
                Ok(Some(metadata))
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(None),
            Err(error) => Err(error),
        }
    }

    /// Writes metadata atomically using a temporary file in the same directory.
    pub fn write_metadata(&self, id: &str, metadata: &DistroMetadata) -> io::Result<()> {
        if !is_supported(id) || metadata.distro_id != id {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "metadata distribution ID does not match a supported distribution",
            ));
        }

        let distro_dir = self.distro_dir(id)?;
        ensure_real_directory(&distro_dir)?;

        let destination = distro_dir.join("metadata.json");
        let temporary = distro_dir.join(format!(".metadata.{}.tmp", std::process::id()));
        let contents = serde_json::to_vec_pretty(metadata)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;

        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&temporary)?;
        file.write_all(&contents)?;
        file.sync_all()?;
        drop(file);

        if let Err(error) = fs::rename(&temporary, &destination) {
            let _ = fs::remove_file(&temporary);
            return Err(error);
        }

        Ok(())
    }

    /// Updates a distribution's lifecycle state and optional error message.
    pub fn set_status(
        &self,
        id: &str,
        status: InstallStatus,
        last_error: Option<String>,
    ) -> io::Result<()> {
        self.prepare_distro(id)?;
        let mut metadata = self
            .read_metadata(id)?
            .expect("prepare_distro creates metadata");
        metadata.status = status;
        metadata.last_error = last_error;
        self.write_metadata(id, &metadata)
    }

    /// Checks for a real rootfs directory, rejecting a symlink at that location.
    pub fn is_installed(&self, id: &str) -> io::Result<bool> {
        let rootfs = self.distro_dir(id)?.join("rootfs");
        match fs::symlink_metadata(rootfs) {
            Ok(metadata) => Ok(metadata.is_dir() && !metadata.file_type().is_symlink()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(error) => Err(error),
        }
    }
}

fn ensure_real_directory(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => Ok(()),
        Ok(_) => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("expected a real directory: {}", path.display()),
        )),
        Err(error) if error.kind() == io::ErrorKind::NotFound => {
            fs::create_dir(path)?;
            Ok(())
        }
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir() -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("clock should be valid")
            .as_nanos();
        std::env::temp_dir().join(format!("distroboxer-storage-{nonce}"))
    }

    #[test]
    fn prepares_isolated_directory_layout() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);

        let distro_dir = manager.prepare_distro("debian").unwrap();

        assert!(distro_dir.join("rootfs").is_dir());
        assert!(distro_dir.join("downloads").is_dir());
        assert!(distro_dir.join("metadata.json").is_file());
        assert!(!manager.is_installed("debian").unwrap());

        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn persists_lifecycle_state() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);

        manager
            .set_status("debian", InstallStatus::Failed, Some("test failure".into()))
            .unwrap();

        let metadata = manager.read_metadata("debian").unwrap().unwrap();
        assert_eq!(metadata.status, InstallStatus::Failed);
        assert_eq!(metadata.last_error.as_deref(), Some("test failure"));

        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn rejects_unknown_distribution() {
        let manager = StorageManager::new(temp_dir());
        assert_eq!(
            manager.prepare_distro("../outside").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
    }

    #[cfg(unix)]
    #[test]
    fn rejects_symlinked_distribution_directory() {
        use std::os::unix::fs::symlink;

        let base = temp_dir();
        fs::create_dir_all(base.join("distros")).unwrap();
        let outside = temp_dir();
        fs::create_dir_all(&outside).unwrap();
        symlink(&outside, base.join("distros/debian")).unwrap();

        let manager = StorageManager::new(&base);
        assert_eq!(
            manager.prepare_distro("debian").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );

        fs::remove_dir_all(base).unwrap();
        fs::remove_dir_all(outside).unwrap();
    }
}
