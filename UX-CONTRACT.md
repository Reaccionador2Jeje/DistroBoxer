# DistroBoxer UX Contract

## Product context
- Audience: People managing Linux distributions and a lightweight Linux workspace on Android or desktop.
- Primary jobs: Browse supported distributions, inspect compatibility, install supported root filesystems, open an available Linux shell, edit/run a supported project, and inspect runtime/settings state.
- Target markets: International open-source project.
- Active locales: Spanish UI for this release; strings should remain easy to translate.
- Language/content register: Direct, plain Spanish; use specific action verbs and explain unavailable capabilities.
- Timezone/calendar: Use platform defaults; no custom date workflows in the current UI.
- Accessibility target: Aim for WCAG 2.2 AA for desktop and equivalent native Android semantics, contrast, focus, and touch usability.

## Business-context sources
| Domain / scope | Authoritative source | Source type | Reviewed date |
|---|---|---|---|
| Runtime capabilities and limitations | `README.md`, `android/README.md`, `docs/QEMU_MIGRATION.md`, `docs/GRAPHICAL_DESKTOPS.md` | Product/architecture documentation | 2026-11-18 |
| Installation lifecycle and validation | `docs/OCI_INSTALLERS.md`, Rust engine installer/storage modules | Domain/implementation contract | 2026-11-18 |
| Android storage and permissions | `android/README.md`, `AndroidManifest.xml` | Permission policy | 2026-11-18 |
| License and monetization principles | `README.md` | Product policy | 2026-11-18 |

## Visual contract
- Project design: `DESIGN.md`.
- Token ownership: `DESIGN.md` defines shared intent; Android Compose `DistroPalette` and desktop CSS variables are runtime adapters that mirror it.
- Runtime sources: `android/app/src/main/java/com/atdevdev/distroboxer/MainActivity.kt`; `desktop/frontend/styles.css`.
- Mapping: semantic colors map one-to-one to `DistroPalette` values and CSS custom properties.
- Supported themes: dark theme in the current application.
- Design-context owner: update both runtime adapters and this document when shared design decisions change.

## Canonical UI Map
| Capability | Canonical owner | Source of truth | Allowed variants | Verification |
|---|---|---|---|---|
| Navigation | Android `AppTab` / Compose NavigationBar; desktop `navigation` buttons and `navigate()` | This contract and existing route/page identifiers | Native bottom bar / desktop sidebar | Manual navigation across all destinations |
| Search | Android Compose `OutlinedTextField`; desktop `#distro-search` and `renderCatalog()` | Current catalog state | Immediate local filtering | Clear, no-results, keyboard |
| Distribution cards | Android `DistroCard`; desktop `renderDistro()` | Rust catalog metadata and install lifecycle state | Native Compose / semantic HTML | Architecture and status truth |
| Feedback | Inline state labels and existing copy-error actions | WorkManager and runtime results | Inline status / copy error | Success, failure, retry |
| Terminal output | Android `TerminalPage` / `TerminalSession`; desktop preview is read-only | Actual process output; desktop must not imply command execution | Native shell / explicit preview | Connected, stopped, unavailable |

## Component behavior
| Component | Default | Hover | Focus | Active | Disabled | Busy | Error |
|---|---|---|---|---|---|---|---|
| Button | One clear action per area | Slight surface/border change | Visible primary outline | Tonal press | No pointer action | Stable dimensions, status label | Inline error |
| Search | Labeled query | Border emphasis | Visible outline | n/a | n/a | n/a | No-results guidance |
| Distribution card | Name + package manager + compatibility | Border emphasis on desktop | Actions reachable by keyboard | n/a | Explain unavailable installer | Show WorkManager progress | Preserve/copy useful error |
| Navigation | Current destination highlighted | Surface emphasis | Visible focus | Selected marker + label | n/a | n/a | n/a |
| Terminal | Clear session state | n/a | Native input focus | Connected state | Commands disabled without session | Show session startup state | Show process error and copy option |

## Dataset navigation
- Catalog: exploratory list of eight supported profiles.
- Search: local immediate filter over name, ID, package manager, description, bootstrap, and archive format.
- Filters: Android installed/available filters remain unchanged.
- Empty/no-results/error/loading: keep distinct states and preserve the search value on load failure.
- Back/scroll restoration: preserve current page and query while the screen remains composed.

## Flow ledger
| Operation | Trigger | Pending | Success destination | Success feedback | Failure recovery | Focus outcome | Source ref |
|---|---|---|---|---|---|---|---|
| Search | Input changes | Immediate local filtering | Same catalog | Count updates | Clear query / change filter | Keep input focus | Local catalog |
| Install | Install action on supported distro | WorkManager progress | Same distro card | Status reflects real worker state | Show error; retry from action | Keep context on card | OCI installer docs |
| Prepare storage | Prepare action | Native bridge call | Same distro card | State says space prepared, not installed | Inline failure status | Keep context on card | Rust storage API |
| Open shell | Open/start action | Process startup | Terminal | Connected status only after process starts | Show process error | Terminal command input when connected | ProotRuntime |
| Stop shell | Stop action | Process termination | Terminal | Session stopped | Show stop result | Terminal action region | TerminalSession |
| Run project | Run action | Save/copy/execute path | Programming | Actual command result | Show error and allow correction | Editor/action region | ProjectWorkspace |
| Import VM input | File picker | File import | Terminal | Kernel/initrd imported state | Explain import error | Import action | QemuRuntime |

## Navigation and responsive behavior
- Android destinations: Inicio, Distros, Terminal, Programar, Escritorio, Ajustes; retain existing enum and navigation behavior.
- Desktop destinations: Inicio, Distribuciones, Terminal, Escritorio, Ajustes.
- Android: bottom navigation remains visible; content scrolls independently and respects system insets.
- Desktop: sidebar on wide viewports; horizontal navigation strip on narrow viewports.
- Focus: preserve native focus behavior; visible focus outline on desktop; controls must remain reachable at narrow widths and with virtual keyboard open.
- No page should claim unavailable runtime functionality.

## Overlays and feedback
- Current app uses inline status and native Compose AlertDialog for crash report only.
- Desktop must not add browser alert/confirm/prompt.
- Copy-error controls remain available for detailed runtime errors.
- Stop/restart and future distribution removal flows must identify the affected resource before destructive confirmation.

## Async and resilience
- Installation remains queued/background WorkManager work on Android.
- UI status reflects the worker state and does not invent percentage progress.
- Do not duplicate installation actions while work is active.
- Terminal output is bounded; process callbacks update the visible session state.
- Desktop catalog loading exposes connected/offline and retry states.
- Local search is immediate; clear is immediate and focus returns to the input.
- Preserve typed search and page context when catalog refresh fails.

## Validation
- Commands: `cargo fmt --all -- --check`, `cargo check --workspace`, `cargo test --workspace`, `gradle :android:app:assembleDebug`, and the desktop Tauri build where toolchains are available.
- Device/browser matrix: Android narrow phone; desktop wide and narrow window.
- Accessibility: native semantics, visible focus, contrast, touch-sized actions, keyboard navigation, reduced motion.
- Canonical sibling workflows: Android catalog and terminal; desktop catalog and settings.
- Known limitation: a physical Android device and graphical browser preview are not available in this editing session; report source/CI checks separately from runtime validation.
