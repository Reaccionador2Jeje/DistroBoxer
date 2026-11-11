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
