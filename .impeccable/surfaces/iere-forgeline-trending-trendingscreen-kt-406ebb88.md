---
version: 1
slug: "iere-forgeline-trending-trendingscreen-kt-406ebb88"
primary_target: "app/src/main/kotlin/fr/arthurbrugiere/forgeline/trending/TrendingScreen.kt"
related_targets: []
---

# Surface: Trending

Scope: the Trending tab (`TrendingScreen`). First surface of the replacement visual world; later tabs will be brought over to it.
Visitor mode: Read. A slow daily browse of ~25 ranked repositories, then open or star one or two. Light and dark are equally first-class, following the system setting.
Constraints: GitHub's ranking and order shown unchanged. Works signed out. No perceptible cost to cold start or scrolling. Not corporate, not heavy.

## Owner's steer (2026-09-28)

Wanted: soft and friendly, modern, vivid enough to feel alive. The app is mostly text, so it has to be a pleasure to read.
Rejected: the "Departure Board" direction (split-flap tiles, amber on near-black, condensed signage type), called a train app, too boxy, aggressive and brutalist. No literal metaphors from other domains, square tiles, harsh contrast or condensed signage faces.
Approved on a mock: Gabarito (display) with Lexend (text); the tinted header field, pill switch, unboxed list and ember accent.

## Direction contract

THESIS: Friendly Type: a warm, rounded display face and very readable text carry Trending, on soft surfaces with one vivid ember accent. It refuses both the stacked-card list and any boxed, gridded chrome.

OWN-WORLD: Light: a soft white ground, deep plum-navy ink, ember for momentum. Dark: a deep violet-black ground, lavender-white ink, bright ember. A soft period tint owns the header field (ember today, lilac this week, mint this month), with 28dp rounded bottom corners. Pill controls, 20dp soft pressed surfaces, no divider lines or card outlines. Gabarito 500/700 for display and figures (tabular); Lexend 400/500 for all text.

STORY: The visitor sees what's rising, reads each description comfortably, stars what's worth keeping, and resumes where they stopped.

FIRST VIEWPORT: The tinted field under the status bar with "Trending" in Gabarito 34sp and search on the right, then a full-width pill switch with an ember thumb. Below: "Updated …" and a "Resume at #n" link when there's a mark. Then about four rows: a small ember rank, the owner muted above the name (Gabarito 21sp), "+2,109" ember on the right, the description (Lexend 15sp/24), and a meta line (language dot, language, stars, forks, builders, star toggle).

FORM: Friendly Type, candidate 6 of 7 on my re-rolled list; seed key a4fc6e9b (re-roll 1). Raises: color at scale in the header field; two weights of one display face; tabular figures; nothing boxed; one soft springy motion language; order that reads from position and rank alone. Signature interaction: the ember thumb springs to the new period while the field tint crossfades and the rows rise in softly, staggered (removed under "Remove animations").

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Unresolved

- Resolved 2026-09-30: the world now covers every tab and the navigation bar (see DESIGN.md).
- Dynamic color no longer affects Trending (kept off by decision).
- With several forges, the forge choice sits in the "Updated…" line as a pill, not in the field (DESIGN.md, the One Axis Rule).
