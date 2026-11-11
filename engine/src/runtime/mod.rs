//! Cross-platform Linux userspace runtime abstraction.
//!
//! Distribution installation is platform-independent, but executing a Linux
//! root filesystem is not. Each host platform selects an appropriate backend:
//! PRoot on Android/Linux, WSL2 on Windows, and a lightweight Linux VM on macOS.
//!
//! This module deliberately describes backend availability separately from
//! implementation readiness. A selected backend is not considered runnable
//! until its host integration and runtime binary have been validated.

use std::fmt;

/// Runtime backend selected for the current host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeKind {
    /// PRoot binary bundled and managed by the Android application.
    AndroidProot,
    /// Native PRoot executable on a Linux host.
    LinuxProot,
    /// Windows Subsystem for Linux 2.
    WindowsWsl2,
    /// Linux virtual machine hosted by Apple's Virtualization framework.
    MacOsVirtualMachine,
    /// The host platform is not supported.
    Unsupported,
}

/// Runtime implementation state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RuntimeState {
    /// Backend has an implementation and can be checked for its dependencies.
    AvailableForDetection,
    /// Backend is planned but its host integration is not implemented yet.
    Planned,
    /// Required host dependency was not found.
    DependencyMissing,
    /// The host platform is not supported.
    Unsupported,
}

/// Result of checking a backend on the current host.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RuntimeStatus {
    pub kind: RuntimeKind,
    pub state: RuntimeState,
    pub message: &'static str,
}

/// Errors returned by runtime selection and validation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuntimeError {
    UnsupportedPlatform,
    BackendNotImplemented(RuntimeKind),
    MissingDependency(&'static str),
}

impl fmt::Display for RuntimeError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::UnsupportedPlatform => write!(formatter, "host platform is not supported"),
            Self::BackendNotImplemented(kind) => {
                write!(formatter, "runtime backend {kind:?} is not implemented yet")
            }
            Self::MissingDependency(name) => {
                write!(formatter, "required runtime dependency is missing: {name}")
            }
        }
    }
}

impl std::error::Error for RuntimeError {}

/// Runtime backend contract shared by desktop and Android frontends.
///
/// Implementations will own process creation, environment setup, bind mounts,
/// terminal I/O, and graceful session shutdown. UI code must not construct
/// PRoot/WSL/VM commands itself.
pub trait RuntimeBackend {
    fn kind(&self) -> RuntimeKind;
    fn status(&self) -> RuntimeStatus;
}

/// Returns the backend appropriate for the compilation target.
pub const fn current_runtime_kind() -> RuntimeKind {
    #[cfg(target_os = "android")]
    {
        RuntimeKind::AndroidProot
    }
    #[cfg(all(target_os = "linux", not(target_os = "android")))]
    {
        RuntimeKind::LinuxProot
    }
    #[cfg(target_os = "windows")]
    {
        RuntimeKind::WindowsWsl2
    }
    #[cfg(target_os = "macos")]
    {
        RuntimeKind::MacOsVirtualMachine
    }
    #[cfg(not(any(
        target_os = "android",
        target_os = "linux",
        target_os = "windows",
        target_os = "macos"
    )))]
    {
        RuntimeKind::Unsupported
    }
}

/// Reports the implementation readiness of the current host backend.
///
/// Detection is intentionally conservative: it never claims that a Linux
/// environment can start merely because the host OS is recognized.
pub fn current_runtime_status() -> RuntimeStatus {
    let kind = current_runtime_kind();
    let (state, message) = match kind {
        RuntimeKind::AndroidProot => (
            RuntimeState::AvailableForDetection,
            "Android PRoot backend selected; bundled binary and app sandbox must be validated",
        ),
        RuntimeKind::LinuxProot => (
            RuntimeState::AvailableForDetection,
            "Linux PRoot backend selected; the host proot executable must be detected",
        ),
        RuntimeKind::WindowsWsl2 => (
            RuntimeState::Planned,
            "Windows backend uses WSL2; host integration is not implemented yet",
        ),
        RuntimeKind::MacOsVirtualMachine => (
            RuntimeState::Planned,
            "macOS backend uses a Linux VM through Virtualization.framework; host integration is not implemented yet",
        ),
        RuntimeKind::Unsupported => (
            RuntimeState::Unsupported,
            "this host platform is not supported",
        ),
    };

    RuntimeStatus {
        kind,
        state,
        message,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn host_backend_is_one_of_the_supported_targets() {
        assert_ne!(current_runtime_kind(), RuntimeKind::Unsupported);
    }

    #[test]
    fn windows_and_macos_are_explicitly_planned_not_misreported_as_proot() {
        assert_ne!(RuntimeKind::WindowsWsl2, RuntimeKind::LinuxProot);
        assert_ne!(RuntimeKind::MacOsVirtualMachine, RuntimeKind::LinuxProot);
        assert_eq!(
            RuntimeStatus {
                kind: RuntimeKind::WindowsWsl2,
                state: RuntimeState::Planned,
                message: "Windows backend uses WSL2; host integration is not implemented yet",
            }
            .state,
            RuntimeState::Planned
        );
        assert_eq!(
            RuntimeStatus {
                kind: RuntimeKind::MacOsVirtualMachine,
                state: RuntimeState::Planned,
                message: "macOS backend uses a Linux VM through Virtualization.framework; host integration is not implemented yet",
            }
            .state,
            RuntimeState::Planned
        );
    }
}
