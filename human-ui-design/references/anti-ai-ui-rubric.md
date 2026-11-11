# Anti-AI UI Rubric

Use this reference when critiquing or redesigning UI that should feel less generic. Score each area mentally from 0-2, then prioritize the lowest areas.

## Hard Gate Audit

Fail the design and revise before delivery if any item is true:

- Generated UI chrome contains emoji.
- The first viewport could be mistaken for a generic AI SaaS template.
- Decoration is doing more work than content, hierarchy, or interaction.
- Cards, gradients, shadows, or icons repeat without a product-specific reason.
- The interface hides real states, dense information, or edge cases behind placeholder copy.
- The design personality makes the UI harder to read, operate, or adapt responsively.

## 1. Product Specificity

0: The UI could belong to any SaaS, portfolio, dashboard, or app.
1: The UI has some relevant labels or data, but the structure is still generic.
2: The layout, content, controls, and visual details clearly belong to this product domain.

Improve by:

- Replace placeholder sections with real workflow states.
- Use domain nouns in navigation, filters, table headers, cards, empty states, and actions.
- Add realistic data density: statuses, timestamps, owners, units, file names, locations, totals, version numbers, or constraints.
- Let the most important user decision define the first viewport.

## 2. Compositional Intent

0: Centered hero, card grid, or evenly spaced sections with no clear reason.
1: Clear hierarchy exists, but the page still follows a familiar template rhythm.
2: The composition has a primary focal area, secondary tools, and quiet supporting zones.

Improve by:

- Create asymmetry around actual importance, not decoration.
- Break perfect centering when a left rail, inspector, preview, timeline, workspace, or command surface would better serve the task.
- Vary section rhythm: dense tool area, open preview, compact metadata, persistent controls.
- Use tables, sidebars, timelines, inspectors, maps, canvases, split panes, or command bars when they fit the task.
- Avoid repeating the same card size, shadow, and text stack across the whole page.
- Use optical spacing. A few values outside a rigid 8px rhythm can feel more authored, but they must improve balance or readability.

## 3. Material Honesty

0: Abstract gradients, blobs, fake glass, and decorative shapes carry the design.
1: Some real UI or imagery appears, but decoration still dominates.
2: Visual assets reveal the product, object, place, media, state, or workflow.

Improve by:

- Use screenshots, thumbnails, product imagery, generated bitmap assets, maps, charts, file previews, or real interface states.
- Replace abstract decoration with structural texture: grid lines, rulers, panels, annotations, ledgers, dividers, tabs, labels.
- Make media inspectable: avoid overly dark, blurred, cropped, or atmospheric images when users need to understand the subject.

## 4. Typography And Voice

0: Oversized generic headline, vague subcopy, repeated title/body card copy.
1: Copy is relevant, but type and voice lack product character.
2: Type scale, labels, microcopy, and information hierarchy feel authored.

Improve by:

- Use concise, concrete labels over marketing abstractions.
- Reserve large type for true hero surfaces; keep panels, sidebars, and dense tools compact.
- Choose type with product intent. Consider serif/sans contrast for editorial or warm brands, geometric sans for technical tools, humanist sans for approachable products, and monospace only where data, code, or measurement benefits.
- Avoid falling back to Inter, Roboto, Arial, or a plain system stack as the only type decision unless the existing design system requires it.
- Use weight, spacing, and alignment before adding color or decoration.
- Include useful microcopy: validation, status, last updated, limits, warnings, progress, or next action.

## 5. Color Discipline

0: One dominant hue family, usually purple-blue, beige, slate, or espresso.
1: Palette is pleasant but decorative; meaning is inconsistent.
2: Color supports brand, state, selection, comparison, and focus.

Improve by:

- Assign saturated color to behavior: active, selected, warning, success, risk, ownership, freshness.
- Use neutrals for structure and reading surfaces.
- Consider an 80/15/5 balance: mostly neutral reading surfaces, a secondary supporting tone, and a small amount of strong accent.
- Prefer off-white, warm gray, cool gray, ink, or product-specific neutrals over pure white/black when that improves atmosphere and contrast.
- Check contrast after every color change.
- Add small, purposeful accent colors instead of washing the entire page in a gradient.

## 6. Interaction Believability

0: Static-looking page with fake controls and no edge states.
1: Controls exist but do not reflect realistic workflows.
2: The interface looks ready for repeated use and imperfect data.

Improve by:

- Include hover, active, selected, disabled, loading, empty, error, and overflow states where relevant.
- Add filters, sorting, tabs, segmented controls, menus, toggles, sliders, steppers, or inspectors according to the task.
- Keep toolbars stable and scannable.
- Make primary and secondary actions visually distinct without relying on huge CTA blocks.
- Avoid untouched component-library defaults. Tune density, borders, focus rings, empty states, selected rows, disabled controls, and error messages.

## 7. Iconography Discipline

0: Emoji are used as icons, decoration, bullets, status, or personality.
1: Icons are present, but styles are mixed or too many icons compete with text.
2: Icons are purposeful, stylistically coherent, accessible, and never emoji.

Improve by:

- Remove all emoji from the UI surface.
- Use text for labels when an icon adds no recognition value.
- Use the project's existing icon set when available.
- Font Awesome icons are allowed as replacements when the project has no established icon library or when a Font Awesome icon best fits the concept.
- Keep icon size, weight, fill/stroke style, alignment, and hover/focus states consistent.
- Add accessible names or hidden text for icon-only controls.
- Do not mix emoji with icons. The UI should have one deliberate visual language.

## 8. Responsive Craft

0: Desktop composition simply stacks into a long mobile page.
1: Mobile is usable but loses hierarchy or causes cramped text.
2: Mobile preserves the task's core action and scan path.

Improve by:

- Reorder content around the mobile user's first decision.
- Collapse secondary controls into menus or drawers.
- Keep fixed-format elements stable with explicit dimensions and aspect ratios.
- Test real longest labels, not ideal short text.

## 9. Controlled Human Irregularity

0: Everything is perfectly centered, equally spaced, equally rounded, and equally important.
1: The design has some variation, but it feels decorative or arbitrary.
2: Small irregularities support hierarchy, warmth, tactility, or editorial rhythm.

Improve by:

- Use asymmetry, offset, varied media ratios, or uneven section rhythm to support the content.
- Vary border weight, divider style, and panel treatment when it clarifies importance.
- Use subtle texture through real material, image grain, paper-like surfaces, table rules, map layers, annotations, or grid overlays.
- Let one or two elements feel authored, but avoid sprinkling random rotations, blobs, squiggles, or hand-drawn marks across serious product UI.
- Keep accessibility and usability above personality. An "imperfect" choice must still be readable, aligned enough to scan, and easy to operate.

## Direction Picker

Choose one direction when the user gives little guidance. Do not mix all of them.

| Direction | Best for | Useful moves |
| --- | --- | --- |
| Warm Crafted | lifestyle, education, community, creative services | warm neutrals, tactile surfaces, serif/sans contrast, gentle asymmetry |
| Editorial Bold | media, portfolios, launches, cultural products | strong type hierarchy, image-led composition, asymmetric grid, decisive white space |
| Quiet Operational | B2B, internal tools, finance, logistics | dense information, restrained palette, tables, filters, status color, precise controls |
| Technical Sharp | developer tools, infrastructure, analytics, security | monospace accents, measured grids, dark/light contrast, command bars, inspection panels |
| Playful Kinetic | games, youth products, creative experiments | strong accent color, motion, irregular rhythm, custom illustration, expressive states |

## Caution Rules

Some anti-AI moves become AI-looking when overused:

- Do not add organic blobs or glowing decorations as a default fix.
- Do not rotate or offset controls that users must read or click repeatedly.
- Do not force non-standard fonts if the project already has a strong brand or performance constraints.
- Do not make every spacing value irregular. Rhythm matters more than novelty.
- Do not use texture to hide weak hierarchy or fake specificity.

## Redesign Recipe

When a UI feels AI-generated:

1. Name the generic template it resembles.
2. Identify the user's real job on the screen.
3. Choose a domain-specific structure before changing colors.
4. Replace decorative surfaces with useful information surfaces.
5. Tune density, type, spacing, and color as one system.
6. Remove all generated emoji from UI chrome.
7. Verify in desktop and mobile screenshots.
