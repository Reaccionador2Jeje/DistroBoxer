# Repair Planner

DistroBoxer Repair Planner turns Doctor diagnostics into conservative, human-readable maintenance suggestions.

## Rust API

```rust
use distroboxer::{plan_repairs, run_doctor, StorageManager};

let storage = StorageManager::new(app_private_files_dir);
let doctor_report = run_doctor(&storage);
let plan = plan_repairs(&doctor_report);

for action in plan.actions {
    println!("[{:?}] {}: {}", action.risk, action.id, action.title);
    for step in action.recommended_steps {
        println!("- {step}");
    }
}
```

## Plan contents

Each action includes a stable ID, source diagnostic codes, optional distribution ID, risk level, explanation, suggested steps, and whether a backup is recommended. Unknown warning/error codes produce a manual-review action rather than being silently discarded. Related rootfs findings are grouped into one suggestion.

## Safety contract

This is an advisory planner only. It consumes the already-created `DoctorReport` and does not access the filesystem, run commands, download archives, alter metadata, or repair anything.

- `automatic` is always `false`.
- `execution_supported` is always `false`.
- High-risk actions require manual review and generally recommend preserving a backup.
- A suggested step is not proof that the operation is safe in every environment.
- Applications must not treat a plan as authorization to perform file operations.

The plan is JSON-serializable and uses schema version 1. Risk labels describe the potential impact of following the suggestion, not a security certification.

## Tests

Tests cover rootfs finding grouping, low-risk storage initialization suggestions, unknown diagnostic fallback, and ignoring informational findings.

Run:

```sh
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
```
