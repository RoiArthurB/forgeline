---
version: 1
slug: "urbrugiere-forgeline-core-ui-soft-soft-kt-feb9fa6b"
primary_target: "core/ui/src/main/kotlin/fr/arthurbrugiere/forgeline/core/ui/soft/Soft.kt"
related_targets: ["core/ui/src/main/kotlin/fr/arthurbrugiere/forgeline/core/ui/soft/SoftComponents.kt","app/src/main/kotlin/fr/arthurbrugiere/forgeline/inbox/InboxScreen.kt","app/src/main/kotlin/fr/arthurbrugiere/forgeline/trending/TrendingScreen.kt"]
---

# Surface: the whole app (Ink Compact)

Scope: every screen, the navigation bar and rail, and the shared Soft layer in core/ui. Replaces the Friendly Type world.
Visitor mode: Operate (Inbox triage, repository and issue screens) with Read moments (Trending descriptions, READMEs, comments).
Constraints: speed first (no new runtime cost: static fonts, no blur, no extra layers per row); every text pair ≥4.5:1 in light, dark and AMOLED; 48dp targets; sp type that grows at 1.3x and 2.0x; nothing cut.

## Owner's steer (2026-10-15)

Felt the old UI heavy and chunky, too airy, too rounded. Liked: Plate's big plain title, underline tabs with a bold selected label, the bottom bar with a marigold bar over the selected tab, the yellower ember (marigold); Digest's fast list and the big icon tiles (easy to identify in the Inbox). Disliked: Material 3 rounded pills and tinted-background selection; the big "Comment" pill (wants a send arrow with some accent). Picked: forge as a badge on the tile; compact density; the ink header band.
Theme answers: light = ink band over a light body; dark = lifted charcoal band (#24242A) on #111113; AMOLED = band #17171A on true black. A Header setting offers Ink (default), True black and White, the last one kept for fun.

## Direction contract

THESIS: Ink Compact: an ink band carries each screen's name and its one axis, and below it a fast, hairline-ruled list where every item opens with a big icon tile. It refuses the tinted soft field, the pill switch, and every filled-pill selection.

OWN-WORLD: Light: #F5F4F6 body, #16181A ink band and ink text, #6B6A70 muted, #DCDADF hairlines, #E8E7EB tiles; marigold #F5A524 fills (selected underline, unread dots, the selected tab's bar, the send tile), deep amber #8F5600 as readable accent text. Dark: #111113 body, #24242A band, #EDEDF0 text, marigold #F5A524, #FFC062 accent text. Geist 400/500/600 for everything, system monospace for code only. Corners: 9dp tiles, 5-6dp tags, fields and buttons; no pills.

STORY: Open on the Inbox, see what needs you by its marigold tiles and dots, read the forge from each tile's badge, clear threads quickly; browse Trending with seven repositories a screen; read an issue and answer with the send arrow.

FIRST VIEWPORT: Inbox: an ink band under the status bar with "Inbox" in Geist 600 28sp, the account tile and search on the right, then the filter as text tabs (Unread, Participating, All, Yours) with the selected one bold over a 3dp marigold underline. On the light body: "Needs you" with its count, 32dp marigold-tinted tiles with a kind icon, an unread dot and a forge badge, a source line with the time on the right, a 15sp title, hairlines between rows; then "Everything else" by repository. A 54dp bottom bar with a hairline top and a 3dp marigold bar over Inbox.

FORM: Ink Compact, the user's fusion of my pick (Digest, candidate 1 of 7) and the Plate Sheet challenger (design-annual-s-plate-section), then the badge, compact and ink-header variations; seed key 28e31d81. Signature interaction: the marigold underline slides to the chosen tab on the soft spring while the list cross-fades (instant under Remove animations).

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Unresolved

- Header "White" style in light theme: a white band over the light body, separated by a hairline.
- State marks (open, merged, closed) move from field tints to small outlined tags with a state dot.
