# Android PRoot permission handling

This change hardens the Android PRoot launch path without requesting broad storage
permissions or attempting to bypass Android's security model.

## Host-side executable

- PRoot is packaged as an ABI-specific executable in Android's native-library
  directory (`libproot.so`).
- Availability checks inspect Unix execute bits and call Android `Os.access(path, X_OK)`;
  `File.canExecute()` alone is not treated as authoritative.
- Startup diagnostics report the executable path, size, mode, owner and group where
  Android permits those details to be read.

## Guest root filesystem

- The selected shell and its parent directories must resolve inside the selected
  rootfs before permission repair.
- Only the shell's execute/traversal bits are repaired; the installer does not
  recursively chmod the entire distribution.
- `/root` is prepared as mode 0700 and `/tmp` as mode 01777.
- The programming workspace repairs its private `/root/projects` directory before
  writing source files.

## Android bind mounts

PRoot receives only the host pseudo-filesystems useful to Linux userspace:
`/dev`, `/proc`, and `/sys`, when present and readable. Android `/system` and
`/apex` are intentionally not mounted into the guest.

The host process working directory remains in the app's private files directory,
not inside the guest rootfs. PRoot temporary files stay in a tested writable
subdirectory of the app cache.

## Startup reporting

A process that exits during the initial startup window is treated as a failed
launch. Its exit code and captured output are returned to the terminal UI. This
prevents the UI from showing a connected session when PRoot immediately failed
to execute the guest shell.

## Remaining device-dependent boundary

This code cannot override Android SELinux policy, kernel ptrace restrictions,
a `noexec` mount, or an ABI mismatch. If PRoot still receives `EACCES`, the terminal
output and host-side diagnostics should help identify whether the denial comes
from the host PRoot executable, ptrace, a bind source, or the guest shell.
A real boot must be verified on a physical device; source changes alone do not
prove that Android permits the process to run.
