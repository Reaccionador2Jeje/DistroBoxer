# Runtime Detection

DistroBoxer exposes passive host and runtime discovery through the shared Rust engine.

## Rust API

```rust
use distroboxer::detect_runtimes;

let report = detect_runtimes();
println!("Host: {} / {}", report.host_os, report.host_architecture);

if report.has_runtime("qemu_aarch64") {
    println!("QEMU AArch64 is present on PATH");
}
```

## What it detects

- Host operating system using Rust's compile-time target constants.
- Host architecture using DistroBoxer's normalized architecture names.
- QEMU System executables for AArch64 and x86_64.
- PRoot executable.
- Executable locations found through the host `PATH` (and `PATHEXT` on Windows).

The report is JSON-serializable and uses schema version 1. Runtime identifiers are stable API values: `qemu_aarch64`, `qemu_x86_64`, and `proot`.

## Safety and limitations

Detection is read-only. It does not launch binaries, inspect guest filesystems, install packages, modify PATH, or infer that a runtime is fully functional merely because its executable exists. A found executable still needs a separate capability or launch check before DistroBoxer can rely on it.

The current supported-host flag means the host OS is among Android, Linux, macOS, and Windows. It does not promise that every runtime backend is implemented or usable on every host.

## Tests

The module tests architecture normalization, report structure, and runtime lookup behavior. Run the workspace checks locally with:

```sh
cargo fmt --all -- --check
cargo check --workspace
cargo test --workspace
```
