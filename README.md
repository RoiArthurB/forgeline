# Forgeline

A fast, discovery-first Android client for code forges.

Forgeline brings your forges to your phone as a timeline: an **Inbox** for what needs you, a **Feed** of what the people you follow are doing, and a **Trending** page for the daily discovery ritual. It opens in about a second and renders everything natively, so you never get bounced to a mobile web page.

> **Status:** early development (v0.2, the first release). GitHub first; GitLab and Gitea/Forgejo (including self-hosted instances) are planned.

## What's in v0.2

- **Inbox:** what needs you first (review requests, mentions, assignments, security alerts), then everything else grouped by owner and repository. Pull requests and issues say whether they're open, merged or closed. Swipe right to mark read, left to mark done, with 5 seconds to Undo. Background checks (every hour by default, or off) post phone notifications on separate channels, and the conversations waiting on you are loaded ahead so they open instantly.
- **Feed:** what the people you follow and the repositories you watch are doing, in strict chronological order, grouped by day, each event leading with the repository or pull request it's about. Identical events on the same repository merge. You choose which kinds of activity show up in Settings.
- **Trending:** GitHub's trending repositories for today, this week or this month, exactly as GitHub ranks them. It works without signing in.
- **Where you left off:** Trending and the Feed mark where your last visit's reading stopped.
- **Repositories:** the README rendered natively, a code browser with syntax highlighting at any branch or tag, and issues, pull requests, releases and Actions.
- **GitHub Actions:** runs with their jobs and failed steps, job logs with colors and folded sections, live steps while a job runs, re-run and cancel, and starting a workflow by hand with its inputs.
- **Conversations and profiles:** issue and pull request timelines with reviews, merges and reactions, kept on your phone so they reopen instantly, and user and organization profiles. You can star and follow from the app.
- **Search:** repositories, issues and pull requests, and people, with GitHub's search syntax (`language:kotlin`, `is:open`, …). There is no code search, by design.
- **github.com links open in the app.** Android doesn't send them to Forgeline automatically, because the app can't verify a domain it doesn't own. To turn it on, go to *Settings → Apps → Forgeline → Open by default → Add links* and select `github.com`.

Release notes for each version are in [docs/release-notes](docs/release-notes).

Sign in with the one-tap device flow, or with a classic personal access token that has the `notifications`, `read:user`, `user:follow` and `public_repo` scopes.

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
