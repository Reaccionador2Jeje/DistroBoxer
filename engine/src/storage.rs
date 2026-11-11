//! App-private storage management for Linux distributions.

use crate::metadata::{DistroMetadata, InstallStatus};
use crate::{distro_path, find_distro, is_supported};
use crate::{installer::extract_tar_gz, verify_sha256};
use std::fs::{self, OpenOptions};
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

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
            let metadata = DistroMetadata::new(distro.id, distro.name, distro.package_manager);
            self.write_metadata(id, &metadata)?;
        }

        Ok(distro_dir)
    }

    /// Reads persisted metadata. Missing metadata is not an error.
    pub fn read_metadata(&self, id: &str) -> io::Result<Option<DistroMetadata>> {
        let distro_dir = self.distro_dir(id)?;
        match fs::symlink_metadata(&distro_dir) {
            Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {}
            Ok(_) => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "distribution storage must be a real directory",
                ));
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error),
        }

        let path = distro_dir.join("metadata.json");
        match fs::symlink_metadata(&path) {
            Ok(file_metadata) if file_metadata.file_type().is_symlink() => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "distribution metadata must not be a symbolic link",
                ));
            }
            Ok(_) => {}
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(None),
            Err(error) => return Err(error),
        }

        let contents = fs::read_to_string(path)?;
        let metadata: DistroMetadata = serde_json::from_str(&contents)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;

        if metadata.distro_id != id || metadata.schema_version != 1 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "metadata ID or schema version is invalid",
            ));
        }

        Ok(Some(metadata))
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
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let temporary = distro_dir.join(format!(".metadata.{}.{}.tmp", std::process::id(), nonce));
        let contents = serde_json::to_vec_pretty(metadata)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, error))?;

        let write_result = (|| {
            let mut file = OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&temporary)?;
            file.write_all(&contents)?;
            file.sync_all()
        })();

        if let Err(error) = write_result {
            let _ = fs::remove_file(&temporary);
            return Err(error);
        }

        if let Err(error) = fs::rename(&temporary, &destination) {
            let _ = fs::remove_file(&temporary);
            return Err(error);
        }

        Ok(())
    }

    /// Applies a validated installation lifecycle transition.
    pub fn transition_status(
        &self,
        id: &str,
        status: InstallStatus,
        last_error: Option<String>,
    ) -> io::Result<()> {
        self.prepare_distro(id)?;
        let mut metadata = self
            .read_metadata(id)?
            .expect("prepare_distro creates metadata");

        if !metadata.status.can_transition_to(status) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "invalid distribution installation state transition",
            ));
        }

        metadata.status = status;
        metadata.last_error = last_error;
        self.write_metadata(id, &metadata)
    }

    /// Sets a lifecycle state directly for recovery and administrative repair.
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

    /// Installs a previously downloaded gzip-compressed rootfs tarball.
    ///
    /// The archive must be inside this distribution's private downloads directory
    /// and match the trusted SHA-256 supplied by the caller.
    pub fn install_tar_gz(
        &self,
        id: &str,
        archive_path: impl AsRef<Path>,
        expected_sha256: &str,
    ) -> io::Result<()> {
        let distro_dir = self.prepare_distro(id)?;
        let downloads_dir = fs::canonicalize(distro_dir.join("downloads"))?;
        let archive_path = fs::canonicalize(archive_path)?;
        if !archive_path.starts_with(&downloads_dir) {
            return Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "archive must be inside the distribution's private downloads directory",
            ));
        }
        if !verify_sha256(&archive_path, expected_sha256)? {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "root filesystem archive SHA-256 verification failed",
            ));
        }

        self.set_status(id, InstallStatus::NotInstalled, None)?;
        self.transition_status(id, InstallStatus::Preparing, None)?;
        self.transition_status(id, InstallStatus::Downloading, None)?;
        self.transition_status(id, InstallStatus::Extracting, None)?;

        let staging = distro_dir.join(".rootfs-staging");
        let backup = distro_dir.join(".rootfs-backup");

        let result = (|| {
            remove_real_directory_if_present(&staging)?;
            if fs::symlink_metadata(&backup).is_ok() {
                return Err(io::Error::new(
                    io::ErrorKind::AlreadyExists,
                    "rootfs backup exists; manual recovery is required",
                ));
            }

            fs::create_dir(&staging)?;
            extract_tar_gz(&archive_path, &staging)?;
            validate_rootfs_release_file(&staging)?;

            let rootfs = distro_dir.join("rootfs");
            if fs::symlink_metadata(&rootfs).is_ok() {
                ensure_real_directory(&rootfs)?;
                fs::rename(&rootfs, &backup)?;
            }

            if let Err(error) = fs::rename(&staging, &rootfs) {
                if fs::symlink_metadata(&backup).is_ok() {
                    let _ = fs::rename(&backup, &rootfs);
                }
                return Err(error);
            }

            remove_real_directory_if_present(&backup)?;
            Ok(())
        })();

        match result {
            Ok(()) => self.transition_status(id, InstallStatus::Installed, None),
            Err(error) => {
                let _ = remove_real_directory_if_present(&staging);
                let _ = self.set_status(id, InstallStatus::Failed, Some(error.to_string()));
                Err(error)
            }
        }
    }

    /// Checks for a real rootfs directory, rejecting a symlink at that location.
    pub fn is_installed(&self, id: &str) -> io::Result<bool> {
        let distro_dir = self.distro_dir(id)?;
        let rootfs = distro_dir.join("rootfs");
        let etc_dir = rootfs.join("etc");
        let directories = [
            self.base_dir.clone(),
            self.base_dir.join("distros"),
            distro_dir,
            rootfs.clone(),
            etc_dir.clone(),
        ];

        for directory in &directories {
            match fs::symlink_metadata(directory) {
                Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {}
                Ok(_) => return Ok(false),
                Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(false),
                Err(error) => return Err(error),
            }
        }

        let release_file = etc_dir.join("os-release");
        match fs::symlink_metadata(&release_file) {
            Ok(metadata) => Ok(metadata.is_file() && !metadata.file_type().is_symlink()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(false),
            Err(error) => Err(error),
        }
    }
}

fn validate_rootfs_release_file(rootfs: &Path) -> io::Result<()> {
    for path in [rootfs.to_path_buf(), rootfs.join("etc")] {
        let metadata = fs::symlink_metadata(path)?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "root filesystem contains an invalid etc directory",
            ));
        }
    }

    let release = rootfs.join("etc/os-release");
    let metadata = fs::symlink_metadata(release)?;
    if !metadata.is_file() || metadata.file_type().is_symlink() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "root filesystem is missing a regular etc/os-release file",
        ));
    }
    Ok(())
}

fn remove_real_directory_if_present(path: &Path) -> io::Result<()> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() && !metadata.file_type().is_symlink() => {
            fs::remove_dir_all(path)
        }
        Ok(_) => Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "refusing to remove a non-directory or symbolic link",
        )),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
        Err(error) => Err(error),
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
    fn installs_a_verified_gzip_rootfs_archive() {
        use flate2::write::GzEncoder;
        use flate2::Compression;
        use tar::{Builder, Header};

        let base = temp_dir();
        let manager = StorageManager::new(&base);
        let distro_dir = manager.prepare_distro("alpine").unwrap();
        let archive_path = distro_dir.join("downloads/rootfs.tar.gz");

        let file = fs::File::create(&archive_path).unwrap();
        let encoder = GzEncoder::new(file, Compression::default());
        let mut builder = Builder::new(encoder);
        let contents = b"ID=alpine\n";
        let mut header = Header::new_gnu();
        header.set_size(contents.len() as u64);
        header.set_mode(0o644);
        header.set_cksum();
        builder
            .append_data(&mut header, "etc/os-release", &contents[..])
            .unwrap();
        builder.into_inner().unwrap().finish().unwrap();

        let digest = crate::sha256_file(&archive_path).unwrap();
        manager
            .install_tar_gz("alpine", &archive_path, &digest)
            .unwrap();

        assert!(manager.is_installed("alpine").unwrap());
        let metadata = manager.read_metadata("alpine").unwrap().unwrap();
        assert_eq!(metadata.status, InstallStatus::Installed);
        assert_eq!(
            fs::read_to_string(distro_dir.join("rootfs/etc/os-release")).unwrap(),
            "ID=alpine\n"
        );

        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn rejects_gzip_rootfs_with_wrong_digest() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);
        let distro_dir = manager.prepare_distro("alpine").unwrap();
        let archive_path = distro_dir.join("downloads/empty.tar.gz");
        fs::write(&archive_path, b"not a real archive").unwrap();

        assert_eq!(
            manager
                .install_tar_gz("alpine", &archive_path, &"0".repeat(64))
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );

        assert!(!manager.is_installed("alpine").unwrap());
        fs::remove_dir_all(base).unwrap();
    }

    #[test]
    fn validates_installation_state_transitions() {
        let base = temp_dir();
        let manager = StorageManager::new(&base);

        manager
            .transition_status("debian", InstallStatus::Preparing, None)
            .unwrap();
        manager
            .transition_status("debian", InstallStatus::Downloading, None)
            .unwrap();

        assert_eq!(
            manager
                .transition_status("debian", InstallStatus::Installed, None)
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidInput
        );

        manager
            .transition_status("debian", InstallStatus::Extracting, None)
            .unwrap();
        manager
            .transition_status("debian", InstallStatus::Installed, None)
            .unwrap();

        let metadata = manager.read_metadata("debian").unwrap().unwrap();
        assert_eq!(metadata.status, InstallStatus::Installed);

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
    fn rejects_symlinked_rootfs_directory() {
        use std::os::unix::fs::symlink;

        let base = temp_dir();
        let outside = temp_dir();
        fs::create_dir_all(base.join("distros/debian")).unwrap();
        fs::create_dir_all(&outside).unwrap();
        symlink(&outside, base.join("distros/debian/rootfs")).unwrap();

        let manager = StorageManager::new(&base);
        assert!(!manager.is_installed("debian").unwrap());

        fs::remove_dir_all(base).unwrap();
        fs::remove_dir_all(outside).unwrap();
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
