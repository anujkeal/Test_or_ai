# App And Mobile Web Rules

Use this reference for native app UI, app-like web UI, PWAs, mobile-first pages, responsive mobile views, and touch-heavy interfaces.

## Trigger Cases

Read this file when the task mentions:

- app, mobile app, iOS, Android, React Native, Flutter, SwiftUI, Kotlin, PWA, mobile web, mobile-first, responsive mobile, touch UI, bottom navigation, sheets, tabs, gestures, or safe areas.

## App UI Principles

App UI should feel like software people will use repeatedly, not a compressed landing page.

- Start from the user's main loop: open, scan, decide, act, recover.
- Design around states and transitions: loading, empty, populated, selected, editing, error, offline, permission denied, background refresh.
- Use platform-familiar structures unless the product has a strong reason to diverge.
- Keep persistent controls predictable: tab bars, navigation headers, bottom actions, search, filters, and primary actions should not jump around.
- Avoid decorative hero sections inside apps unless the screen is onboarding, profile, media, or commerce where imagery is part of the task.
- Preserve density where needed. Mobile does not mean sparse; it means carefully ordered.

## Native App Rules

For SwiftUI, UIKit, Jetpack Compose, Android Views, Flutter, React Native, Expo, or similar app stacks:

- Respect safe areas, status bars, notches, home indicators, keyboard avoidance, and platform accessibility settings.
- Use native navigation patterns: stack navigation for drill-down, tabs for stable top-level sections, sheets for focused temporary tasks, drawers only when the app already uses that convention.
- Prefer native controls when they improve accessibility, gesture behavior, focus, and platform feel.
- Keep destructive actions away from common thumb paths and require clear confirmation where risk is high.
- Design for system text scaling. Important controls must survive larger accessibility font sizes.
- Include realistic state handling for permissions, connectivity, sync, stale data, and background work.
- Do not use emoji as icons or personality. Use the platform icon set, the existing app icon set, or Font Awesome when appropriate.

## Mobile Web And PWA Rules

For mobile web, responsive websites, and PWAs:

- Treat the 360-430px viewport as a first-class design target.
- Avoid desktop-first layouts that merely stack cards vertically.
- Use sticky or persistent actions carefully; account for browser chrome, safe-area insets, and virtual keyboard behavior.
- Prefer lightweight CSS and stable layout primitives. Avoid heavy blur, giant shadows, unnecessary animations, and oversized images.
- Keep tap targets at least 44px when practical, with enough spacing to avoid accidental taps.
- Ensure forms are mobile-native: proper input types, labels, validation, autocomplete, keyboard-friendly flow, and visible error recovery.
- Test scroll behavior: nested scrolling, fixed headers, bottom bars, modals, drawers, and sheets should not trap or obscure content.
- For PWA-like experiences, design offline, reconnecting, syncing, installed, and update-available states when relevant.

## Mobile Visual Anti-Patterns

Treat these as AI-template warnings:

- A desktop hero squeezed into a phone viewport.
- Repeated feature cards stacked for several screens with no real task flow.
- Floating CTA buttons that cover content, inputs, or important status.
- Big rounded cards that leave too little information above the fold.
- Icons without labels in unfamiliar navigation.
- Emoji used for feature bullets, tab labels, empty states, or notifications.
- Fake phone mockups used instead of real app states.
- Bottom navigation with too many equal-priority items.

## Mobile-Specific Humanization

Make mobile UI feel authored through usefulness, not decoration:

- Put the first real decision above the fold.
- Use compact but legible hierarchy: section label, primary value, supporting metadata, next action.
- Use thumb-friendly controls: bottom sheets, segmented filters, stepper controls, swipe actions only when discoverable.
- Use realistic content density: names, timestamps, statuses, counts, previews, errors, and partial data.
- Use media ratios that fit the task: square thumbnails for grids, 3:2 for editorial previews, tall crops for stories, full-bleed only when inspection is not required.
- Prefer subtle dividers, grouped rows, and state color over large decorative panels.

## App QA Checklist

Before delivery, check:

- No emoji appears in UI strings, icons, bullets, empty states, or status.
- Primary task works at 360px, 390px, and a tablet or desktop width when applicable.
- Touch targets are reachable and large enough.
- Text still fits with long labels and larger font settings.
- Keyboard-open state does not hide active fields or actions.
- Safe areas do not clip navigation, bottom bars, sheets, or floating actions.
- Loading, empty, error, offline, and permission states are represented when relevant.
- Icon-only controls have accessible names.
- The screen does not look like a stacked desktop landing page.
