---
name: human-ui-design
description: Create, redesign, or critique frontend, app, and mobile web UI so it feels specific, intentional, and human-made rather than generic AI output. Use when an AI coding agent is asked to build or improve websites, native-like apps, mobile apps, mobile web, PWAs, dashboards, landing pages, components, design systems, prototypes, visual polish, UI styling, or design reviews where the user wants less "AI-looking" design, no emoji in UI, stronger product fit, fewer generic cards/gradients, more taste, or a more distinctive interface.
---

# Human UI Design

## Operating Principle

Design from the product's lived context, not from a generic composition template. Before choosing colors, cards, or layout, identify what the interface is for, who uses it, what they need to notice first, and what real-world material should shape the visual language.

## Priority Order

When principles conflict, use this order:

1. Preserve the user's requested functionality and the product's real workflow.
2. Preserve accessibility, readability, responsiveness, and interaction clarity.
3. Preserve the project's established design system when it is intentional; customize only what looks like an untouched preset.
4. Increase product specificity through structure, content, data, and hierarchy before adding decoration.
5. Add personality through controlled choices, not random irregularity.

## Non-Negotiables

- Do not use emoji anywhere in generated UI chrome: navigation, buttons, headings, badges, bullets, empty states, alerts, status, illustrations, or decorative copy.
- Use a real icon system instead of emoji. Prefer the project's existing icon library; Font Awesome is allowed when no established icon set is preferable or when it best fits the concept.
- Do not use abstract blobs, glowing orbs, glass panels, or generic gradients as the main visual idea.
- Do not create nested cards, card walls, or decorative bento grids when a task-specific structure would work better.
- Do not ship placeholder content that hides missing hierarchy, state, density, or edge cases.
- Do not explain the design concept inside the product UI unless that explanation is part of the product itself.

## Output Behavior

When using this skill, make concrete design decisions and implement them. Do not merely explain that the UI should be less generic.

If the user request is broad, briefly choose a direction yourself:

- Name the product archetype: operational dashboard, creative editor, commerce flow, editorial page, portfolio, internal tool, game UI, mobile utility, native app, PWA, or another concrete category.
- Pick a visual stance: quiet and dense, tactile and crafted, cinematic and image-led, precise and technical, editorial and opinionated, playful and kinetic, or another stance that fits.
- Name one thing you will intentionally avoid from the AI-default list.
- Name one signature move tied to the product: a layout structure, data treatment, material cue, interaction model, type pairing, or asset strategy.
- Then build or revise the UI.

## Workflow

1. Inspect the existing product, codebase, screenshots, assets, copy, and user request before designing.
2. State a compact design thesis in your own working notes: audience, domain mood, interaction density, and one non-generic visual idea tied to the product.
3. Apply the non-negotiables before adding polish.
4. Remove AI-default patterns.
5. Build the actual usable interface first, not a marketing explanation of the interface.
6. Verify the result visually in browser screenshots when a local UI can run.

For deeper critique, redesign planning, or a user explicitly asking to remove "AI taste", read `references/anti-ai-ui-rubric.md`.

For app UI, mobile app screens, mobile-first web, responsive mobile views, PWAs, or touch-heavy interfaces, also read `references/app-mobile-ui.md`.

## AI-Default Patterns To Avoid

Treat these as warning signs unless the product context specifically justifies them:

- Purple-blue gradients, glowing orbs, bokeh blobs, glass panels, and decorative abstract SVG waves.
- Centered hero with vague headline, pill eyebrow, two CTA buttons, and a floating card mockup.
- Repeated rounded cards with identical shadows, identical spacing, and no hierarchy.
- Bento grids used as decoration instead of as a meaningful information structure.
- Generic SaaS copy such as "Unlock your potential", "AI-powered insights", or "seamless workflow".
- Default component-library styling that looks like untouched Material, Ant Design, Bootstrap, or Tailwind UI.
- Overly safe typography: generic Inter/Roboto/system stacks everywhere with no product-specific type choice.
- Emoji used as UI icons, labels, decoration, status markers, feature bullets, or empty-state personality.
- Icon soup: many unrelated line icons carrying labels that could be plain text.
- Overly symmetrical layouts where every section has the same rhythm.
- Spacing that feels mathematically generated: every gap is identical or visibly locked to one repeated step.
- Excessive 24-32px border radii, large soft shadows, and one-note palettes.
- Placeholder content that hides the product's real state, edge cases, or data density.
- Decorative visuals that do not reveal the actual product, object, place, state, or workflow.

## Make It Human

Use these moves to create specificity:

- Anchor the visual system in the domain: finance should feel accountable, creative tools tactile, dev tools precise, editorial sites opinionated, operational tools calm and scannable.
- Choose one visual motif with a reason: ledger lines, stage lighting, blueprint grids, instrument panels, print editorial spacing, file-system density, studio contact sheets, map layers, or another product-relevant idea.
- Let information hierarchy create the layout. Put the user's highest-value action, comparison, or decision where the eye lands first.
- Mix scale deliberately: one dominant area, supporting dense areas, and quiet utility zones.
- Break symmetry only when it improves hierarchy, energy, or scan path. Keep visual balance even when the layout is asymmetric.
- Use spacing rhythm, not spacing randomness: pair a small set of deliberate values, and allow occasional non-grid values for optical correction.
- Use real labels, states, numbers, timestamps, statuses, empty states, validation messages, and edge cases.
- Prefer restrained but opinionated typography: clear type pairings, sensible line lengths, and deliberate weight contrast. Avoid default font stacks unless they are already part of the product's system.
- Use color as behavior or meaning, not decoration. Reserve saturated color for status, selection, emphasis, or brand moments.
- Add texture through structure: rules, alignment, tabs, tables, inspector panes, timelines, thumbnails, annotations, or controls that fit the product.
- Keep components ergonomic. Buttons, inputs, tabs, filters, menus, and panels should look ready for repeated use.
- Use a coherent icon system instead of emoji. Font Awesome icons are allowed as a replacement when the project has no established icon library, but match stroke/fill style, sizing, and accessibility labels.

## Frontend Build Rules

When implementing UI:

- Reuse the project's existing framework, component system, icons, routing, data patterns, and CSS conventions.
- If the project uses a default UI library, customize the surrounding composition, density, type, color, borders, states, and content so the result does not look like an untouched preset.
- Use actual visual assets when the product depends on place, object, person, brand, media, or inspection. Prefer real/generative bitmap imagery over abstract SVG decoration.
- Avoid landing-page shells unless the user explicitly asked for one. For apps and tools, the first screen should be the working experience.
- Do not put cards inside cards. Use cards for repeated items, modals, and framed tools; use full-width sections or unframed layout for page structure.
- Keep cards at 8px radius or less unless the existing design system says otherwise.
- Define stable dimensions for boards, grids, controls, counters, tiles, previews, and toolbar buttons so content changes do not shift layout.
- Use familiar icons for icon-worthy actions, with tooltips for non-obvious controls.
- Strictly prohibit emoji in the UI. Do not use emoji for navigation, buttons, badges, headings, bullets, empty states, alerts, status, decoration, or personality. Use text, real imagery, product-specific illustration, or a proper icon library instead.
- Use Font Awesome when an icon replacement is useful and no existing project icon set is preferable. If another icon system is already installed, preserve consistency with that system.
- Do not add remote font dependencies casually. Use existing project fonts first; add or reference distinctive fonts only when the project setup supports it and the choice improves the product's voice.
- Ensure text fits its container at mobile and desktop sizes. Do not scale font size with viewport width.
- Avoid visible instructional text that explains the UI's styling, keyboard shortcuts, or design concept unless the product itself requires it.

## App And Mobile Web Rules

When implementing app UI or mobile web:

- Design the main task for a small viewport first, then expand to tablet and desktop patterns.
- Respect platform conventions for navigation, safe areas, keyboard behavior, gestures, and status bars.
- Keep primary actions reachable and stable. Avoid CTA placement that jumps when content loads or the keyboard opens.
- Use touch targets of at least 44px where the platform allows it.
- Prefer task-specific app structures: tab bars, navigation stacks, sheets, drawers, inspectors, bottom bars, segmented controls, lists, maps, timelines, or media canvases.
- Avoid desktop-first card grids collapsed into a long mobile stack.
- Preserve performance: avoid heavy decorative effects, excessive shadows, large unoptimized images, and layout shifts.
- Test mobile states: empty, loading, error, offline/poor network, long labels, keyboard-open, safe-area inset, and scroll boundaries.

## Redesign Pass

When improving an existing AI-looking UI:

1. Keep the user's intended functionality intact.
2. Identify the top three causes of genericness in the current design.
3. Replace broad decoration with product-specific structure or content.
4. Adjust spacing, type scale, density, and color together; do not only swap colors.
5. Preserve accessibility, contrast, responsive behavior, and interaction affordances.

## Visual QA

Before finishing substantial UI work:

- Run the app when possible and inspect it in a browser.
- Check at least one desktop and one mobile viewport.
- Look for overlap, clipped text, accidental horizontal scrolling, blank media, broken icons, weak contrast, and layouts that collapse into stacked sameness.
- Search modified UI strings for emoji and remove any generated emoji from the interface.
- Confirm icon-only controls have accessible names.
- If the page still reads like a generic template, revise the motif, hierarchy, content specificity, or visual assets before handing it back.
