# Forgeline

A fast, discovery-first Android client for code forges.

Forgeline brings your forges to your phone as a timeline: an **Inbox** for what needs you, a **Feed** of what the people you follow are doing, and a **Trending** page for the daily discovery ritual. It opens in about a second and renders everything natively, so you never get bounced to a mobile web page.

> **Status:** early development (v0.4). GitHub, GitLab, Codeberg and self-hosted Forgejo servers work today, several accounts at once, private repositories included.

## What's in v0.4

- **Several forges at once:** GitHub, GitLab, Codeberg and any self-hosted Forgejo server share one Inbox, Feed, Trending page and search. Each row names its forge, and one forge can be picked alone. Codeberg's and gitlab.com's Trending are measured daily, shown to everyone without signing in, and mixed in by share of stars.
- **Inbox:** what needs you first (review requests, mentions, assignments, security alerts, and GitLab Todos), then everything else grouped by owner and repository. Pull requests/merge requests and issues say whether they're open, merged or closed. Swipe right to mark read, left to mark done, with 5 seconds to Undo. Background checks (every hour by default, or off) post phone notifications on separate channels, and the conversations waiting on you are loaded ahead so they open instantly.
- **Feed:** what the people you follow and the repositories you watch are doing, in strict chronological order, grouped by day, each event leading with the repository or pull request it's about. Identical events on the same repository merge. Releases (and, on GitHub, announcements) from the repositories you starred join in, which the forges' own event feeds leave out. You choose which kinds of activity show up in Settings.
- **Trending:** what is trending today, this week or this month on GitHub (as GitHub ranks it), Codeberg and gitlab.com, mixed into one page or one forge at a time. It works without signing in.
- **Where you left off:** Trending and the Feed mark where your last visit's reading stopped.
- **Repositories:** the README rendered natively, a code browser with syntax highlighting at any branch or tag, and issues, pull requests, merge requests, releases and CI pipelines. Issues and pull requests list open or closed, can be searched by words, and show the repository's pinned issues first. Each release has its own page: its notes, the files it ships with their size and download count, and the source at its tag.
- **CI / CD and Actions:** GitHub Actions and GitLab CI/CD pipelines with their jobs and failed steps, job logs with colors and folded sections, live steps while a job runs, re-run and cancel, and starting a workflow by hand with its inputs.
- **Conversations and profiles:** issue, pull request and merge request timelines with reviews, merges and reactions, kept on your phone so they reopen instantly, and user and organization/group profiles. You can open an issue, comment on a conversation, close or reopen it, star and follow from the app. With a role in the repository you can also triage (labels, assignees, milestone), lock, pin, transfer, duplicate and delete, as far as each forge's API allows.
- **Search:** repositories, issues and pull requests/merge requests, and people, with each forge's query syntax. There is no code search, by design.
- **github.com and gitlab.com links open in the app.** Android doesn't send them to Forgeline automatically, because the app can't verify a domain it doesn't own. To turn it on, go to *Settings → Apps → Forgeline → Open by default → Add links* and select the forge domains.

Release notes for each version are in [docs/release-notes](docs/release-notes).

Sign in with the one-tap device flow (GitHub), browser OAuth with PKCE (GitLab, Codeberg), or personal access tokens.

## Principles

- **Fast.** Cache-first: every screen renders from the local database instantly, then refreshes.
- **Native.** Kotlin and Jetpack Compose, with Forgeline's own soft, readable design.
- **Private.** No backend, no analytics, no trackers. The app talks directly to your forge.
- **Free.** GPL-3.0.

## Building

Requirements: the Android SDK (API 37). Gradle downloads its own JDK 21 on first run.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug        # build
./gradlew testDebugUnitTest    # JVM tests (unit, Robolectric, screenshots)
```

Sign in works out of the box with a personal access token. To enable the one-tap "Sign in with GitHub" device flow, see [docs/GITHUB_OAUTH_APP.md](docs/GITHUB_OAUTH_APP.md). Releases are covered in [docs/RELEASING.md](docs/RELEASING.md).

## License

Forgeline is free software, licensed under the [GNU General Public License v3.0](LICENSE).
