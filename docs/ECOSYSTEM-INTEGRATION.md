# DistroBoxer — Ecosystem Integration

Status: planning document; supported distributions and architectures must reflect verified profiles only.

## Runtime boundary
The Android environment is an unprivileged user-space environment, not a separate Linux kernel or full virtual machine. Keep desktop and Android runtime capabilities distinct.

## Integration rules
- Validate distribution IDs, architecture and download metadata before installation.
- Verify downloaded artifacts with trusted checksums/signatures when available.
- Keep installations in app-private storage unless a user-selected storage design is documented.
- Do not run downloaded installation scripts without inspection and explicit consent.
- Surface unsupported architectures and failed prerequisites clearly.

## Next tasks
1. Add tests for catalog profile validation.
2. Add tests for lifecycle transition rules.
3. Document storage use and uninstall behavior.
4. Add a distribution/architecture compatibility matrix based on verified test results.