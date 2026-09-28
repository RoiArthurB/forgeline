# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

**Primary: the discovery reader.** A developer who opens Forgeline for a daily browse: Trending, then the Feed of people and repositories they follow, finding projects and people worth knowing about. On a forge, discovery also means taking part, so reading an issue, reacting, starring, following and writing issues all count as discovering.

**Secondary: the maintainer doing triage.** Code moves slowly enough that discovery alone wouldn't bring people back daily. The Inbox (notifications grouped by repository, swipe to mark read or done, background checks) keeps someone's own projects moving between desk sessions.

**Not a focus:** the general "developer on the go" who wants to do GitHub from their phone. If the app serves them, that's a side effect, not a design target.

## Product Purpose

Forgeline is a fast, discovery-first Android client for code forges. It shows a forge as a timeline: an **Inbox** for what needs you, a **Feed** of what the people you follow are doing, and **Trending** for the daily discovery ritual. Everything renders natively, so you're never sent to a mobile web page.

It began because the author wanted, and needed, a forge client that is light and very fast. Success means opening the app feels instant, the daily discovery browse is worth coming back to, and triage is quick enough to do in a spare minute.

## Positioning

In order of importance:

1. **Fast, above everything else.** Cache-first: every screen renders from the local database instantly, then refreshes. The app opens in about a second. Speed is the reason the project exists, and it wins any trade-off.
2. **Discovery-first.** Built around the timeline and the daily Trending browse, not a port of desktop GitHub's layout.
3. **Multi-forge.** GitHub first; GitLab and Gitea/Forgejo, including self-hosted instances, are planned.
4. **Free and private.** GPL-3.0, no backend, no analytics, no trackers. The app talks directly to the forge.

## Operating Context

- A quick daily check-in: open, browse Trending and the Feed, dip into repositories, READMEs, issues and profiles, then close.
- Triage between desk sessions: clear the Inbox with swipes. Background checks (hourly by default, or off) post phone notifications on separate channels, so people can mute CI without missing mentions.
- github.com links can open in the app, but only after the user turns it on in Android's "Open by default" settings, because the app can't verify a domain it doesn't own.
- Sign-in uses the one-tap GitHub device flow or a classic personal access token. Trending works without signing in.

## Capabilities and Constraints

- **v0.1 (proof of concept):** Inbox (Unread, Participating and All filters; swipe actions), Feed (strict chronological order, identical events merged, activity types chosen in Settings), Trending (today, this week, this month, ranked as GitHub ranks them), repositories (native README, a code browser with syntax highlighting, issues, pull requests, releases, Actions runs), issue and PR timelines with reviews, merges and reactions, user and organization profiles, starring and following, and search (repositories, issues and PRs, people, with GitHub's query syntax).
- **Deliberately excluded:** code search.
- **Stack:** Kotlin, Jetpack Compose, a Material 3 base, and a user setting for dynamic color. Modules are split into `core/*` and `forge/*`, the latter so more forges can be added. Screenshot tests use Roborazzi and Robolectric, and the app ships a baseline profile.
- **Performance is a product requirement:** new UI work must not slow cold start, first render from cache, or scrolling. The baseline profile and benchmarks cover the daily journeys.
- **Why Material You:** it was the default, picked only to check whether the app concept worked. The owner has no attachment to Material You or Material 3 components. Any UI library or custom design system can replace them, as long as it is at least as fast and allows a better-looking UI. **Speed is the only hard constraint** on the UI stack. The `redesign` branch is where the visual direction and the component layer get decided.

## Brand Commitments

The name "Forgeline" is the only commitment. There's no committed logo, voice or visual identity to preserve.

## Evidence on Hand

- `README.md`: the feature list and principles.
- `app/src/test/screenshots/`: Roborazzi screenshot goldens of the current screens.
- There are no users, testimonials, download numbers or benchmark figures beyond "opens in about a second". Future work must not invent them.

## Product Principles

1. **Speed is the feature.** If a design choice costs perceived or measured speed, it needs a very strong reason.
2. **Discovery comes first, triage stays fast.** The daily browse leads. The Inbox is always a quick way to get work done.
3. **Discovery includes taking part.** Reading, reacting, starring, following and writing issues are part of discovery, not a separate mode.
4. **Nothing is tied to one forge.** Concepts and UI shouldn't bake in anything GitHub-only that GitLab or Forgejo couldn't fill.
5. **The app is light and honest.** No tracking and no backend. The app shows the forge's data as the forge ranks and orders it.
