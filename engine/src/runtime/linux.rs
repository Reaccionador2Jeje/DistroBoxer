//! Native Linux PRoot backend.

use super::{RuntimeBackend, RuntimeKind, RuntimeState, RuntimeStatus};
use crate::StorageManager;
use std::ffi::OsString;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

/// Executes an installed Linux root filesystem through the host PRoot binary.
#[derive(Debug, Clone)]
pub struct LinuxProotBackend {
    proot_path: PathBuf,
    storage: StorageManager,
}

impl LinuxProotBackend {
    /// Finds PRoot in PATH and binds the backend to the app's storage directory.
    pub fn discover(storage_base: impl Into<PathBuf>) -> io::Result<Self> {
        let proot_path = find_proot_executable()?;
        Ok(Self {
            proot_path,
            storage: StorageManager::new(storage_base),
        })
    }

    /// Creates a backend with an explicit PRoot executable path.
    pub fn with_executable(
        proot_path: impl Into<PathBuf>,
        storage_base: impl Into<PathBuf>,
    ) -> io::Result<Self> {
        let proot_path = proot_path.into();
        validate_executable(&proot_path)?;
        Ok(Self {
            proot_path,
            storage: StorageManager::new(storage_base),
        })
    }

    /// Returns the PRoot executable selected by this backend.
    pub fn proot_path(&self) -> &Path {
        &self.proot_path
    }

    /// Builds and starts an interactive login shell inside an installed distro.
    ///
    /// Standard input, output, and error are inherited from the parent process,
    /// so desktop frontends can attach a terminal or PTY around this child.
    pub fn launch(&self, distro_id: &str) -> io::Result<Child> {
        let rootfs = self.validated_rootfs(distro_id)?;
        let mut command = Command::new(&self.proot_path);

        command
            .arg("-0")
            .arg("-r")
            .arg(&rootfs)
            .arg("-w")
            .arg("/root")
            .arg("/bin/sh")
            .arg("-l")
            .env_clear()
            .env("HOME", "/root")
            .env("USER", "root")
            .env("LOGNAME", "root")
            .env(
                "PATH",
                "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            )
            .env(
                "TERM",
                std::env::var_os("TERM").unwrap_or_else(|| OsString::from("xterm-256color")),
            )
            .stdin(Stdio::inherit())
            .stdout(Stdio::inherit())
            .stderr(Stdio::inherit());

        command.spawn()
    }

    fn validated_rootfs(&self, distro_id: &str) -> io::Result<PathBuf> {
        if !self.storage.is_installed(distro_id)? {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("distribution '{distro_id}' is not installed or its rootfs is invalid"),
            ));
        }

        let distro_dir = self.storage.distro_dir(distro_id)?;
        let rootfs = distro_dir.join("rootfs");
        let metadata = fs::symlink_metadata(&rootfs)?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "distribution rootfs must be a real directory",
            ));
        }

        let rootfs = fs::canonicalize(rootfs)?;
        let shell = rootfs.join("bin/sh");
        match fs::symlink_metadata(&shell) {
            Ok(metadata) if metadata.is_file() || metadata.file_type().is_symlink() => {}
            _ => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "distribution rootfs does not contain /bin/sh",
                ));
            }
        }

        Ok(rootfs)
    }
}

impl RuntimeBackend for LinuxProotBackend {
    fn kind(&self) -> RuntimeKind {
        RuntimeKind::LinuxProot
    }

    fn status(&self) -> RuntimeStatus {
        RuntimeStatus {
            kind: RuntimeKind::LinuxProot,
            state: RuntimeState::AvailableForDetection,
            message: "Linux PRoot executable validated; a distribution rootfs is checked when launching",
        }
    }
}

/// Finds an executable named proot in PATH.
pub fn find_proot_executable() -> io::Result<PathBuf> {
    let path = std::env::var_os("PATH").unwrap_or_default();
    for directory in std::env::split_paths(&path) {
        let candidate = directory.join("proot");
        if validate_executable(&candidate).is_ok() {
            return fs::canonicalize(candidate);
        }
    }

    Err(io::Error::new(
        io::ErrorKind::NotFound,
        "PRoot was not found in PATH; install the proot package on Linux",
    ))
}

fn validate_executable(path: &Path) -> io::Result<()> {
    let metadata = fs::metadata(path)?;
    if !metadata.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "PRoot path must point to a regular executable file",
        ));
    }

    use std::os::unix::fs::PermissionsExt;
    if metadata.permissions().mode() & 0o111 == 0 {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "PRoot file does not have executable permission",
        ));
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_dir() -> PathBuf {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock should be valid")
            .as_nanos();
        std::env::temp_dir().join(format!("distroboxer-proot-{nonce}"))
    }

    #[test]
    fn rejects_non_executable_files() {
        let directory = temp_dir();
        fs::create_dir_all(&directory).unwrap();
        let executable = directory.join("proot");
        fs::write(&executable, "#!/bin/sh\nexit 0\n").unwrap();

        assert_eq!(
            validate_executable(&executable).unwrap_err().kind(),
            io::ErrorKind::PermissionDenied
        );

        fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn rejects_unknown_distribution_before_launch() {
        let directory = temp_dir();
        fs::create_dir_all(&directory).unwrap();
        let executable = directory.join("proot");
        fs::write(&executable, "#!/bin/sh\nexit 0\n").unwrap();

        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&executable, fs::Permissions::from_mode(0o755)).unwrap();

        let backend = LinuxProotBackend::with_executable(&executable, directory.join("data"))
            .unwrap();
        assert_eq!(
            backend.launch("../outside").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );

        fs::remove_dir_all(directory).unwrap();
    }
}
