---
name: Forgeline
description: A fast, discovery-first Android client for code forges, set in Friendly Type, a soft, readable world with one vivid ember accent.
colors:
  ground: "#FAFAFB"
  ink: "#1E1B2E"
  ink-muted: "#5A566B"
  accent: "#B83A17"
  thumb: "#FF6B3D"
  on-thumb: "#1E1B2E"
  track: "#1E1B2E0F"
  surface: "#F0EFF3"
  field-today: "#FFE6DC"
  field-week: "#ECE6FF"
  field-month: "#DDF3EA"
  ground-dark: "#15131C"
  ink-dark: "#F1EEF6"
  ink-muted-dark: "#A7A2B8"
  accent-dark: "#FF8A5C"
  on-thumb-dark: "#1A1216"
  track-dark: "#FFFFFF12"
  surface-dark: "#221F2C"
  field-today-dark: "#2B1E1F"
  field-week-dark: "#221F33"
  field-month-dark: "#1A2724"
  ground-amoled: "#000000"
  surface-amoled: "#16141D"
typography:
  title:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "34sp"
    fontWeight: 700
    lineHeight: "38sp"
    letterSpacing: "-0.01em"
  name:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "21sp"
    fontWeight: 700
    lineHeight: "25sp"
  figure:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "16sp"
    fontWeight: 700
    lineHeight: "20sp"
    fontFeature: "tnum"
  control:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "15sp"
    fontWeight: 500
    lineHeight: "20sp"
  body:
    fontFamily: "Lexend, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "24sp"
  secondary:
    fontFamily: "Lexend, sans-serif"
    fontSize: "14sp"
    fontWeight: 400
    lineHeight: "20sp"
  meta:
    fontFamily: "Lexend, sans-serif"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "18sp"
    fontFeature: "tnum"
  label:
    fontFamily: "Lexend, sans-serif"
    fontSize: "13sp"
    fontWeight: 500
    lineHeight: "18sp"
rounded:
  snackbar: "16dp"
  row: "20dp"
  thumb: "24dp"
  field: "28dp"
  pill: "50%"
spacing:
  xs: "4dp"
  sm: "8dp"
  md: "12dp"
  lg: "16dp"
  gutter: "20dp"
  xl: "24dp"
  notice: "32dp"
components:
  header-field:
    backgroundColor: "{colors.field-today}"
    textColor: "{colors.ink}"
    typography: "{typography.title}"
    rounded: "{rounded.field}"
    padding: "16dp 8dp 20dp 20dp"
  switch-track:
    backgroundColor: "{colors.track}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.control}"
    rounded: "{rounded.field}"
    padding: "4dp"
    height: "48dp"
  switch-thumb:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    typography: "{typography.control}"
    rounded: "{rounded.thumb}"
  row:
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
    padding: "14dp 4dp 4dp 12dp"
  row-pressed:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
  button-filled:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    typography: "{typography.control}"
    rounded: "{rounded.pill}"
    padding: "0 24dp"
    height: "48dp"
  link-resume:
    textColor: "{colors.accent}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "0 12dp"
    height: "48dp"
  marker-stopped-here:
    backgroundColor: "{colors.field-today}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.pill}"
    padding: "6dp 12dp"
  snackbar:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.snackbar}"
---

# Design System: Forgeline

## Overview

**Creative North Star: "Friendly Type"**

Forgeline is mostly text, so the world is built to be pleasant to read. A warm, rounded display face (Gabarito) carries names, titles and figures, a very legible text face (Lexend) carries every line of reading, and both sit on soft surfaces with one vivid ember accent. The owner's word for the result is comfy: soft, friendly, modern, and vivid enough to feel alive, never heavy or corporate.

Hierarchy comes from type, space and tone, never from lines or boxes. Colour arrives at scale once per surface, as a soft tint that owns the header field and changes with the section (ember today, lilac this week, mint this month); ember then marks only momentum and selection. Density is comfortable: one reading column, generous row rhythm, descriptions held to a readable measure. Motion is soft and springy, a little lively and never bouncy, and it disappears entirely under the system Remove animations setting.

Native Android behaviour stays native: system Back, edge-to-edge insets, 48dp touch targets, and sp type that follows the font scale (rows grow and wrap at 1.3x and 2.0x; nothing is cut). Confirmed rejections: brutalist or boxy layouts, split-flap or departure-board and other literal metaphors borrowed from other domains, harsh aggressive contrast, condensed signage display faces, and a corporate feel.

Scope today: the world lives in `core/ui/.../soft/Soft.kt` (`SoftColors`, `SoftType`, `SoftTheme`) and is expressed first by Trending. Other screens and the navigation bar still wear the old Material look until they are migrated, and the dynamic colour setting does not affect screens in this world; only the light, dark and AMOLED choice carries over.

**Key Characteristics:**
- Gabarito 500/700 for display, names and figures; Lexend 400/500 for all reading text.
- One soft tinted header field per surface, 28dp rounded bottom corners, tint keyed to the section.
- One ember accent for momentum (ranks, gains, starred, resume) and one ember thumb for selection.
- Nothing boxed: no dividers, no card outlines, no stacked cards.
- Pill controls and a 20dp soft surface for pressed or focused rows.
- Tabular figures wherever numbers line up.
- Every text/background pair at least 4.5:1, enforced by `SoftTest`.

## Colors

A cool, near-white (or violet-black) ground with plum-navy ink, lifted by soft pastel section tints and one hot ember.

### Primary
- **Ember Thumb** (`thumb`): the saturated ember of selection and commitment. It fills the period-switch thumb, filled buttons (Retry, Refresh) and the dot on the Stopped-here marker. Identical in both themes; text on it is `on-thumb` (plum-navy in light, near-black in dark).
- **Readable Ember** (`accent` / `accent-dark`): ember tuned per theme to read as text. Light uses a deep burnt ember so it holds 4.5:1 on the ground and on the pressed surface; dark uses a bright coral ember. It marks momentum only: rank numbers, "+gained" figures, the filled star, the "Resume at #n" link, and the pull-to-refresh spinner.

### Secondary
- **Section Tints** (`field-today`, `field-week`, `field-month`, with `-dark` variants): peach, lilac and mint in light; ember-, violet- and green-tinged near-blacks in dark. One owns the header field of a surface, chosen by section (Trending's period), and crossfades when the section changes. `field-today` also backs the Stopped-here marker.

### Neutral
- **Soft White / Violet Black** (`ground` / `ground-dark`, `ground-amoled`): the page. AMOLED swaps the ground to true black and the surface to `surface-amoled`.
- **Plum-Navy Ink / Lavender White** (`ink` / `ink-dark`): all primary text, titles, names and descriptions; also the snackbar background (with ground as its text). Handed to leftover Material pieces as the content colour, so their icons and ripples take the palette.
- **Muted Ink** (`ink-muted` / `ink-muted-dark`): owners, "Updated…", meta stats, unselected period labels, the unstarred star.
- **Soft Surface** (`surface` / `surface-dark`): the pressed or focused row, loading shapes, avatar placeholders, the pull-to-refresh container.
- **Track** (`track` / `track-dark`): a 6-7% ink or white veil laid over the field for the switch's track, so it follows whichever tint sits beneath.

### Named Rules
**The Field Tint Rule.** Colour at scale lives in the header field tint, one tint per surface, keyed to the section. Ember elsewhere is a mark (rank, gain, starred, resume, thumb), never a background wash, border or scattered decoration.

**The Readable Everywhere Rule.** Every text/background pair the world draws holds at least 4.5:1 in light, dark and AMOLED, on the ground, on every field tint, on the track over each tint, and on the pressed surface. `SoftTest` enforces it; a new pair gets a new assertion.

## Typography

**Display Font:** Gabarito (bundled; fallback sans-serif)
**Body Font:** Lexend (bundled; fallback sans-serif)

**Character:** Gabarito is warm, round and a little playful at weight; Lexend is open, even and made for comfortable reading. Display is friendly, text is effortless.

### Hierarchy
- **Title** (Gabarito 700, 34sp/38sp, -0.01em): the surface title in the header field ("Trending"). Marked as a heading.
- **Name** (Gabarito 700, 21sp/25sp): the repository name in a row (up to two lines), and Notice titles.
- **Figure** (Gabarito 700, 16sp/20sp, tabular): ranks and "+gained" counts, in ember.
- **Control** (Gabarito 500, 15sp/20sp): switch labels and filled button text. Switch labels auto-shrink (down to 9sp) only when a single word cannot fit at very large font scales.
- **Body** (Lexend 400, 15sp/24sp): descriptions and Notice bodies. Measure capped at 580dp (about 65-75 characters); three lines at 1.0x font scale, uncapped when text is enlarged.
- **Secondary** (Lexend 400, 14sp/20sp): owner above the name, the "Updated…" status line.
- **Meta** (Lexend 400, 13sp/18sp, tabular): language, stars and forks on the meta line.
- **Label** (Lexend 500, 13sp/18sp): the resume link and the Stopped-here marker.

### Named Rules
**The Two Weights Rule.** Gabarito ships in exactly two weights, Medium 500 for controls and Bold 700 for everything else it sets; Lexend in Regular 400 and Medium 500. No other weights, no other families, no condensed faces.

**The Tabular Figures Rule.** Every number meant to be compared down a list (ranks, gains, stats) is set with `tnum`, so columns line up without a grid.

## Layout

A single centred reading column. Content is capped at 720dp wide on tablets and large screens, with descriptions further capped at 580dp; the header field tint still runs edge to edge behind it. The screen gutter is 20dp at the start and 8dp at the end, where icon buttons supply their own 48dp targets. Rows sit 8dp in from the column edge so the pressed surface has room, then pad 12dp start and 14dp top; rank sits in a 30dp column and the text column aligns after it.

Spacing moves in 4dp steps (4, 8, 12, 16, 20, 24, 32). The meta line puts stats left (14dp apart), the star toggle on the right edge, and builders' avatars (22dp, overlapping by 6dp) just before the toggle only when everything fits; crowded rows drop the builders first, then wrap the stats. Content draws edge to edge: the field sits under the status bar, which gets a 94% ground veil once the field has scrolled away; the list pads for the navigation bar.

## Elevation & Depth

Flat and tonal. Depth comes from the field tint against the ground and the soft surface appearing under a pressed row; there are no card elevations and no outlines.

### Shadow Vocabulary
- **Ember Glow** (6dp Compose shadow, ambient and spot colour set to `thumb`, on the 24dp thumb shape): the only shadow in the world, a soft ember halo that lifts the selection thumb off its track.

### Named Rules
**The Nothing Boxed Rule.** No divider lines, no card outlines, no stacked cards. Rows are separated by space and type; a surface appears only as feedback (press, focus) or as a loading shape. The 2dp ground-coloured ring around overlapping avatars is a cut-out that separates faces, not a border, and is the only stroke in the world.

## Shapes

Round and soft throughout. The header field has 28dp rounded bottom corners and square top (it runs under the status bar). The switch track is 28dp, its thumb and segment clips 24dp. Rows clip to 20dp. Buttons, links, the Stopped-here marker and loading bars are full pills; the language dot, marker dot, avatars and loading rank are circles. The snackbar is 16dp. No sharp corners, no square tiles.

## Components

Material 3 composables (Icon, IconButton, IconToggleButton, Snackbar, PullToRefresh) remain as the base, restyled with Soft colours; the distinctive pieces are custom.

### Header Field
- **Character:** the one place colour arrives at scale.
- **Shape:** edge-to-edge tint, 28dp bottom corners.
- **Content:** Title left, search icon button (ink) right, 16dp gap, then the period switch.
- **Behaviour:** the tint crossfades between section tints over 400ms (instant when animations are off).

### Period Switch (signature)
- **Style:** a full-width pill track (`track` over the field, 4dp inset) with equal segments and one ember thumb with the Ember Glow.
- **Labels:** Control type, `ink-muted` unselected, `on-thumb` selected, centred.
- **Size:** at least 48dp tall; grows to the tallest label at large font scales so no word is cut.
- **Behaviour:** the thumb springs to the chosen segment (damping 0.8, medium-low stiffness). Exposed as a selectable group of tabs.

**The Ember Thumb Rule.** Selection is the springing ember thumb. Don't show selection with underlines, outlines, checkmarks or tinted text alone.

### Rows (list items)
- **Anatomy:** ember rank (figure) in a 30dp column; owner (secondary, muted) above name (name, ink); ember "+gained" figure on the right; description (body) below; meta line last.
- **Rest:** transparent on the ground; no divider, no card.
- **Pressed / Focused:** `surface` fills the row, clipped to 20dp. No ripple.
- **Entry:** after a section change, the first eight rows rise 16dp and fade in from 35% opacity, staggered 35ms, on the soft spring.

**The Soft Press Rule.** Pressed or focused rows get a 20dp soft surface in `surface`, never a ripple. Every text colour on it is tested to 4.5:1.

### Buttons
- **Filled:** full pill, `thumb` fill, `on-thumb` Control text, 24dp horizontal padding, 48dp minimum height. Used for the one action in a Notice (Retry, Refresh).
- **Link:** the resume link, Label type in `accent` with a 16dp down arrow, pill-clipped, 48dp tall.
- **Icon:** Material icon buttons with 48dp targets; icons in `ink` (search) or `accent` / `ink-muted` (star toggle, filled or outlined star).

### Stopped-here Marker
A small pill in `field-today` with an 8dp ember dot and Label text in ink, inset under the rank column, left where the last browse stopped.

### Notices (empty and error)
No illustration and no box: a Name-style title, a Body line in muted ink, 20dp gap, then one filled pill button, in the reading column with 20dp/32dp padding.

### Loading
Five static skeleton rows of pill bars and a circle in `surface`, shaped like real rows. No shimmer.

### Snackbar
Inverted: `ink` background, `ground` text, 16dp corners, action colour `thumb`.

**The One Spring Rule.** Things that move use one soft spring, lively but never bouncy (damping 0.8-0.85, medium-low stiffness); colour changes crossfade over 400ms. All of it is skipped when the system animator scale is 0 (Remove animations).

## Do's and Don'ts

### Do:
- **Do** take every colour and type style from `Soft.colors` and `Soft.type` inside `SoftTheme`; never hard-code a hex in a screen.
- **Do** give each surface one header field tint with 28dp bottom corners, and keep ember for momentum marks and the selection thumb.
- **Do** set names, titles and figures in Gabarito (500/700 only) and all reading text in Lexend (400/500).
- **Do** use `tnum` figures for ranks, counts and stats.
- **Do** separate list items with space and type alone, and show press or focus with the 20dp `surface` fill.
- **Do** use the soft spring for movement and drop all motion when Remove animations is on.
- **Do** keep 48dp touch targets, sp type that grows with the font scale, system Back and edge-to-edge insets.
- **Do** add a `SoftTest` assertion for every new text/background pair (4.5:1 minimum).

### Don't:
- **Don't** draw divider lines, card outlines, stacked cards or square tiles; nothing is boxed.
- **Don't** build brutalist or boxy layouts, or borrow literal metaphors from other domains (split-flap, departure boards).
- **Don't** use harsh, aggressive contrast or condensed signage display faces.
- **Don't** let it feel corporate.
- **Don't** scatter ember as backgrounds, borders or decoration.
- **Don't** add a third weight of Gabarito or Lexend, or another family.
- **Don't** give rows a ripple.
- **Don't** mark selection with anything other than the ember thumb.
