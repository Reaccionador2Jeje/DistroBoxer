# DistroBoxer Doctor

DistroBoxer Doctor is a read-only diagnostic API in the shared Rust engine.

## What it checks

- Whether the app-private storage root and `distros/` directory are real directories.
- Whether each known distribution has readable, schema-valid metadata.
- Whether a rootfs exists without following a symlink at the rootfs directory itself.
- Whether `etc/os-release` resolves to a file inside that rootfs.
- Whether the reported `ID` matches the catalog identifier.
- Whether a common shell path exists.
- Whether persisted installation state agrees with the files on disk.

## Rust API

```rust
use distroboxer::{run_doctor, run_distro_doctor, StorageManager};

let storage = StorageManager::new(app_private_files_dir);
let report = run_doctor(&storage);

println!("healthy: {}", report.healthy);
for finding in report.diagnostics {
    println!("[{:?}] {}: {}", finding.level, finding.code, finding.message);
}

// Inspect one distribution:
let alpine = run_distro_doctor(&storage, "alpine")?;
```

The report is serializable with Serde and uses stable diagnostic codes so Android, desktop, and future AtDev tools can render consistent messages.

## Safety contract

Doctor does not create directories, change metadata, download files, extract archives, invoke package managers, or execute guest programs. Unknown distribution IDs are rejected by the single-distribution API. Findings are observations, not automatic repair instructions.

A warning does not necessarily mean an installation is unusable. An error means the scan found an invalid or inconsistent condition that should be reviewed before attempting to launch that environment.

## Current limitations

- Shell detection checks for common shell paths; it does not execute the shell.
- Runtime availability (QEMU/PRoot), Android ABI compatibility, free disk space, and boot readiness are not yet probed.
- The scan is a point-in-time filesystem inspection and does not lock concurrent installers.
