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
  raised: "#FFFFFF"
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
  raised-dark: "#282533"
  field-today-dark: "#2B1E1F"
  field-week-dark: "#221F33"
  field-month-dark: "#1A2724"
  ground-amoled: "#000000"
  surface-amoled: "#16141D"
  raised-amoled: "#1C1925"
typography:
  title:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "34sp"
    fontWeight: 700
    lineHeight: "38sp"
    letterSpacing: "-0.01em"
  hero:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "26sp"
    fontWeight: 700
    lineHeight: "31sp"
    letterSpacing: "-0.01em"
  name:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "21sp"
    fontWeight: 700
    lineHeight: "25sp"
  section:
    fontFamily: "Gabarito, sans-serif"
    fontSize: "17sp"
    fontWeight: 500
    lineHeight: "22sp"
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
  tag:
    fontFamily: "Lexend, sans-serif"
    fontSize: "12sp"
    fontWeight: 500
    lineHeight: "16sp"
  code:
    fontFamily: "monospace"
    fontSize: "13sp"
    fontWeight: 400
    lineHeight: "20sp"
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
  header-field-detail:
    backgroundColor: "{colors.field-week}"
    textColor: "{colors.ink}"
    typography: "{typography.hero}"
    rounded: "{rounded.field}"
    padding: "4dp 8dp 20dp 4dp"
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
  chip-tab:
    backgroundColor: "{colors.track}"
    textColor: "{colors.ink-muted}"
    typography: "{typography.control}"
    rounded: "{rounded.thumb}"
    padding: "0 18dp"
    height: "48dp"
  chip-tab-selected:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    typography: "{typography.control}"
    rounded: "{rounded.thumb}"
    padding: "0 18dp"
    height: "48dp"
  row:
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
    padding: "14dp 4dp 4dp 12dp"
  row-pressed:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.row}"
  row-badge:
    backgroundColor: "{colors.field-today}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    size: "36dp"
  event-badge:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    size: "28dp"
  button-filled:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    typography: "{typography.control}"
    rounded: "{rounded.pill}"
    padding: "0 24dp"
    height: "48dp"
  button-tonal:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.pill}"
    padding: "0 20dp"
    height: "48dp"
  tag:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.tag}"
    rounded: "{rounded.pill}"
    padding: "4dp 10dp"
  search-field:
    backgroundColor: "{colors.ground}"
    textColor: "{colors.ink}"
    typography: "{typography.body}"
    rounded: "{rounded.pill}"
    padding: "0 4dp 0 16dp"
    height: "52dp"
  nav-bar:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.pill}"
    padding: "6dp"
    height: "64dp"
  nav-item-selected:
    backgroundColor: "{colors.thumb}"
    textColor: "{colors.on-thumb}"
    typography: "{typography.control}"
    rounded: "{rounded.pill}"
    padding: "0 16dp"
    height: "52dp"
  nav-rail:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.thumb}"
    padding: "12dp 6dp"
    width: "88dp"
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
  code-block:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.code}"
  snackbar:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ground}"
    rounded: "{rounded.snackbar}"
---

# Design System: Forgeline

## Overview

**Creative North Star: "Friendly Type"**

Forgeline is mostly text, so the world is built to be pleasant to read. A warm, rounded display face (Gabarito) carries names, titles and figures, a very legible text face (Lexend) carries every line of reading, and both sit on soft surfaces with one vivid ember accent. The owner's word for the result is comfy: soft, friendly, modern, and vivid enough to feel alive, never heavy or corporate.

Hierarchy comes from type, space and tone, never from lines or boxes. Colour arrives at scale once per surface, as a soft tint that owns the header field and says something about the screen: warm, cool or fresh, keyed to the section (Trending's period, the Inbox filter) or to the thing being read (an issue's state). Ember then marks only momentum and selection. Density is comfortable: one reading column, generous row rhythm, descriptions held to a readable measure. Motion is soft and springy, a little lively and never bouncy, and it disappears entirely under the system Remove animations setting.

Native Android behaviour stays native: system Back, edge-to-edge insets, 48dp touch targets, and sp type that follows the font scale (rows grow and wrap at 1.3x and 2.0x; nothing is cut). Confirmed rejections: brutalist or boxy layouts, split-flap or departure-board and other literal metaphors borrowed from other domains, harsh aggressive contrast, condensed signage display faces, and a corporate feel.

Scope: the world covers the whole app. It lives in `core/ui/.../soft/Soft.kt` (`SoftColors`, `SoftType`, `SoftTheme`, `amoled()`) and `SoftComponents.kt` (`SoftTokens` and the shared pieces), and every screen, the floating navigation bar and the rail are built from it. `ForgelineTheme` fills Material's colour roles, type scale and shapes from Soft, so the Material pieces still in use (dialogs, switches, text fields, menus, snackbars, pull-to-refresh, progress indicators) take the palette. The Material You (dynamic colour) setting is gone; the only appearance choices are System, Light or Dark, plus AMOLED black.

**Key Characteristics:**
- Gabarito 500/700 for display, names and figures; Lexend 400/500 for all reading text.
- One soft tinted header field per screen, 28dp rounded bottom corners, tint warm, cool or fresh by meaning.
- One ember accent for momentum (ranks, gains, starred, resume, links) and one ember fill for selection (the switch thumb, the selected tab, the selected nav item).
- Nothing boxed: no dividers, no card outlines, no stacked cards; comments are unboxed turns.
- Pill controls, round soft badges, and a 20dp soft surface for pressed or focused rows.
- A floating pill navigation bar on phones and a soft rail on wide windows, both on `raised`.
- Tabular figures wherever numbers line up.
- Every text/background pair at least 4.5:1, enforced by `SoftTest`.

## Colors

A cool, near-white (or violet-black) ground with plum-navy ink, lifted by soft pastel tints and one hot ember.

### Primary
- **Ember Thumb** (`thumb`): the saturated ember of selection and commitment. It fills the switch thumb, the selected chip tab, the selected navigation item, filled buttons, the checked Material switch track, and the dot on the Stopped-here marker. Identical in all themes; text and icons on it are `on-thumb` (plum-navy in light, near-black in dark).
- **Readable Ember** (`accent` / `accent-dark`): ember tuned per theme to read as text. Light uses a deep burnt ember so it holds 4.5:1 on the ground, the pressed surface and the raised surface; dark uses a bright coral ember. It marks momentum and links only: rank numbers, "+gained" figures, the filled star, the "Resume at #n" link, README and comment links (Lexend 500, underlined), the sign-in token link, text cursors, spinners and progress bars, and the cross on a failed run.

### Secondary
- **Field Tints** (`field-today` warm, `field-week` cool, `field-month` fresh, with `-dark` variants): peach, lilac and mint in light; ember-, violet- and green-tinged near-blacks in dark. The token names come from Trending's periods; across the app they are used by meaning:
  - **Tabs:** Inbox warm, and its filter switch retints it (Unread warm, Participating cool, All fresh); Feed cool; Trending by period (today warm, week cool, month fresh); You fresh.
  - **Detail screens:** repository warm; file cool; issue or pull request by state (fresh open, cool merged, warm closed); profile cool; search cool; settings, credits fresh; sign-in warm.
  - **Small marks:** the same three tints back round badges by kind (below), the Stopped-here marker, the Inbox's "Needs you" pills and count (warm: for you), and the swipe backgrounds under an Inbox row (fresh to mark read, warm for done).

### Neutral
- **Soft White / Violet Black** (`ground` / `ground-dark`, `ground-amoled`): the page. AMOLED swaps the ground to true black, the surface to `surface-amoled` and the raised colour to `raised-amoled`. Ground also fills pills that sit on a field: the search field, an issue's state tag, the Following button, profile avatar placeholders.
- **Plum-Navy Ink / Lavender White** (`ink` / `ink-dark`): all primary text, titles, names and descriptions, icons in headers and badges; also the snackbar background (with ground as its text). Handed to Material as the content colour, so its icons and ripples take the palette.
- **Muted Ink** (`ink-muted` / `ink-muted-dark`): owners and `owner/` prefixes, relative times, meta stats, timeline event lines, unselected switch labels and navigation icons, placeholders, the unstarred star.
- **Soft Surface** (`surface` / `surface-dark`): the pressed or focused row, loading shapes, avatar placeholders, unselected chip tabs, tonal buttons, tags and reactions, neutral badges (files, drafts, plain events), and code blocks and inline code in Markdown.
- **Raised** (`raised` / `raised-dark` / `raised-amoled`): floating things lifted off the ground by a soft shadow: the navigation bar and rail, menus and dialogs (Material's container roles).
- **Track** (`track` / `track-dark`): a 6-7% ink or white veil laid over the field for the switch's track, so it follows whichever tint sits beneath.

### Named Rules
**The Field Tint Rule.** Colour at scale lives in the header field tint, one tint per surface, keyed to the section. Ember elsewhere is a mark (rank, gain, starred, resume, thumb), never a background wash, border or scattered decoration.

**The Readable Everywhere Rule.** Every text/background pair the world draws holds at least 4.5:1 in light, dark and AMOLED, on the ground, on every field tint, on the track over each tint, and on the pressed surface. `SoftTest` enforces it; a new pair gets a new assertion.

**The Tint Means Something Rule.** Warm, cool and fresh are chosen by meaning, never for variety. On marks the code states it outright: on issues and pull requests, in their headers, rows and Feed state pills alike, fresh is open, cool merged, warm closed; in the Feed warm also marks releases, fresh an approving review, warm a review asking for changes. Headers follow the table above; a new screen takes the tint of its nearest kin in it.

## Typography

**Display Font:** Gabarito (bundled; fallback sans-serif)
**Body Font:** Lexend (bundled; fallback sans-serif)
**Code Font:** the system monospace, for code, commit SHAs, branch names and the sign-in device code only

**Character:** Gabarito is warm, round and a little playful at weight; Lexend is open, even and made for comfortable reading. Display is friendly, text is effortless.

### Hierarchy
- **Title** (Gabarito 700, 34sp/38sp, -0.01em): the title of a top-level screen in its header field ("Trending", "Inbox"). Marked as a heading.
- **Hero** (Gabarito 700, 26sp/31sp): the subject of a detail screen inside its header field: an issue title, a profile's name.
- **Name** (Gabarito 700, 21sp/25sp): repository names in Trending rows, a detail header's small title (file name), a signed-in account's name, Notice titles.
- **Section** (Gabarito 500, 17sp/22sp): section titles inside a list (Settings groups, Feed days), the Inbox's "Needs you" and "Everything else", the notification prompt title.
- **Figure** (Gabarito 700, 16sp/20sp, tabular): ranks and "+gained" counts, in ember.
- **Control** (Gabarito 500, 15sp/20sp): switch and tab labels, button text, the selected navigation label, comment author names; at body size (15sp/24sp) for `owner/name` in shared repository rows. Switch labels auto-shrink (down to 9sp) only when a single word cannot fit at very large font scales.
- **Body** (Lexend 400, 15sp/24sp): descriptions, issue titles in rows, comment and Notice bodies, setting titles. Measure capped at 580dp (about 65-75 characters).
- **Secondary** (Lexend 400, 14sp/20sp): owner above a name, `@login`, a detail header's repository line, timeline event lines.
- **Meta** (Lexend 400, 13sp/18sp, tabular): language, stars, forks, issue numbers and relative times.
- **Label** (Lexend 500, 13sp/18sp): the resume link, the Stopped-here marker, an issue's state tag, forge labels; at 12sp/16sp (**Tag**) for tags and reactions; at meta size for rail labels.
- **Code** (monospace 400, 13sp/20sp): the file viewer and Markdown code, with a muted line-number gutter.

Material's scale (`SoftTypography`) is filled from the same faces for leftover pieces and Markdown headings: display, headline and title roles in Gabarito (titleMedium and titleSmall at 500, the rest 700), body and label roles in Lexend.

### Named Rules
**The Two Weights Rule.** Gabarito ships in exactly two weights, Medium 500 for controls and Bold 700 for everything else it sets; Lexend in Regular 400 and Medium 500. No other weights, no other families, no condensed faces.

**The Tabular Figures Rule.** Every number meant to be compared down a list (ranks, gains, stats) is set with `tnum`, so columns line up without a grid.

## Layout

A single centred reading column. Content is capped at 720dp wide (`MaxReadingWidth`) on tablets and large screens, with running text further capped at 580dp (`MaxMeasure`); the header field tint still runs edge to edge behind it. The screen gutter is 20dp; top-level headers pad 20dp start and 8dp end, where icon buttons supply their own 48dp targets, and detail headers start 4dp in so the back arrow's target lines up, with their hero content indented 16dp. Rows sit 8dp in from the column edge (2dp apart vertically) so the pressed surface has room, then pad 12dp; a 36dp round badge and a 14dp gap lead the text column where a row has one.

Spacing moves in 4dp steps (4, 8, 12, 16, 20, 24, 32), with 2dp and 6dp for tight gaps inside a row. Content draws edge to edge: the field sits under the status bar, which gets a 94% ground veil (`SoftStatusBarScrim`) once the field has scrolled away. Section tabs (`SoftChipTabs`) pin under the header as a sticky ground-coloured strip.

Navigation is responsive at 600dp. Below it, a floating pill bar (at most 480dp wide, 16dp from the sides, 12dp above the system bar) floats over content, and `LocalBottomBarSpace` (bar height, both margins and the system inset) is added under every list so its last row and snackbars clear the bar. At 600dp and up, a soft rail (88dp wide, 12dp inset) stands at the start and the content takes the rest; only the system inset is reserved.

Trending keeps its own row anatomy: rank in a 30dp column, meta line stats left (14dp apart), the star toggle on the right edge, and builders' avatars (22dp, overlapping by 6dp) just before the toggle only when everything fits; crowded rows drop the builders first, then wrap the stats.

## Elevation & Depth

Mostly flat and tonal. Depth on the page comes from the field tint against the ground and the soft surface appearing under a pressed row; there are no card elevations and no outlines. Only things that float above the page get a shadow, and every shadow is soft and tinted, never grey and never hard.

### Shadow Vocabulary
- **Ember Glow** (6dp Compose shadow, ambient and spot colour set to `thumb`, on the 24dp thumb shape): a soft ember halo that lifts the switch thumb off its track.
- **Floating Lift** (8dp Compose shadow, ambient and spot colour `ink` at 18%, on the pill): lifts the phone navigation bar off the content scrolling beneath it.
- **Rail Lift** (12dp Compose shadow, ambient and spot colour `ink` at 25%, on the 24dp shape): lifts the rail on wide windows.
- Menus and dialogs keep Material's own elevation on `raised`.

### Named Rules
**The Nothing Boxed Rule.** No divider lines, no card outlines, no stacked cards. Rows are separated by space and type; a surface appears only as feedback (press, focus) or as a loading shape. The 2dp ground-coloured ring around overlapping avatars is a cut-out that separates faces, not a border, and is the only stroke in the world.

As built app-wide, the soft surface also fills small, self-contained shapes that carry meaning: pill tags and reactions, tonal buttons, text fields, round badges, and code blocks. None of them contains other content, so the page stays unboxed. Prompts (the notification ask) and the sign-in device code sit on the ground like notices, never in a card. The Feed's repository previews are the one larger soft panel: they hold only the repository's own name, description and stats, never other rows. Horizontal rules and table fills inside rendered Markdown are the author's content, drawn at a doubled track veil.

## Shapes

Round and soft throughout. The header field has 28dp rounded bottom corners and square top (it runs under the status bar); the switch track is 28dp, its thumb and segment clips 24dp. Rows, swipe backgrounds and pressed surfaces clip to 20dp. The rail and its items are 24dp. Buttons, chip tabs, tags, labels, the search field, the navigation bar and its items, the Stopped-here marker and loading bars are full pills; badges, avatars, dots and the loading dot are circles. The snackbar is 16dp. Material shapes for leftover pieces follow the same steps (8, 12, 20, 24, 28dp). No sharp corners, no square tiles.

## Components

Material 3 composables (Icon, IconButton, Snackbar, PullToRefresh, AlertDialog, DropdownMenu, Switch, text fields) remain as the base, restyled from Soft through `ForgelineTheme`; the distinctive pieces are the Soft components in `core/ui/.../soft/SoftComponents.kt` and the shared rows in `app/.../ui/`.

### Header Field (`SoftHeader`)
- **Character:** the one place colour arrives at scale, heading every screen.
- **Shape:** edge-to-edge tint, 28dp bottom corners, content held to the 720dp column.
- **Top-level screens:** a Title (up to two lines) left, actions (search) right, then optional content 12dp below: Trending's period switch, the Inbox filter switch.
- **Detail screens:** a back arrow and actions (open on the forge, copy) in a 48dp row; a screen with a plain title (Settings, Credits, Sign-in's "Connect to GitHub") sets it large in the field under that row, never inline beside the arrow; then the hero in the content slot: repository line, Hero title, state tag and labels for an issue; avatar, name and follow button for a profile; the search field and scope switch for search.
- **Behaviour:** the tint crossfades between tints over 400ms (instant when animations are off). Tint semantics are listed under Colors.

### Period Switch (`SoftSwitch`, signature)
- **Style:** a full-width pill track (`track` over the field, 4dp inset) with equal segments and one ember thumb with the Ember Glow.
- **Labels:** Control type, `ink-muted` unselected, `on-thumb` selected, centred.
- **Size:** at least 48dp tall; grows to the tallest label at large font scales so no word is cut.
- **Behaviour:** the thumb springs to the chosen segment (damping 0.8, medium-low stiffness). Exposed as a selectable group of tabs. Used for Trending's period, the Inbox filter, the search scope and the theme setting.

**The Ember Thumb Rule.** Selection is the springing ember thumb. Don't show selection with underlines, outlines, checkmarks or tinted text alone.

### Chip Tabs (`SoftChipTabs`)
- **Style:** the period switch's scrolling form: the same `track` pill (28dp corners, 4dp inset) holding options at their natural width (48dp tall, 18dp horizontal padding), scrolling sideways when they overflow; the strip sits on the ground from the 20dp gutter, pinned under the header.
- **State:** one ember thumb (with the Ember Glow) springs to the chosen option and takes its width; labels are Control type, `on-thumb` selected and `ink-muted` otherwise. Exposed as tabs.
- **Use:** a page's sections (a repository's README, Code, Issues, Pull requests, Releases, Actions; a profile's Repositories and Starred).

### Rows (list items)
- **Anatomy (shared rows):** optional 36dp round badge, 14dp gap, then the text column. A repository row is `owner/` (muted) + name in Control at body size, a two-line Body description, and a meta line; an issue row is an optional repo line (search results), a two-line Body title, a Meta line (number, time, author), then draft tag and labels, with a comment count on the right.
- **Trending anatomy:** ember rank (figure) in a 30dp column; owner (secondary, muted) above name (name, ink); ember "+gained" figure on the right; description (body) below; meta line last.
- **Rest:** transparent on the ground; no divider, no card.
- **Pressed / Focused:** `surface` fills the row, clipped to 20dp, and the row squishes to 98% on the soft spring, springing back on release (`softPressable`). No ripple.
- **Swipe (Inbox):** the row slides off a 20dp tint with an ink icon: fresh to mark read, warm for done. Every swipe or menu action shows at once but waits 5 seconds before reaching the forge, with Undo on the snackbar; leaving the Inbox sends what is waiting.
- **Entry (Trending):** after a section change, the first eight rows rise 16dp and fade in from 35% opacity, staggered 35ms, on the soft spring.

**The Soft Press Rule.** Pressed or focused rows get a 20dp soft surface in `surface`, never a ripple. Every text colour on it is tested to 4.5:1.

### Inbox (needs you first)
The Inbox is triage: what's waiting on you first, then the rest, each newest first, the repository on every row rather than as a group.
- **Sections:** "Needs you" (review requested, mentioned, team mentioned, assigned, security alert) then "Everything else", as pinned Section titles with the unread count as a tag (warm for Needs you, `surface` otherwise) that ticks up or down as it changes.
- **Row:** a 20dp gutter holding the 8dp ember unread dot (it pops in and shrinks away), then a line with a pill and `owner/name #N` in Meta muted, the title in Lexend 16/22 (500 ink unread, 400 muted read, easing between them over 300ms), and a Meta line: the reason (Everything else only) and the abbreviated time. The overflow menu stays on the right.
- **The pill says why, or what:** in Needs you it names the reason on warm ("Review requested", "Mentioned"); in Everything else it names the kind on `surface` ("Issue", "Release", "Checks"). Both lead with the kind's glyph.

### Round Badges
A circle with an 18dp ink icon (36dp on rows, 28dp with 15dp icons on the timeline, 40dp with 20dp icons for Inbox threads and You entries), tinted by kind with the field tints:
- **Issues and pull requests:** warm issue, fresh pull request, `surface` with a muted icon for drafts.
- **Code tab:** cool folder, `surface` file; **Releases:** warm.
- **Actions runs:** cool with a small ink spinner while running, fresh check on success, warm with an ember cross on failure.

### Feed timeline (kind first)
The Feed tells what happened by leading every event with its object, so an open pull request and a closed issue differ at a glance, the way they do on the forge's own web feed, but unboxed.
- **Day chapters:** events stay one chronological timeline under Section titles: Today, Yesterday, This week, Earlier (the device's local date).
- **The line:** a 40dp avatar, then one Secondary line: the actor (or "alice and 2 others") and named repositories in ink 500, the verb muted, then " · 15 min. ago" (abbreviated, never split by a wrap).
- **The object, below the line, drawn for its kind:**
  - Starred, forked, made public, created: a **repository preview**, a 20dp-corner `surface` panel (held to 580dp) with `owner/` muted and the name in Gabarito 18/22 Bold, the description in Secondary ink (3 lines), then a linguist-colored 9dp dot, the language and "12.4k stars" in Meta muted.
  - Issues, pull requests, reviews and comments: a **state pill** (15dp ink glyph + Label on a field tint: Open/Reopened fresh, Merged cool, Closed warm, Approved fresh, Changes requested warm, Comment cool, Reviewed `surface`) with "#N" muted beside it, then the title in Lexend 16/22 Medium ink (3 lines).
  - Releases: the tag as a warm pill (plus a Pre-release tag), then the release name as the title. Pushes, branches and tags: the ref as a `surface` pill in the code face. Members added: the line alone.
- **Filled progressively:** events don't carry repository details or pull request titles, so rows on screen ask for them; they are fetched once, cached a day (kept a week), and fill in as they arrive: the panel grows and the new text fades in over 220ms. Cached details scrolling into view show at once. Until a title arrives, a 14dp `surface` pill bar holds its place so the row keeps its shape; a repository preview shows its name alone.
- The whole row is one `softPressable` target opening what it's about; the avatar opens the actor.

### Conversation (issue and pull request)
- **Comments are unboxed turns:** a 28dp avatar, author in Control and the relative time in Meta on one pill-clipped line (tapping opens the profile), then the Markdown body in a 40dp gutter, held to 580dp, with reactions as tags below. No bubble, no card, no divider between turns.
- **Timeline events are small badges in the avatar column:** a 28dp round badge (merged cool, reopened fresh, closed warm, approved review fresh, everything else `surface`) followed by one muted Secondary line; commits use the code face for the short SHA; a labelled event carries the label pill inline; cross-references are pressable.

### Code and Markdown
- **Markdown** (READMEs, Markdown files, comments): ink text, headings on the Material headline/title scale, links in readable ember at Lexend 500 underlined; code blocks and inline code on `surface`, set in the code face and syntax-highlighted.
- **File viewer:** code in the code face, a right-aligned muted line-number gutter, highlighted off the main thread (plain text shows first).
- **Syntax colours:** Forgeline's own light and dark token sets (keyword ember, strings green, literals blue, metadata violet, comments muted ink), each readable at 4.5:1 on every ground; `CodeContrastTest` holds them to it.
- **Slugs:** `owner/name` in running text carries a word joiner after the slash, so a repository name never breaks across lines.

### Buttons
- **Filled (`SoftButton`):** full pill, `thumb` fill, `on-thumb` Control text, 24dp horizontal padding, 48dp minimum height. The one primary action: Retry, Sign in, Follow, Allow notifications.
- **Tonal (`SoftTonalButton`):** full pill, `surface` fill, `ink` Control text, 20dp padding, 48dp. Secondary actions: Cancel, Sign out, Read the licence, retry inside a list. On a field, the same pill in `ground` (Following).
- **Link:** Label type in `accent`, pill-clipped, 48dp tall (the resume link with a 16dp down arrow; the token link on sign-in).
- **Icon:** Material icon buttons with 48dp targets; icons in `ink` in headers, `accent` / `ink-muted` for the star toggle.

### Tags and Labels
- **Pill (`SoftPill`):** a 15dp ink glyph and Label type on a tint, 8dp/12dp horizontal padding: the Feed's state pills and the Inbox's reason and kind pills.
- **Tag (`SoftTag`):** Tag type on a pill, 10dp/4dp padding, `surface` by default: Draft, Pre-release, reactions, the Inbox unread count (on warm).
- **State tag:** an issue's state on a `ground` pill inside the field, with a 16dp ink icon and Label text.
- **Forge labels:** pills in the forge's own colour, as the forge shows it, in Label type, with whichever of black or white text contrasts more (`readableOn`); `SoftTest` holds GitHub's default label colours to 4.5:1.

### Text Fields (`SoftTextField`)
The one text field: a filled pill, 52dp tall, no outline, Body text at 16sp with an ember cursor, its label as the muted placeholder, an optional leading glyph and trailing action, and an ember Secondary error line under it. `surface` on the ground (the sign-in token); `ground` inside a field (search, with a search glyph and a clear button).

### Navigation (`SoftNavigation`)
- **Phones:** a floating pill bar on `raised`, 64dp tall with 6dp inner padding, Floating Lift shadow. The selected tab is an ember pill (52dp, `on-thumb` filled icon and Control label, which expands and fades in); the others are muted outlined icons named for screen readers. Exposed as tabs.
- **Wide windows:** a soft rail on `raised`, 24dp corners, Rail Lift shadow; each item stacks icon and a meta-size label, the selected one filled `thumb`.
- **Spacing:** `LocalBottomBarSpace` / `listBottomPadding()` reserve room under every list.

### Settings Rows
Unboxed rows with a Body title and muted Secondary summary; a toggle row switches as a whole, with a Material switch restyled from Soft (ember track and `on-thumb` thumb when on, `surface` track and muted thumb when off, no outline in either state). Groups are headed by Section titles.

### Stopped-here Marker
A small pill in `field-today` with an 8dp ember dot and Label text in ink, inset under the rank column, left where the last browse stopped.

### Notices (empty and error, `SoftNotice`)
No illustration and no box: a Name-style title, 6dp, a Body line in muted ink, 20dp gap, then one filled pill button, in the reading column with 20dp/32dp padding. A plain empty list may be just a muted Body line.

### Loading
Skeleton rows (`SoftLoadingRows`, five by default) of pill bars and an optional circle in `surface`, shaped like real rows. No shimmer. Lists loading more show an ember spinner on a `surface` track.

### Snackbar
Inverted: `ink` background, `ground` text, 16dp corners; it sits above the navigation bar space. Its action (Undo) is `thumb` on the dark bar of the light theme and the light theme's deep ember on the pale bar of the dark themes, tested to 4.5:1.

**The One Spring Rule.** Things that move use one soft spring, lively but never bouncy (damping 0.8-0.85, medium-low stiffness; medium for the press squish); colour changes crossfade over 400ms (300ms for a title going read), content arriving late fades in over 220ms, and list rows enter, leave and reorder with Compose's item animations. All of it is skipped when the system animator scale is 0 (Remove animations).

## Do's and Don'ts

### Do:
- **Do** take every colour and type style from `Soft.colors` and `Soft.type` inside `SoftTheme`; never hard-code a hex in a screen.
- **Do** give each surface one header field tint with 28dp bottom corners, and keep ember for momentum marks and the selection thumb.
- **Do** head every screen with `SoftHeader`, and pick its tint (warm, cool, fresh) by what the screen is about, following the table under Colors.
- **Do** set names, titles and figures in Gabarito (500/700 only) and all reading text in Lexend (400/500).
- **Do** use `tnum` figures for ranks, counts and stats.
- **Do** separate list items with space and type alone, and show press or focus with the 20dp `surface` fill.
- **Do** lead a row with a round soft badge tinted by kind when the kind matters, and render timeline events as badges in the avatar column.
- **Do** put code on the soft surface in the code face, and forge labels on pills in the forge's own colour.
- **Do** pad every list's bottom with `listBottomPadding()` so the floating bar never covers the last row.
- **Do** use the soft spring for movement and drop all motion when Remove animations is on.
- **Do** keep 48dp touch targets, sp type that grows with the font scale, system Back and edge-to-edge insets.
- **Do** add a `SoftTest` assertion for every new text/background pair (4.5:1 minimum).

### Don't:
- **Don't** draw divider lines, card outlines, stacked cards or square tiles; nothing is boxed.
- **Don't** put comments in bubbles or cards; a comment is an unboxed turn.
- **Don't** build brutalist or boxy layouts, or borrow literal metaphors from other domains (split-flap, departure boards).
- **Don't** use harsh, aggressive contrast or condensed signage display faces.
- **Don't** let it feel corporate.
- **Don't** scatter ember as backgrounds, borders or decoration.
- **Don't** add a third weight of Gabarito or Lexend, or another family.
- **Don't** give rows a ripple.
- **Don't** mark selection with anything other than the ember thumb.
- **Don't** bring back dynamic colour (Material You); the palette is Soft's alone.
