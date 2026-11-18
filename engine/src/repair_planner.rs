//! Advisory repair planning from Doctor findings.
//!
//! Plans are inert data: this module never writes files, runs commands,
//! downloads archives, or invokes a repair backend.

use crate::{Diagnostic, DiagnosticLevel, DoctorReport};
use serde::Serialize;
use std::collections::BTreeSet;

/// Relative risk of carrying out a suggested maintenance action.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RepairRisk {
    Low,
    Medium,
    High,
}

/// One suggested maintenance action. It is descriptive and never executable.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct RepairAction {
    /// Stable identifier suitable for UI grouping.
    pub id: String,
    /// Original Doctor diagnostic codes that led to this action.
    pub source_codes: Vec<String>,
    pub distro_id: Option<String>,
    pub risk: RepairRisk,
    pub title: String,
    pub explanation: String,
    pub recommended_steps: Vec<String>,
    /// Always false in this API: no automatic repair is supported.
    pub automatic: bool,
    /// Whether the user should preserve a copy before attempting the suggestion.
    pub backup_recommended: bool,
}

/// Advisory plan generated from a Doctor report.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct RepairPlan {
    pub schema_version: u32,
    pub source_diagnostics: usize,
    pub actions: Vec<RepairAction>,
    /// Always false; callers must not interpret a plan as permission to mutate.
    pub execution_supported: bool,
}

impl RepairPlan {
    pub fn has_high_risk_actions(&self) -> bool {
        self.actions.iter().any(|action| action.risk == RepairRisk::High)
    }
}

/// Converts Doctor findings into conservative, human-readable suggestions.
///
/// The planner consumes only the supplied report. It does not access storage
/// or inspect paths, so it cannot accidentally mutate or traverse user files.
pub fn plan_repairs(report: &DoctorReport) -> RepairPlan {
    let mut actions: Vec<RepairAction> = Vec::new();
    let mut handled = BTreeSet::new();

    for diagnostic in &report.diagnostics {
        let Some((id, risk, title, explanation, steps, backup)) = recommendation(diagnostic) else {
            continue;
        };

        // Several Doctor findings may describe the same underlying rootfs
        // problem. Keep one action and attach all related diagnostic codes.
        if let Some(existing) = actions.iter_mut().find(|action: &&mut RepairAction| {
            action.id == id && action.distro_id == diagnostic.distro_id
        }) {
            let action = *existing;
            if handled.insert((id.to_owned(), diagnostic.code.clone())) {
                action.source_codes.push(diagnostic.code.clone());
            }
            continue;
        }

        if !handled.insert((id.to_owned(), diagnostic.code.clone())) {
            continue;
        }

        actions.push(RepairAction {
            id: id.to_owned(),
            source_codes: vec![diagnostic.code.clone()],
            distro_id: diagnostic.distro_id.clone(),
            risk,
            title: title.to_owned(),
            explanation: explanation.to_owned(),
            recommended_steps: steps.iter().map(|step| (*step).to_owned()).collect(),
            automatic: false,
            backup_recommended: backup,
        });
    }

    RepairPlan {
        schema_version: 1,
        source_diagnostics: report.diagnostics.len(),
        actions,
        execution_supported: false,
    }
}

type Recommendation = (&'static str, RepairRisk, &'static str, &'static str, &'static [&'static str], bool);

fn recommendation(diagnostic: &Diagnostic) -> Option<Recommendation> {
    use DiagnosticLevel::{Error, Warning};

    let result = match diagnostic.code.as_str() {
        "storage.base.missing" | "storage.distros.missing" => (
            "initialize_storage",
            RepairRisk::Low,
            "Initialize DistroBoxer storage",
            "The app-private storage layout has not been created.",
            &[
                "Confirm the configured app-private storage location.",
                "Use DistroBoxer's normal initialization flow.",
                "Run Doctor again to verify the directory layout.",
            ][..],
            false,
        ),
        "storage.base.invalid" | "storage.distros.invalid" => (
            "review_storage_path",
            RepairRisk::High,
            "Review an unsafe storage path",
            "A storage path is not a normal directory. Do not delete or replace it automatically.",
            &[
                "Inspect the reported path and its parent directory.",
                "Check whether it is a file or symbolic link and why it exists.",
                "Back up relevant data before changing the storage layout.",
                "Choose a new app-private storage location if the path cannot be trusted.",
            ][..],
            true,
        ),
        "distro.metadata.invalid" => (
            "recover_metadata",
            RepairRisk::High,
            "Recover distribution metadata",
            "The saved metadata is unreadable or does not match the expected schema.",
            &[
                "Preserve a copy of the current distribution directory.",
                "Review the metadata file and compare it with the supported distribution catalog.",
                "Regenerate metadata only through a trusted DistroBoxer recovery workflow.",
                "Run Doctor again before launching the distribution.",
            ][..],
            true,
        ),
        "distro.rootfs.missing" | "distro.state.inconsistent"
        | "distro.os_release.missing" | "distro.os_release.invalid"
        | "distro.os_release.escape" | "distro.identity.mismatch"
        | "distro.rootfs.invalid" => (
            "restore_rootfs",
            RepairRisk::High,
            "Restore the distribution root filesystem",
            "The root filesystem is missing, unsafe, incomplete, or does not identify as the selected distribution.",
            &[
                "Do not launch this distribution in its current state.",
                "Preserve user projects and important files from the distribution directory if accessible.",
                "Obtain a trusted rootfs for the exact distribution and architecture.",
                "Verify its published SHA-256 before using DistroBoxer's supported installation flow.",
                "Run Doctor again and confirm the identity checks pass.",
            ][..],
            true,
        ),
        "distro.shell.missing" => (
            "review_guest_shell",
            RepairRisk::Medium,
            "Review the guest shell installation",
            "No common shell executable was found at the expected rootfs paths.",
            &[
                "Confirm the rootfs is complete and belongs to the selected distribution.",
                "Check the distribution's documented shell package.",
                "Use a trusted package-management workflow only after the rootfs passes Doctor checks.",
                "Run Doctor again after the change.",
            ][..],
            true,
        ),
        "distro.install.failed" | "distro.state.failed" => (
            "review_failed_install",
            RepairRisk::Medium,
            "Review the previous installation failure",
            "Metadata records a failed or interrupted installation.",
            &[
                "Read the saved installation error and relevant application logs.",
                "Check network availability and the trusted archive source.",
                "Verify the archive checksum before retrying the supported installer.",
                "Keep existing user data until recovery is confirmed.",
            ][..],
            true,
        ),
        "distro.os_release.unreadable" | "distro.os_release.resolve_failed"
        | "distro.rootfs.resolve_failed" | "distro.rootfs.unreadable" => (
            "inspect_distribution_files",
            RepairRisk::High,
            "Inspect distribution files",
            "A required distribution file could not be safely inspected.",
            &[
                "Avoid launching or modifying the distribution until it is understood.",
                "Check filesystem permissions and storage health.",
                "Preserve a backup before attempting manual recovery.",
                "Run Doctor again after recovery.",
            ][..],
            true,
        ),
        _ if diagnostic.level == Error || diagnostic.level == Warning => (
            "manual_review",
            RepairRisk::High,
            "Review this diagnostic manually",
            "No dedicated recovery recipe exists for this diagnostic code.",
            &[
                "Review the diagnostic message and reported path.",
                "Do not delete or overwrite files based only on this suggestion.",
                "Consult the project documentation or maintainer before changing data.",
            ][..],
            true,
        ),
        _ => return None,
    };

    Some(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Diagnostic;

    fn report(codes: &[(&str, DiagnosticLevel)]) -> DoctorReport {
        DoctorReport {
            schema_version: 1,
            healthy: false,
            checked_distributions: 1,
            diagnostics: codes.iter().map(|(code, level)| Diagnostic {
                code: (*code).to_owned(),
                level: *level,
                distro_id: Some("alpine".to_owned()),
                message: "test finding".to_owned(),
                path: None,
            }).collect(),
        }
    }

    #[test]
    fn creates_conservative_plan_for_missing_rootfs() {
        let input = report(&[
            ("distro.rootfs.missing", DiagnosticLevel::Error),
            ("distro.state.inconsistent", DiagnosticLevel::Error),
        ]);
        let plan = plan_repairs(&input);

        assert_eq!(plan.actions.len(), 1);
        assert_eq!(plan.actions[0].id, "restore_rootfs");
        assert_eq!(plan.actions[0].source_codes.len(), 2);
        assert_eq!(plan.actions[0].risk, RepairRisk::High);
        assert!(!plan.actions[0].automatic);
        assert!(!plan.execution_supported);
    }

    #[test]
    fn missing_storage_gets_low_risk_initialization_suggestion() {
        let plan = plan_repairs(&report(&[("storage.base.missing", DiagnosticLevel::Warning)]));
        assert_eq!(plan.actions[0].risk, RepairRisk::Low);
        assert!(!plan.actions[0].backup_recommended);
    }

    #[test]
    fn unknown_findings_are_not_silently_ignored() {
        let plan = plan_repairs(&report(&[("future.unknown.problem", DiagnosticLevel::Error)]));
        assert_eq!(plan.actions.len(), 1);
        assert_eq!(plan.actions[0].id, "manual_review");
        assert!(plan.has_high_risk_actions());
    }

    #[test]
    fn informational_findings_do_not_create_actions() {
        let plan = plan_repairs(&report(&[("distro.rootfs.ok", DiagnosticLevel::Info)]));
        assert!(plan.actions.is_empty());
    }
}
