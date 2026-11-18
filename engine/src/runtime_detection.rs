//! Passive host and runtime discovery for DistroBoxer.
//!
//! Detection inspects platform constants and searches PATH. It never launches
//! QEMU, PRoot, a shell, or any other external executable.

use serde::Serialize;
use std::env;
use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};

/// A runtime executable found on the host PATH.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct DetectedRuntime {
    /// Stable runtime identifier.
    pub id: String,
    /// Command name that was discovered.
    pub command: String,
    /// Absolute or PATH-derived executable location.
    pub path: String,
}

/// Snapshot of the host platform and known runtime executables.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct RuntimeReport {
    pub schema_version: u32,
    /// Rust target OS, such as `linux`, `android`, `windows`, or `macos`.
    pub host_os: String,
    /// Normalized architecture, such as `aarch64` or `x86_64`.
    pub host_architecture: String,
    /// Whether this host OS is one of the platforms DistroBoxer currently targets.
    pub supported_host: bool,
    pub runtimes: Vec<DetectedRuntime>,
}

impl RuntimeReport {
    pub fn has_runtime(&self, id: &str) -> bool {
        self.runtimes.iter().any(|runtime| runtime.id == id)
    }
}

/// Detects host details and runtime binaries without executing them.
pub fn detect_runtimes() -> RuntimeReport {
    let host_os = env::consts::OS.to_owned();
    let host_architecture = normalize_architecture(env::consts::ARCH).to_owned();
    let supported_host = matches!(host_os.as_str(), "android" | "linux" | "macos" | "windows");
    let path = env::var_os("PATH");

    let candidates = [
        ("qemu_aarch64", "qemu-system-aarch64"),
        ("qemu_x86_64", "qemu-system-x86_64"),
        ("proot", "proot"),
    ];

    let runtimes = candidates
        .iter()
        .filter_map(|(id, command)| {
            find_executable(command, path.as_deref()).map(|found| DetectedRuntime {
                id: (*id).to_owned(),
                command: (*command).to_owned(),
                path: found.display().to_string(),
            })
        })
        .collect();

    RuntimeReport {
        schema_version: 1,
        host_os,
        host_architecture,
        supported_host,
        runtimes,
    }
}

/// Converts Rust target architecture names to DistroBoxer's stable names.
pub fn normalize_architecture(architecture: &str) -> &str {
    match architecture {
        "aarch64" | "arm64" => "aarch64",
        "x86_64" | "x86-64" | "amd64" => "x86_64",
        "x86" | "i386" | "i586" | "i686" => "x86",
        "arm" | "armv7" | "armv7l" => "arm",
        other => other,
    }
}

fn find_executable(command: &str, path_value: Option<&OsStr>) -> Option<PathBuf> {
    let path_value = path_value?;
    for directory in env::split_paths(path_value) {
        for candidate in command_candidates(command) {
            let path = directory.join(candidate);
            if is_executable_file(&path) {
                return Some(path);
            }
        }
    }
    None
}

#[cfg(windows)]
fn command_candidates(command: &str) -> Vec<OsString> {
    let has_extension = Path::new(command).extension().is_some();
    if has_extension {
        return vec![OsString::from(command)];
    }

    let extensions = env::var_os("PATHEXT")
        .unwrap_or_else(|| OsString::from(".COM;.EXE;.BAT;.CMD"));
    let mut candidates = vec![OsString::from(command)];
    for extension in extensions.to_string_lossy().split(';') {
        candidates.push(OsString::from(format!("{command}{extension}")));
    }
    candidates
}

#[cfg(not(windows))]
fn command_candidates(command: &str) -> Vec<OsString> {
    vec![OsString::from(command)]
}

fn is_executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        return metadata.permissions().mode() & 0o111 != 0;
    }

    #[cfg(windows)]
    {
        true
    }

    #[cfg(not(any(unix, windows)))]
    {
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_common_architecture_aliases() {
        assert_eq!(normalize_architecture("arm64"), "aarch64");
        assert_eq!(normalize_architecture("amd64"), "x86_64");
        assert_eq!(normalize_architecture("i686"), "x86");
        assert_eq!(normalize_architecture("armv7l"), "arm");
        assert_eq!(normalize_architecture("riscv64"), "riscv64");
    }

    #[test]
    fn report_contains_host_information() {
        let report = detect_runtimes();
        assert_eq!(report.schema_version, 1);
        assert!(!report.host_os.is_empty());
        assert!(!report.host_architecture.is_empty());
    }

    #[test]
    fn runtime_lookup_is_stable_when_missing() {
        let report = RuntimeReport {
            schema_version: 1,
            host_os: "linux".into(),
            host_architecture: "aarch64".into(),
            supported_host: true,
            runtimes: Vec::new(),
        };
        assert!(!report.has_runtime("proot"));
    }
}
