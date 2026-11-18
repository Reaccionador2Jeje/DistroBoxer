# Storage Analyzer

DistroBoxer provides a read-only storage report through the shared Rust engine.

## Rust API

```rust
use distroboxer::{analyze_storage, StorageManager};

let report = analyze_storage(&StorageManager::new("/path/to/app-private-storage"));
println!("Available bytes: {:?}", report.available_bytes);

for distro in report.distributions {
    if let Some(rootfs) = distro.rootfs {
        println!("{} rootfs: {} bytes", distro.distro_id, rootfs.bytes);
    }
}
```

## Report contents

- Filesystem total and available bytes, when the host filesystem provides them.
- App-private storage usage and per-distribution rootfs/download usage.
- File, directory, and symbolic-link counts.
- A permission-mode hint for whether the current user appears to have write permission.
- Stable machine-readable findings for missing, unsafe, or unreadable paths.

The report is JSON-serializable and uses schema version 1.

## Safety and accuracy

The analyzer is read-only: it does not create, delete, repair, or write files. It counts symbolic links but never follows them. File sizes are logical file lengths; filesystem allocation, compression, sparse-file overhead, and hard-link deduplication are not calculated.

The `writable_by_mode` field is a permission-bit hint, not a guaranteed access test. ACLs, mount flags, sandbox policy, and runtime identity may further restrict access. No write probe is performed.

Capacity values describe the filesystem containing the app storage base, not a sum of per-directory quotas. They may be unavailable on unsupported filesystems.

## Tests

The module tests missing storage behavior, byte/file accounting, capacity reporting, and safe handling of symbolic links. Run:

```sh
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
```
