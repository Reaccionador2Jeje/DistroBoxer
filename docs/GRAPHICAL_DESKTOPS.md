# Graphical Linux desktops on Android and desktop

## Goal

DistroBoxer should offer full Linux desktop sessions without paid tiers. XFCE is the first target, followed by LXQt and MATE. A desktop environment is not an X server: the environment provides panels, menus, window management and desktop applications, while the display server presents application windows on Android or the desktop host.

## Android architecture

The intended Android flow is:

1. DistroBoxer installs a supported root filesystem into its private application storage.
2. The runtime starts the distribution through an unprivileged PRoot-compatible launcher. No root access or separate Linux kernel is assumed.
3. A display backend starts an X11-compatible server on Android.
4. DistroBoxer exposes only the display socket and required temporary runtime paths to the Linux environment. The guest receives a DISPLAY value and a private session environment.
5. The selected desktop session (initially XFCE) starts inside the distribution. DistroBoxer displays the server activity and keeps a visible session notification while the session is running.
6. Stop, suspend, resume and session logs are controlled from the DistroBoxer UI.

The X server must be started before the desktop session. A PRoot environment must share or deliberately map the temporary directory/socket expected by the chosen display backend. Merely setting DISPLAY is not sufficient.

## Display backend decision

Use a backend interface so DistroBoxer is not permanently tied to one X server implementation:

- EmbeddedX11Backend — long-term preferred option. The Android app owns the display server lifecycle and does not require another terminal app to be installed.
- ExternalX11Backend — optional compatibility adapter for a separately installed X server such as Termux:X11. This is an opt-in integration, not a mandatory dependency. Its app and companion package have their own installation and licensing requirements.
- VncBackend — possible fallback for devices where a direct X11 backend is unavailable. It needs a local-only viewer/server path and must not expose a remote desktop port to the network by default.

Do not bundle or redistribute third-party X server code until its exact license, notices, source availability, and F-Droid compatibility have been reviewed. Keep backend-specific integration behind an interface.

## Desktop environment profiles

| Environment | Priority | Initial profile |
|---|---:|---|
| XFCE | First | Full desktop session, panel, window manager, file manager and terminal |
| LXQt | Second | Lightweight Qt desktop |
| MATE | Third | Traditional desktop layout |

Install desktop components from the selected distribution's official repositories using its native package manager. Do not download arbitrary desktop bundles from unofficial mirrors. Package names and session commands vary by distribution, so each profile must carry distro-specific dependencies and a validated session launcher.

## Session lifecycle and Android background execution

A desktop session is long-running work, unlike an archive download. It must not be implemented as a WorkManager download job.

The session design needs:
- A user-started foreground service with a persistent notification while the session is active.
- Explicit Start, Stop and notification actions.
- A visible indication when Android stops or restricts a session.
- A clean shutdown path for the desktop process tree.
- No broad storage permissions. Root filesystems and runtime sockets remain app-private.
- Runtime permission prompts only when a feature actually needs them.

Android may restrict background CPU and memory. DistroBoxer must not promise uninterrupted sessions after the user force-stops the app or the operating system terminates it.

## UI contract

The Android and desktop apps expose a graphical-desktop area with:
- Available desktop profiles and their requirements.
- Display backend selection and readiness state.
- Start/stop session controls (disabled until a backend is implemented).
- Session status, selected distribution, display number and logs.
- Clear labels distinguishing planned profiles from installable/runnable features.

The current UI is a design preview. It does not yet install XFCE/LXQt/MATE, start an X server, or run a graphical session.

## Milestones

1. Complete and test the PRoot-compatible runtime for one installed distro.
2. Define and test the DisplayBackend interface and private socket mapping.
3. Prototype one supported X11 backend on Android 8+ devices, including Android 13+ notification behavior.
4. Install and launch XFCE in Alpine or Debian and verify keyboard, touch, window resizing and clean shutdown.
5. Add LXQt and MATE profiles with distribution-specific package metadata.
6. Add session persistence controls and document battery/memory limitations.
7. Run reproducible-build and license checks before including any third-party display components in F-Droid builds.
