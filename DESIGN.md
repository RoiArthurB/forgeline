---
name: Forgeline
description: A fast, discovery-first Android client for code forges, set in Ink Compact, an ink band over a quick hairline-ruled list with one marigold accent.
colors:
  ground: "#F5F4F6"
  ink: "#16181A"
  ink-muted: "#606065"
  accent: "#8F5600"
  thumb: "#F5A524"
  on-thumb: "#16181A"
  track: "#16181A0F"
  surface: "#E8E7EB"
  raised: "#FFFFFF"
  hairline: "#DCDADF"
  accent-soft: "#FCE9C0"
  field-warm: "#FCE6DE"
  field-cool: "#E7E4F6"
  field-fresh: "#DDF0E4"
  band: "#16181A"
  ground-dark: "#111113"
  ink-dark: "#EDEDF0"
  ink-muted-dark: "#9C9BA3"
  accent-dark: "#FFC062"
  track-dark: "#FFFFFF14"
  surface-dark: "#1F1F24"
  raised-dark: "#1D1D21"
  hairline-dark: "#2A2A30"
  accent-soft-dark: "#3A2C12"
  field-warm-dark: "#3A221C"
  field-cool-dark: "#25233A"
  field-fresh-dark: "#18291F"
  band-dark: "#24242A"
  ground-amoled: "#000000"
  surface-amoled: "#141417"
  raised-amoled: "#17171A"
  hairline-amoled: "#232327"
  band-amoled: "#17171A"
  band-black: "#000000"
  band-white: "#FFFFFF"
  band-white-dark: "#EDEDF0"
  on-dark-band-muted: "#A6A5AB"
  on-dark-band-surface: "#303037"
  on-dark-band-hairline: "#34343B"
  on-light-band-muted: "#5E5D63"
  on-light-band-surface: "#DDDCE1"
  on-light-band-hairline: "#D3D1D7"
typography:
  title:
    fontFamily: "Geist, sans-serif"
    fontSize: "28sp"
    fontWeight: 600
    lineHeight: "32sp"
    letterSpacing: "-0.03em"
  detail-title:
    fontFamily: "Geist, sans-serif"
    fontSize: "24sp"
    fontWeight: 600
    lineHeight: "29sp"
    letterSpacing: "-0.02em"
  hero:
    fontFamily: "Geist, sans-serif"
    fontSize: "22sp"
    fontWeight: 600
    lineHeight: "27sp"
    letterSpacing: "-0.02em"
  name:
    fontFamily: "Geist, sans-serif"
    fontSize: "16sp"
    fontWeight: 600
    lineHeight: "21sp"
    letterSpacing: "-0.005em"
  section:
    fontFamily: "Geist, sans-serif"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "18sp"
  control:
    fontFamily: "Geist, sans-serif"
    fontSize: "14sp"
    fontWeight: 600
    lineHeight: "20sp"
  figure:
    fontFamily: "Geist, sans-serif"
    fontSize: "13sp"
    fontWeight: 600
    lineHeight: "18sp"
    fontFeature: "tnum"
  body:
    fontFamily: "Geist, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "22sp"
  lead:
    fontFamily: "Geist, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "22sp"
  row-title:
    fontFamily: "Geist, sans-serif"
    fontSize: "15sp"
    fontWeight: 400
    lineHeight: "20sp"
  secondary:
    fontFamily: "Geist, sans-serif"
    fontSize: "13.5sp"
    fontWeight: 400
    lineHeight: "19sp"
  label:
    fontFamily: "Geist, sans-serif"
    fontSize: "12.5sp"
    fontWeight: 600
    lineHeight: "17sp"
  meta:
    fontFamily: "Geist, sans-serif"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "16sp"
    fontFeature: "tnum"
  tag:
    fontFamily: "Geist, sans-serif"
    fontSize: "11.5sp"
    fontWeight: 600
    lineHeight: "16sp"
  code:
    fontFamily: "monospace"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "20sp"
  code-dense:
    fontFamily: "monospace"
    fontSize: "12sp"
    fontWeight: 400
    lineHeight: "18sp"
  gutter:
    fontFamily: "monospace"
    fontSize: "11sp"
    fontWeight: 400
    lineHeight: "18sp"
    fontFeature: "tnum"
rounded:
  row: "0dp"
  xs: "4dp"
  tag: "5dp"
  control: "6dp"
  tile: "8dp"
  lg: "10dp"
  sheet: "12dp"
  round: "50%"
spacing:
  hair: "2dp"
  xs: "4dp"
  sm: "6dp"
  md: "8dp"
  row-v: "10dp"
  gap: "12dp"
  gutter: "16dp"
  band-edge: "18dp"
  conversation: "20dp"
  notice: "28dp"
  row-text: "60dp"
components:
  band-header:
    backgroundColor: "{colors.band}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.title}"
    rounded: "{rounded.row}"
    padding: "4dp 8dp 0 18dp"
  band-header-detail:
    backgroundColor: "{colors.band}"
    textColor: "{colors.ink-dark}"
    typography: "{typography.detail-title}"
    rounded: "{rounded.row}"
    padding: "4dp 8dp 14dp 4dp"
  tab:
    textColor: "{colors.on-dark-band-muted}"
    typography: "{typography.control}"
    padding: "10dp 10dp"
    height: "48dp"
  tab-selected:
    textColor: "{colors.ink-dark}"
    typography: "{typography.control}"
    padding: "10dp 10dp"
    height: "48dp"
  tab-underline:
    backgroundColor: "{colors.thumb}"
    height: "3dp"
  nav-bar:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.meta}"
    height: "56dp"
  nav-item-selected:
    textColor: "{colors.ink}"
    typography: "{typography.meta}"
    height: "56dp"
  nav-indicator:
    backgroundColor: "{colors.thumb}"
    height: "3dp"
  nav-rail:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.meta}"
    width: "80dp"
  row:
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
    padding: "10dp 16dp"
  row-pressed:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
  tile:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.tile}"
    size: "32dp"
  tile-needs-you:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent}"
    rounded: "{rounded.tile}"
    size: "32dp"
  unread-dot:
    backgroundColor: "{colors.thumb}"
    rounded: "{rounded.round}"
    size: "7dp"
  comment-avatar:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.tile}"
    size: "28dp"
  button-filled:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 16dp"
    height: "40dp"
  button-filled-disabled:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    height: "40dp"
  button-tonal:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 14dp"
    height: "40dp"
  button-final:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 16dp"
    height: "40dp"
  button-icon:
    textColor: "{colors.ink}"
    rounded: "{rounded.tile}"
    size: "40dp"
  send-tile:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    rounded: "{rounded.tile}"
    size: "40dp"
  send-tile-quiet:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.tile}"
    size: "40dp"
  check-on:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    rounded: "{rounded.control}"
    size: "22dp"
  check-off:
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.control}"
    size: "22dp"
  text-field:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink}"
    typography: "{typography.lead}"
    rounded: "{rounded.control}"
    padding: "0 14dp"
    height: "48dp"
  choice-pill:
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 4dp 0 8dp"
    height: "48dp"
  tag:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.tag}"
    rounded: "{rounded.tag}"
    padding: "2dp 7dp"
  state-pill:
    backgroundColor: "{colors.field-fresh}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.tag}"
    padding: "3dp 8dp 3dp 6dp"
  gain-figure:
    backgroundColor: "{colors.accent-soft}"
    textColor: "{colors.accent}"
    typography: "{typography.figure}"
    rounded: "{rounded.tag}"
    padding: "0 5dp"
  repo-panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.tile}"
    padding: "10dp 12dp"
  left-off-mark:
    backgroundColor: "{colors.field-warm}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.control}"
    padding: "6dp 12dp"
  selection-bar:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    typography: "{typography.control}"
    rounded: "{rounded.tile}"
    padding: "0 4dp"
  snackbar:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.tile}"
  menu:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.tile}"
---

# Design System: Forgeline

## Overview

**Creative North Star: "Ink Compact"**

Every screen starts on an ink band that carries its name and its one axis; below it runs a fast, hairline-ruled list in which every item opens with a big icon tile. One typeface (Geist) sets everything, one marigold accent marks what matters, and corners stay small. The world exists to be quick to read and quick to clear: the Inbox shows what needs you by its marigold tiles and dots, Trending fits about seven repositories to a screen, and an issue is answered with a send arrow rather than a big button.

Density is compact on purpose: 32dp tiles, 10dp row padding, one-pixel rules instead of air. Hierarchy comes from weight (Geist Regular against SemiBold), the band against the page, and the tile at the head of each row. Colour at scale is only the band; marigold is a fill for a handful of marks, and warm, cool and fresh tints survive only as meaning on small things (a tile's state, a Feed state tag, a swipe). Motion is one soft spring and disappears entirely under the system Remove animations setting.

Native Android stays native: system Back, edge-to-edge insets, 48dp touch targets, sp type that follows the font scale (rows grow and wrap at 1.3x and 2.0x; nothing is cut). Confirmed rejections: brutalist or boxy layouts, literal metaphors borrowed from other domains (departure boards, split-flap), condensed signage type, Material 3's rounded pills and tinted-background selection, and big text pills for small actions.

Scope: the whole app. The world lives in `core/ui/.../soft/Soft.kt` (`SoftColors`, `SoftType`, `onBand()`, `withHeader()`, `amoled()`) and `SoftComponents.kt` (`SoftTokens` and the shared pieces). `ForgelineTheme` fills Material's colour roles, type scale (`SoftTypography`, all Geist) and shapes (`SoftShapes`, 4-12dp) from it, so the Material pieces still in use (menus, sheets, snackbars, pull-to-refresh, progress) take the palette. Appearance settings are Theme (System, Light, Dark), Header (Ink, True black, White) and Pure black.

**Key Characteristics:**
- An ink band heads every screen; everything on it is drawn in the band's own palette (`onBand()`).
- Geist in two weights only, 400 and 600; system monospace for code.
- One marigold fill: the selected tab's underline, the navigation bar's selected bar, unread dots, the send tile, the primary button, the check.
- Selection is a bold label over a 3dp marigold underline, never a tinted chip.
- Things are 8dp tiles; people's faces are round, except comment authors' 28dp rounded squares.
- Rows run edge to edge between one-pixel hairlines that start at the text edge (60dp).
- Small corners: 5dp tags, 6dp controls, 8dp tiles, 12dp at most.
- Every text/background pair at least 4.5:1 in every theme and header style, enforced by `SoftTest`.

## Colors

A cool near-white (or near-black) page under a near-black ink band, with one marigold for marks and a deep amber that reads as text.

### Primary
- **Marigold** (`thumb`): the one accent fill, identical in every theme. It fills the 3dp underline under the chosen tab, the 3dp bar over the selected navigation tab (and on the rail's edge), the unread dot, the send tile, the filled button, the on state of the check, a picked Inbox thread's tile, and the Undo action on the light theme's snackbar. Text and icons on it are `on-thumb` ink. On the light page it is about 1.9:1, so it never carries meaning alone: a bold label, a filled icon or a bold title always says the same thing.
- **Deep Amber / Light Amber** (`accent` / `accent-dark`): marigold tuned to read as text, 4.5:1 or better on the page, tiles and tints. It sets the Inbox's "Mentioned" and "Review requested" reasons, a needs-you tile's glyph, Trending's "+gained" figure, links in Markdown (underlined), the text cursor, error lines, the chosen ring in a choice row, the check in a menu, spinners.
- **Marigold Tint** (`accent-soft` / `accent-soft-dark`): marigold's soft tint, behind a tile that needs you, the "Needs you" count and the "+gained" figure.

### Secondary
- **Meaning Tints** (`field-warm`, `field-cool`, `field-fresh`, with `-dark` variants): peach, lilac and mint in light; ember-, violet- and green-tinged near-blacks in dark. Used by meaning only, on small things: an issue or pull request tile (fresh open, cool merged, warm closed; quiet `surface` for drafts and what was closed in shared rows), Feed state tags (fresh open, approved; cool merged, comment, announcement; warm closed, changes requested, release tag), the swipe under an Inbox row (fresh to mark read, warm for done), timeline event badges, and the warm "Where you left off" mark.

### Neutral
- **Ink Band** (`band`, `band-dark`, `band-amoled`; `band-black`, `band-white`, `band-white-dark` by the Header setting): the band at the top of every screen and the status bar's scrim once the header scrolls away. Light: ink over the light body. Dark: a lifted charcoal a step lighter than the page, so it still reads as the top. AMOLED: a near-black band on true black.
- **Band Palette** (`on-dark-band-*`, `on-light-band-*`): what `onBand()` hands to everything on the band: light ink, a muted ink, a tag surface and a hairline tuned to read on the band itself; a dark band gets light ink whichever theme the page is in, a white band dark ink.
- **Page** (`ground` / `ground-dark` / `ground-amoled`): the body, the navigation bar and rail, sheets.
- **Ink** (`ink` / `ink-dark`): titles, names, descriptions, icons; also the selection bar, the final-action button and the snackbar.
- **Muted Ink** (`ink-muted` / `ink-muted-dark`): source lines, times, meta, read titles, unselected tabs and navigation, placeholders. Dark enough to read on a tile, a tint and a pressed row, not only on the page.
- **Tile Surface** (`surface` / `surface-dark` / `surface-amoled`): icon tiles, a pressed row, tags, tonal buttons, loading shapes, code, the Feed's repository panel.
- **Raised** (`raised` / `raised-dark` / `raised-amoled`): text fields, menus and the floating jump buttons.
- **Hairline** (`hairline` / `hairline-dark` / `hairline-amoled`): the one-pixel rule between rows, beside the rail, above the navigation bar, and under a band that melts into the page.
- **Veil** (`track` / `track-dark`): a 6-8% ink or white wash over anything pressed or focused.

### Named Rules
**The One Marigold Rule.** Marigold is a fill for marks (underline, bar, dot, send tile, primary button, check), never a background wash, a border or decoration; as text it is always deep amber. Since it is 1.9:1 on the light page, every marigold mark is doubled by weight, a filled icon or text.

**The Readable Everywhere Rule.** Every text/background pair holds at least 4.5:1 in light, dark and AMOLED, on the page, the tile surface, every meaning tint, the marigold tint and a pressed row, and in every header style (Ink, True black, White) on the band. The chosen tab's underline holds at least 3:1 on every dark band. `SoftTest` enforces it; a new pair gets a new assertion.

**The Band Rule.** The band ends where the page begins without a line, unless the two sit under 1.12:1 apart (`bandNeedsRule`: White in light, True black on AMOLED): then a hairline in the page's colour says where it ends. Status bar icons follow the band (`bandIsDark`); navigation bar icons follow the page.

## Typography

**Display and Body Font:** Geist v1.7.2 (bundled, OFL; Latin and Cyrillic; other scripts fall back to the system sans-serif)
**Code Font:** the system monospace, for code, diffs, blame, logs, SHAs and branch names only

**Character:** a neutral, compact grotesque with tight negative tracking at title sizes. Hierarchy is weight and size, not a second family.

### Hierarchy
- **Title** (600, 28sp/32sp, -0.03em): a tab's name on its band ("Inbox", "Trending"). A heading, up to two lines.
- **Detail Title** (600, 24sp/29sp, -0.02em): a detail screen's title on the band when it is a name (a repository, Settings).
- **Hero** (600, 22sp/27sp, -0.02em): a detail screen's subject when it is a sentence: an issue's title, a person's name, a job.
- **Name** (600, 16sp/21sp, -0.005em): a repository in a list, an account's name, a Notice's or sheet's title.
- **Section** (600, 14sp/18sp): a section's title over its rows ("Needs you", a settings group, a Feed day).
- **Control** (600, 14sp/20sp): buttons, a comment's author, the choice pill; tabs use it at 600 when chosen and 400 otherwise.
- **Figure** (600, 13sp/18sp, tabular): ranks, gains, counts.
- **Body** (400, 15sp/22sp): descriptions, comments, Notice bodies. Measure capped at 580dp.
- **Lead** (400, 15sp/22sp): what a subject says of itself under its name in a header, and what is written in a field.
- **Row Title** (400, 15sp/20sp): the line of a row read at a glance; an unread Inbox thread at 600 in ink, a read one at 400 muted.
- **Secondary** (400, 13.5sp/19sp): Trending descriptions, a header's repository line, timeline event lines, error lines.
- **Label** (600, 12.5sp/17sp): state tags, a field's name above it, the left-off mark.
- **Meta** (400, 12sp/16sp, tabular): source lines, times, stats, navigation labels (held at 1.15x on the bar).
- **Tag** (600, 11.5sp/16sp): tags, labels, reactions, counts.
- **Code / Code Dense / Gutter** (monospace 400, 13sp/20sp, 12sp/18sp, 11sp/18sp tabular): a file; a change, a blame or a log; line numbers and hunk headers.

Material's scale (`SoftTypography`) is filled from Geist for leftovers and Markdown headings: display, headline and title roles at 600, body at 400, labels at 600.

### Named Rules
**The Two Weights Rule.** Geist ships Regular 400 and SemiBold 600 only. No other weight, no other family, no condensed face; anything else is faked or falls back. `DesignSystemTest` rejects every other `FontWeight` outside monospace.

**The Tabular Figures Rule.** Every number meant to be compared down a list (ranks, gains, stats, times, line numbers) is set with `tnum`, so columns line up without a grid.

**The Named Style Rule.** Every text takes one of the styles above from `Soft.type`, changing at most its weight (within the Two Weights Rule) or its colour. A size set by hand in a screen is drift; a new need gets a new named style here and in `SoftType`. `DesignSystemTest` holds every screen to it; the reaction emoji in a comment's menu and its burst are the one exception, as pictures rather than text.

## Layout

A single centred reading column, capped at 720dp (`MaxReadingWidth`), with running text further capped at 580dp (`MaxMeasure`); the band runs edge to edge behind it. There is no tablet-specific layout, by owner decision, and none is planned: tablets get the same column, centred.

The band's text edge is 18dp on top-level screens; a detail screen starts 4dp in so the back arrow's 48dp target lines up, its title and header content 16dp further. Tabs carry a 10dp inset of their own, so the header's tab row is laid out a tab-inset wider on each side and the first label sits on the title's edge; the pinned section tabs pad 8dp so they land on the same 18dp line. List rows pad 16dp at the sides and 10dp top and bottom (9dp in the Inbox), lead with the 32dp tile and a 12dp gap, and so put their text at 60dp, where the hairline starts. The conversation reads from a 20dp gutter. Spacing otherwise moves in small steps (2, 4, 6, 8, 12, 14dp).

Edge to edge: the band sits under the status bar, and once it has scrolled away `SoftStatusBarScrim` keeps the band's colour there. Section tabs pin under the header on the band. Navigation is responsive at 600dp: below it a 56dp bar on the page along the bottom, its height plus the system inset reserved under every list (`listBottomPadding()`); from 600dp an 80dp rail at the start, divided from content by a hairline. Every screen takes the horizontal safe-drawing inset (`sideSafeArea()`), and screens with fields take `imePadding`.

Rows grow, never cut: at large font the Inbox's time moves from the right edge into the end of the source line, the section tabs scroll sideways only when they overflow, and Trending's meta line is sized by its glyphs.

## Elevation & Depth

Flat. Depth on the page is the band against the body, the tile at the head of a row, and the hairlines between rows. A pressed row fills with the tile surface; anything else pressed takes the veil. Menus, sheets and text fields sit on `raised` with Material's own elevation for menus and sheets.

### Shadow Vocabulary
- **Jump Lift** (8dp Compose shadow, ambient and spot `ink` at 18%, on the 8dp tile shape): the one drawn shadow, lifting the conversation's jump buttons off the comments scrolling beneath.

### Named Rules
**The Hairline Rule.** Rows are parted by one device pixel in `hairline`, starting where the row's text starts (60dp past a tile, 20dp in a conversation), never a card, an outline or stacked panels. The same pixel separates the navigation from the page and ends a band that melts into it. Small self-contained shapes (tiles, tags, tonal buttons, fields, the Feed's 8dp repository panel) hold their own content and never other rows.

## Shapes

Small, quiet corners. Tags and counts are 5dp; controls (buttons, fields, the check, the choice pill, the left-off mark) are 6dp; tiles, icon buttons, the send tile, menus, the snackbar and the selection bar are 8dp; Material's leftover shapes step 4, 6, 8, 10 and 12dp, and nothing exceeds 12dp (`DesignSystemTest`). Rows and the band are rectangles. Circles are reserved for dots (unread, language, the forge badge's cut-out) and for people's faces: a Feed actor and a profile are round, while repository owners in lists and comment authors (28dp) are rounded squares. There are no pills and no pill switches.

## Components

Material 3 composables (Icon, menus, bottom sheets, snackbars, pull-to-refresh, progress) remain as the base, restyled through `ForgelineTheme`; the distinctive pieces are the Soft components in `core/ui/.../soft/` and the shared rows in `app/.../ui/`.

### Band (`SoftHeader`, signature)
- **Character:** the one place colour arrives at scale; the screen's name and its one axis.
- **Top-level:** Title left (up to two lines), actions right (search; the Inbox's account as a tile), then the axis as tabs ending on their own underline.
- **Detail:** back and actions on a 48dp row, then the Detail Title or the subject's own header (repository line, Hero, state tag, labels) 16dp in, 14dp below.
- **Palette:** everything inside is drawn with `onBand()`, so it reads on ink, black or white alike. `DesignSystemTest` holds header content to the band's palette.

### Tabs (`SoftSwitch`, `SoftChipTabs`)
- **Style:** text tabs at their natural width, 48dp tall, 10dp inset; the chosen one in Control 600 ink, the others 400 muted.
- **Underline:** a 3dp marigold line under the chosen label, sliding and resizing to it on the spring (damping 0.8); instant under Remove animations.
- **Placement:** `SoftSwitch` is the one axis inside a header (period, filter, scope, theme, header style); `SoftChipTabs` are a page's sections on the band, pinned while the page scrolls. Both scroll sideways only when they overflow, bringing the chosen tab into view.

**The Underline Rule.** Selection in the open is a bold label over a marigold underline (tabs) or a marigold bar with a filled icon and bold label (navigation). Never a tinted chip, a filled pill or colour alone.

**The One Axis Rule.** A header carries one axis in the open, as the tabs; a second axis (forge, account) is a `SoftChoicePill`, never a second strip of tabs.

### Choice Pill (`SoftChoicePill`)
The current value in Control ink and a muted chevron, transparent so it reads on the band and the page, 48dp tall, 6dp corners, at most 220dp; or, beside a band's title, a 32dp tile holding only the chosen account's forge logo. Opens a menu on `raised` with 8dp corners; the chosen option carries a deep amber check. Screen readers hear "Forge, Codeberg".

### Rows and Tiles (`SoftTile`, `ui/Rows.kt`)
- **Tile:** 32dp, 8dp corners, `surface` by default, an 18dp ink glyph or a name's initial. An unread dot (marigold on a page-coloured ring) sits on its top corner; when more than one forge is signed in, the forge's logo sits on its bottom corner on a page-coloured disc, and the tile is named by that forge for screen readers.
- **Inbox thread:** the tile in marigold tint with a deep amber glyph when it needs you, its state's tint otherwise, marigold with a check when picked; a Meta source line (the reason in deep amber 600, then `owner/name #N`) with the time on the right, or inline at the end above 1.3x font; the title in Row Title, 600 ink unread and 400 muted read; an overflow menu on the right.
- **Trending:** the owner's avatar as a tile with the forge badge; `owner · #rank` in Meta muted, the "+gained" Figure in deep amber on the marigold tint at the trailing edge; the name in Name; a one-line Secondary description; a meta line with the language dot, stats, builders and the star toggle.
- **Feed:** the actor's face as a round tile with the forge badge, then the line and the object (state tag, title, or an 8dp `surface` repository panel held to 580dp).
- **Press:** `softPressable` fills the whole row with `surface` while pressed or focused; no squish, no ripple.
- **Swipe (Inbox):** the row slides off a rectangle in a meaning tint with an ink icon. Every swipe or menu action shows at once but waits for the Undo delay (5 seconds by default, a setting) before reaching the forge, with Undo on the snackbar.

### Buttons
- **Filled (`SoftButton`):** marigold, `on-thumb` Control text, 6dp corners, 40dp tall in a 48dp target, 16dp padding. The one primary action. Disabled, it goes quiet on `surface`.
- **Tonal (`SoftTonalButton`):** `surface`, ink Control text, 14dp padding. Cancel and secondary actions.
- **Final (`SoftDangerButton`):** ink with page-coloured text. For what can't be taken back; always beside a tonal Cancel, never beside marigold.
- **Icon (`SoftIconButton`, `SoftIconToggleButton`):** a 40dp press shape with 8dp corners in a 48dp target, answering with the veil; disabled at 38%.
- **Send (`SoftSendButton`):** a send arrow on a 40dp marigold tile in a 48dp target, at the end of the composer; quiet on `surface` while nothing is written, a small spinner while sending.

**The Veil Rule.** Nothing pressable answers with Material's ripple. Rows fill with `surface`; everything else takes the veil (`SoftIndication`, the theme's default indication); on the ink selection bar the veil is the page colour at 14%. `DesignSystemTest` holds every screen to it.

### Check (`SoftCheck`, `SoftCheckRow`, `SoftChoiceRow`)
A 22dp square with 6dp corners: marigold with an ink check when on, a 1.5dp muted frame when off. It shows the state of a row that toggles as a whole (Pure black, Feed kinds, repository settings) and replaces every pill switch. A choice among a few in a sheet is a `SoftChoiceRow`: a ring that fills deep amber for the one picked, Control title and Meta summary, 48dp.

### Inputs (`SoftTextField`)
`raised` fill in a one-pixel hairline frame, 6dp corners, at least 48dp, 14dp padding; Lead text with a deep amber cursor; the placeholder muted; an optional Label above that stays once something is written; an error line in deep amber Secondary below. Multi-line fields grow with what is written.

### Tags (`SoftTag`, `SoftPill`)
- **Tag:** Tag type on 5dp corners, 7dp/2dp padding, `surface` by default (marigold tint for "Needs you"); one line, ellipsised, never broken into a stub.
- **State tag:** a 14dp ink glyph and Label on a meaning tint, 5dp corners: the Feed's states, a release tag, a branch (monospace).
- **Forge labels:** the forge's own colour, with black or white text, whichever contrasts more (`readableOn`).

### Conversation (issue and pull request)
Comments are parted by hairlines starting at 20dp: a 28dp rounded-square author avatar, the author in Control and the time in Meta, then the Markdown body held to 580dp, reactions as tags. No bubble, no card. Timeline events are 28dp tiles tinted by meaning with a muted Secondary line. Jump buttons, event badges and reaction targets are 8dp tiles. The composer closes the conversation: a `SoftTextField` and the send tile.

### Navigation (`SoftNavigation`)
- **Phones:** a 56dp bar on the page with a hairline above, four equal tabs (Inbox, Feed, Trending, You), each icon over its Meta label. The selected tab shows a filled icon, a 600 label and a 3dp marigold bar across 44% of its width at its top edge, crossfading in over 180ms. Labels follow the font scale to 1.15x and then hold.
- **Wide windows (600dp and up):** an 80dp rail on the page with a hairline beside it; the selected item has a 3dp × 32dp marigold bar on its start edge.
- **Order and start:** Inbox, Feed, Trending, You, always; the app opens on Inbox when signed in and on Trending when signed in nowhere.

### Notices, Loading, Sheets, Snackbar
- **Notice (`SoftNotice`):** a Name heading, a muted Body line, then a filled and optionally a tonal button, in the column at 18dp/28dp. No illustration, no box.
- **Loading (`SoftLoadingRows`):** five rows of a 32dp tile and three 5dp-cornered bars in `surface`, parted by hairlines. No shimmer.
- **Sheets (`SoftConfirmSheet`, `SoftSheet`, `SoftChoiceSheet`):** rise from the foot on the page colour: a Name heading, a muted Body line, what to pick or write, then tonal Cancel beside the way on, right-aligned and wrapping. Never a centred dialog with two equal text buttons.
- **Snackbar (`SoftSnackbarHost`):** ink with page-coloured text, 8dp corners; its action is marigold on the light theme's ink bar and deep amber on the pale bar of the dark themes.
- **Left-off mark:** a warm 6dp-cornered mark with a 6dp marigold dot and "Where you left off" in Label, between rows; nothing to press.

**The One Spring Rule.** Things that move use one soft spring (damping 0.8-0.85, medium-low stiffness): the tab underline, scroll-to-tab. Colour changes crossfade (180ms for the navigation bar, 300ms for a title going read); late content fades in. All of it is skipped when the system animator scale is 0 (Remove animations).

## Do's and Don'ts

### Do:
- **Do** take every colour and type style from `Soft.colors` and `Soft.type`; never hard-code a hex in a screen.
- **Do** head every screen with `SoftHeader` and draw everything on the band from `onBand()`.
- **Do** show selection with a bold label and a 3dp marigold underline (tabs) or bar (navigation), and double every marigold mark with weight, a filled icon or text.
- **Do** open list rows with a 32dp 8dp-cornered tile, and part rows with a one-pixel hairline from the text edge.
- **Do** keep things as 8dp tiles and people's faces round, except comment authors' 28dp rounded squares.
- **Do** set everything in Geist 400 or 600, and code in the system monospace.
- **Do** use `tnum` figures for ranks, gains, counts, times and line numbers.
- **Do** use the square `SoftCheck` for on and off, and the send tile for sending.
- **Do** keep 48dp targets, sp type that grows at 1.3x and 2.0x without cutting, system Back and edge-to-edge insets.
- **Do** add a `SoftTest` assertion for every new text/background pair, in every theme and header style.
- **Do** honour Remove animations and the Undo delay for Inbox actions.

### Don't:
- **Don't** build brutalist or boxy layouts, or borrow literal metaphors from other domains (train boards, split-flap).
- **Don't** use condensed signage type, a third Geist weight or another family.
- **Don't** use Material 3 rounded pills, tinted-background selection or Material switches.
- **Don't** use a big text pill for a small action; a small action is an icon on an 8dp tile.
- **Don't** use corners above 12dp, or pills anywhere.
- **Don't** let the marigold underline or bar carry meaning alone; on the light page it is 1.9:1.
- **Don't** wash marigold over backgrounds or borders; deep amber is its text.
- **Don't** put rows in cards or comments in bubbles; rows are parted by hairlines.
- **Don't** give anything pressable a ripple, or press a row with a squish.
- **Don't** stack a second strip of tabs under a header's axis.
- **Don't** build a two-pane or tablet-specific layout.
