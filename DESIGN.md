---
version: alpha
name: "DistroBoxer"
description: "A practical Linux workspace for managing distributions and working with Linux environments across Android and desktop."
colors:
  primary: "#C7E36A"
  secondary: "#86BAC5"
  background: "#0B1317"
  surface: "#141F24"
  surface-raised: "#1E2D33"
  border: "#304147"
  text: "#E9ECE4"
  muted: "#A4B1AD"
  success: "#83C99A"
  warning: "#E8AA68"
  danger: "#E47F78"
typography:
  sans:
    fontFamily: "system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif"
  mono:
    fontFamily: "ui-monospace, 'SFMono-Regular', Consolas, monospace"
rounded:
  DEFAULT: "0.875rem"
  sm: "0.625rem"
  md: "0.875rem"
  lg: "1.25rem"
spacing:
  page-gutter: "1.25rem"
  section-gap: "1.25rem"
  card-padding: "1.125rem"
  page-max: "88rem"
components:
  button: {}
  card: {}
  navigation: {}
  terminal: {}
  status: {}
---
# DistroBoxer Design System

## Overview

### Creative North Star
DistroBoxer should feel like a compact field workstation for Linux: the tactile clarity of a well-made diagnostic instrument, translated into a friendly software workspace. Its visual signature is a **runtime signal rail**: compact monospace labels, small state markers, and one clear signal color that makes runtime state legible without turning every screen into a terminal.

### Product context and register
- **Audience and primary job:** People who want to prepare and manage Linux distributions and use a lightweight Linux workspace from Android or desktop.
- **Target market(s) and evidence:** International open-source project. Current interface copy is Spanish; avoid country-specific assumptions and keep text easy to translate.
- **Locale(s) and language policy:** Spanish UI copy for this release; use Android/desktop locale-aware platform defaults for dates and numbers. New user-facing strings should remain concise and translation-friendly.
- **Usage scene:** Primarily Android phones, sometimes tablets and desktop windows. One-handed navigation and readable status are important on phones; desktop can use more horizontal space.
- **Register:** Product-first hybrid. Home can express the Linux-workbench identity; installation, terminal, settings, and error states prioritize clarity and honest capability descriptions.
- **Memorable signature:** The runtime signal rail: uppercase monospace metadata, concise state labels, and a restrained citron accent.
- **Restraint:** Keep distribution names, installation state, architecture compatibility, and next actions more prominent than decoration. Do not imply an unavailable runtime or feature is ready.
- **Anti-references:** Generic neon hacker dashboards, dense pseudo-terminal decoration, glassmorphism, crypto-style gradients, and oversized marketing heroes that bury the actual Linux tasks.
- **Token ownership/runtime mapping:** This document is the shared visual contract. Android Compose values are implemented in `android/app/src/main/java/com/atdevdev/distroboxer/MainActivity.kt` through `DistroPalette`; desktop values are implemented as CSS custom properties in `desktop/frontend/styles.css`. Both adapters mirror the exact semantic values below. Update both adapters with this file when tokens change.

## Colors

The palette is mineral and readable rather than black-and-neon. `primary` (#C7E36A) is a soft citron signal for primary actions and selected states. `secondary` (#86BAC5) is a cool blue for supporting information. `background` (#0B1317), `surface` (#141F24), and `surface-raised` (#1E2D33) establish depth through tonal steps; `border` (#304147) defines structure without heavy outlines. `text` (#E9ECE4) and `muted` (#A4B1AD) carry hierarchy. `success`, `warning`, and `danger` are reserved for actual state semantics and are never the only state cue.

## Typography

Use the platform sans-serif for headings and body copy to avoid external font dependencies and preserve native rendering. Use the system monospace only for shell output, command input, architecture identifiers, technical metadata, and the signal rail. Keep body copy short, sentence case, and comfortably readable; reserve uppercase tracking for compact metadata labels. Never set important status copy in tiny text.

## Layout

Android uses a single-column, scrollable content area with persistent bottom navigation and a compact identity header. Keep navigation labels visible and avoid forcing wide cards onto phone widths. Desktop uses a fixed-width sidebar at wide sizes, a flexible content column capped at 88rem, and converts the sidebar to a horizontally scrollable navigation strip on narrow windows. Cards use consistent internal padding and content-driven height. Search and filters remain directly above the catalog they control.

## Elevation & Depth

Hierarchy comes from tonal surfaces, subtle borders, and spacing. Avoid large drop shadows and translucent glass panels. The terminal and editor may use deeper inset surfaces to distinguish working areas. Navigation remains a stable, slightly raised surface.

## Shapes

Use a 14px standard radius for cards and controls, 10px for compact controls, and 20px for prominent home panels. Prefer clean rounded rectangles over pill-shaped cards. Pills are reserved for short status labels and architecture/package-manager metadata.

## Components

### Foundational visual states
- Default: raised surface, clear text hierarchy, visible boundary where needed.
- Hover: slightly lighter surface and border on desktop; no hover-only information.
- Focus: visible 2px primary outline with offset; preserve native keyboard focus.
- Active/selected: primary tint plus a non-color indicator such as label/icon or weight.
- Disabled: reduced emphasis and no pointer affordance; explain unavailable actions nearby.
- Busy: preserve button dimensions and show a truthful progress/status label.
- Success/warning/error: semantic text and icon/label plus corresponding color.
- Loading: reserve the expected content region and use concise status copy.

### Buttons and actions
Use one solid primary action per decision area. Secondary actions use tonal/outlined or text-button treatment. Label actions with specific verbs such as “Instalar”, “Abrir terminal”, “Importar kernel”, and “Detener sesión”. Keep destructive or stop actions visually distinct from safe setup actions.

### Navigation and data display
Android uses the six existing destinations (Inicio, Distros, Terminal, Programar, Escritorio, Ajustes) with visible labels; retain their behavior. Desktop uses the existing five destinations and highlights the current page with a left signal marker. Distribution cards expose name, package manager, architecture, install status, and the actions actually supported by the current build. Search supports immediate filtering and an explicit clear action.

### Forms and overlays
Use native Compose and HTML controls with visible labels and focus states. Preserve platform keyboard behavior. Error details remain copyable; do not use browser-native alert/confirm/prompt dialogs. Any future destructive confirmation must identify the affected distribution and consequence.

### Iconography
Prefer the existing concise text symbols for navigation until a consistent icon set is introduced. Symbols must be paired with visible labels; avoid emoji as the only meaning-bearing icon.

### Motion
Use short transitions only to communicate page changes or button feedback. Respect reduced-motion preferences on desktop and avoid continuous ambient animation.

### Content and data visualization
Use honest capability language. A catalog profile is not necessarily installed; a downloaded archive is not necessarily a runnable environment; a planned desktop environment is not ready. Explain compatibility and runtime limitations near the relevant action.

## Do's and Don'ts

- **Do:** Make installed state, runtime availability, and compatibility immediately scannable.
- **Do:** Keep Android and desktop colors and action vocabulary aligned.
- **Don't:** Display fake terminal output or claim a runtime is ready when it is not.
- **Don't:** Use decorative terminal chrome, color-only statuses, or marketing copy that obscures limitations.
