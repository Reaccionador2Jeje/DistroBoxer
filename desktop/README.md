# DistroBoxer Desktop

The desktop application is a Tauri 2 shell using the shared Rust engine. The initial milestone is a native distribution catalog for Windows and Linux.

## Current scope

- Native Rust engine shared with Android.
- Tauri desktop window and catalog UI.
- Distribution profiles and official project links.
- No rootfs installation, PRoot runtime, terminal, or background session support yet.

## Requirements

- Rust stable
- Tauri 2 system dependencies for the target operating system

On Ubuntu, install the Tauri Linux prerequisites (including WebKitGTK 4.1 development libraries), then run:

```sh
cargo run --manifest-path desktop/Cargo.toml
```

On Windows, use the Visual Studio C++ Build Tools and WebView2 runtime, then run the same command from PowerShell.

The SVG brand mark is in `desktop/icons/distroboxer.svg`. Platform installer bundles and signed releases are a later packaging milestone.
