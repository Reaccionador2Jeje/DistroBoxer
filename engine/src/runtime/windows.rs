//! Windows Subsystem for Linux 2 runtime backend.

use super::{RuntimeBackend, RuntimeKind, RuntimeState, RuntimeStatus};
use crate::find_distro;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};

/// Starts DistroBoxer-managed Linux distributions registered in WSL 2.
///
/// WSL distributions are registered independently from DistroBoxer's portable
/// rootfs storage. This backend detects WSL and launches an already registered
/// instance; import/export and lifecycle management are separate milestones.
#[derive(Debug, Clone)]
pub struct WindowsWsl2Backend {
    wsl_path: PathBuf,
}

impl WindowsWsl2Backend {
    /// Finds wsl.exe in PATH or the Windows system directory.
    pub fn discover() -> io::Result<Self> {
        Ok(Self {
            wsl_path: find_wsl_executable()?,
        })
    }

    /// Creates a backend using an explicit wsl.exe path.
    pub fn with_executable(path: impl Into<PathBuf>) -> io::Result<Self> {
        let path = path.into();
        validate_executable(&path)?;
        Ok(Self { wsl_path: path })
    }

    /// Returns the selected WSL executable.
    pub fn executable(&self) -> &Path {
        &self.wsl_path
    }

    /// Checks whether WSL responds to its status command.
    pub fn is_available(&self) -> bool {
        Command::new(&self.wsl_path)
            .arg("--status")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .map(|status| status.success())
            .unwrap_or(false)
    }

    /// Lists registered DistroBoxer distributions.
    pub fn registered_distributions(&self) -> io::Result<Vec<String>> {
        let output = Command::new(&self.wsl_path)
            .args(["--list", "--quiet"])
            .output()?;
        if !output.status.success() {
            return Err(io::Error::other(
                "WSL could not list registered distributions",
            ));
        }

        // WSL commonly emits UTF-16LE when stdout is redirected.
        let utf16: Vec<u16> = output
            .stdout
            .chunks_exact(2)
            .map(|pair| u16::from_le_bytes([pair[0], pair[1]]))
            .collect();
        let listing = String::from_utf16_lossy(&utf16);
        let mut result = Vec::new();
        for line in listing.trim_start_matches('\u{feff}').lines() {
            let name = line.trim().trim_start_matches('*').trim();
            if let Some(id) = name.strip_prefix("DistroBoxer-") {
                if find_distro(id).is_some() {
                    result.push(id.to_owned());
                }
            }
        }
        result.sort();
        result.dedup();
        Ok(result)
    }

    /// Imports a verified rootfs tar archive as a dedicated WSL 2 distribution.
    ///
    /// The destination must be an empty directory. Existing distributions are
    /// never unregistered or overwritten automatically.
    pub fn import_rootfs_tarball(
        &self,
        distro_id: &str,
        install_location: impl AsRef<Path>,
        tarball: impl AsRef<Path>,
    ) -> io::Result<()> {
        let name = registered_name(distro_id)?;
        if !self.is_available() {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                "WSL2 is not available; initialize WSL before importing a distribution",
            ));
        }
        if self
            .registered_distributions()?
            .iter()
            .any(|id| id == distro_id)
        {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                format!("WSL distribution '{name}' is already registered"),
            ));
        }

        let tarball = tarball.as_ref();
        let archive_metadata = fs::metadata(tarball)?;
        if !archive_metadata.is_file() || archive_metadata.len() == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "WSL import requires a non-empty regular tar archive",
            ));
        }

        let install_location = install_location.as_ref();
        fs::create_dir_all(install_location)?;
        if fs::read_dir(install_location)?.next().is_some() {
            return Err(io::Error::new(
                io::ErrorKind::AlreadyExists,
                "WSL installation directory must be empty",
            ));
        }

        let output = Command::new(&self.wsl_path)
            .arg("--import")
            .arg(&name)
            .arg(install_location)
            .arg(tarball)
            .arg("--version")
            .arg("2")
            .output()?;
        if !output.status.success() {
            let detail = String::from_utf8_lossy(&output.stderr).trim().to_owned();
            return Err(io::Error::other(if detail.is_empty() {
                "WSL failed to import the Linux root filesystem".to_owned()
            } else {
                detail
            }));
        }

        if !self
            .registered_distributions()?
            .iter()
            .any(|id| id == distro_id)
        {
            return Err(io::Error::new(
                io::ErrorKind::Other,
                "WSL import returned success but the distribution was not registered",
            ));
        }
        Ok(())
    }

    /// Starts a login shell in an already imported DistroBoxer WSL distribution.
    ///
    /// The distribution must be registered as DistroBoxer-<catalog-id>.
    /// No shell command is concatenated or evaluated by the Windows host.
    pub fn launch(&self, distro_id: &str) -> io::Result<Child> {
        let name = registered_name(distro_id)?;
        let registered = self.registered_distributions()?;
        if !registered.iter().any(|id| id == distro_id) {
            return Err(io::Error::new(
                io::ErrorKind::NotFound,
                format!("WSL distribution '{name}' is not registered"),
            ));
        }

        Command::new(&self.wsl_path)
            .arg("--distribution")
            .arg(name)
            .arg("--exec")
            .arg("/bin/sh")
            .arg("-l")
            .stdin(Stdio::inherit())
            .stdout(Stdio::inherit())
            .stderr(Stdio::inherit())
            .spawn()
    }
}

impl RuntimeBackend for WindowsWsl2Backend {
    fn kind(&self) -> RuntimeKind {
        RuntimeKind::WindowsWsl2
    }

    fn status(&self) -> RuntimeStatus {
        if self.is_available() {
            RuntimeStatus {
                kind: RuntimeKind::WindowsWsl2,
                state: RuntimeState::AvailableForDetection,
                message:
                    "WSL responds; DistroBoxer WSL distributions must be imported before launch",
            }
        } else {
            RuntimeStatus {
                kind: RuntimeKind::WindowsWsl2,
                state: RuntimeState::DependencyMissing,
                message: "WSL2 is not ready; enable and initialize Windows Subsystem for Linux",
            }
        }
    }
}

/// Finds wsl.exe using PATH and the standard Windows system directory.
pub fn find_wsl_executable() -> io::Result<PathBuf> {
    if let Some(path) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&path) {
            let candidate = directory.join("wsl.exe");
            if validate_executable(&candidate).is_ok() {
                return fs::canonicalize(candidate);
            }
        }
    }

    if let Some(root) = std::env::var_os("SystemRoot") {
        let candidate = PathBuf::from(root).join("System32").join("wsl.exe");
        if validate_executable(&candidate).is_ok() {
            return fs::canonicalize(candidate);
        }
    }

    Err(io::Error::new(
        io::ErrorKind::NotFound,
        "wsl.exe was not found; install or enable Windows Subsystem for Linux",
    ))
}

fn validate_executable(path: &Path) -> io::Result<()> {
    let metadata = fs::metadata(path)?;
    if !metadata.is_file() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "WSL executable path must be a regular file",
        ));
    }
    Ok(())
}

fn registered_name(distro_id: &str) -> io::Result<String> {
    if find_distro(distro_id).is_none() {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "unsupported distribution identifier",
        ));
    }
    Ok(format!("DistroBoxer-{distro_id}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_only_catalog_ids_to_wsl_names() {
        assert_eq!(registered_name("debian").unwrap(), "DistroBoxer-debian");
        assert_eq!(
            registered_name("../outside").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
        assert_eq!(
            registered_name("unknown").unwrap_err().kind(),
            io::ErrorKind::InvalidInput
        );
    }
}
