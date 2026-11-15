//! Serializable metadata stored beside each distribution root filesystem.

use serde::{Deserialize, Serialize};

/// Lifecycle state for a distribution environment.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum InstallStatus {
    NotInstalled,
    Preparing,
    Downloading,
    Extracting,
    Installed,
    Failed,
}

impl InstallStatus {
    /// Returns whether a lifecycle transition is valid.
    pub fn can_transition_to(self, next: Self) -> bool {
        use InstallStatus::*;

        self == next
            || matches!(
                (self, next),
                (NotInstalled, Preparing)
                    | (NotInstalled, Failed)
                    | (Preparing, Downloading)
                    | (Preparing, Failed)
                    | (Preparing, NotInstalled)
                    | (Downloading, Extracting)
                    | (Downloading, Failed)
                    | (Downloading, NotInstalled)
                    | (Extracting, Installed)
                    | (Extracting, Failed)
                    | (Extracting, NotInstalled)
                    | (Installed, Preparing)
                    | (Installed, NotInstalled)
                    | (Failed, Preparing)
                    | (Failed, NotInstalled)
            )
    }
}

/// Metadata persisted in each distribution's private storage directory.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct DistroMetadata {
    pub schema_version: u32,
    pub distro_id: String,
    pub distro_name: String,
    pub package_manager: String,
    pub status: InstallStatus,
    pub last_error: Option<String>,
}

impl DistroMetadata {
    /// Creates initial metadata from a known catalog entry.
    pub fn new(
        distro_id: impl Into<String>,
        distro_name: impl Into<String>,
        package_manager: impl Into<String>,
    ) -> Self {
        Self {
            schema_version: 1,
            distro_id: distro_id.into(),
            distro_name: distro_name.into(),
            package_manager: package_manager.into(),
            status: InstallStatus::NotInstalled,
            last_error: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::InstallStatus::*;

    #[test]
    fn allows_the_expected_installation_lifecycle() {
        assert!(NotInstalled.can_transition_to(Preparing));
        assert!(Preparing.can_transition_to(Downloading));
        assert!(Downloading.can_transition_to(Extracting));
        assert!(Extracting.can_transition_to(Installed));
    }

    #[test]
    fn allows_retry_after_failure_and_reinstallation() {
        assert!(Failed.can_transition_to(Preparing));
        assert!(Installed.can_transition_to(Preparing));
        assert!(Downloading.can_transition_to(NotInstalled));
    }

    #[test]
    fn rejects_skipped_or_reversed_lifecycle_steps() {
        assert!(!NotInstalled.can_transition_to(Installed));
        assert!(!Preparing.can_transition_to(Installed));
        assert!(!Downloading.can_transition_to(Preparing));
        assert!(!Extracting.can_transition_to(Downloading));
        assert!(!Failed.can_transition_to(Installed));
    }
}
