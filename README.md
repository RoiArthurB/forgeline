# Forgeline

A fast, discovery-first Android client for code forges.

Forgeline brings your forges to your phone as a timeline: an **Inbox** for what needs you, a **Feed** of what the people you follow are doing, and a **Trending** page for the daily discovery ritual. It opens in about a second and renders everything natively, so you never get bounced to a mobile web page.

> **Status:** early development (v0.1 PoC). GitHub first; GitLab and Gitea/Forgejo (including self-hosted instances) are planned.

## What's in v0.1

- **Inbox:** your GitHub notifications grouped by repository, with Unread, Participating and All filters. Swipe right to mark a thread read, left to mark it done. Background checks (every hour by default, or off) post phone notifications on separate channels, so you can mute CI without missing mentions.
- **Feed:** what the people you follow and the repositories you watch are doing, in strict chronological order. Identical events on the same repository merge ("alice and 2 others starred…"). You choose which kinds of activity show up in Settings.
- **Trending:** GitHub's trending repositories for today, this week or this month, exactly as GitHub ranks them. It works without signing in.
- **Repositories:** the README rendered natively, a code browser with syntax highlighting, and issues, pull requests, releases and Actions runs.
- **Conversations and profiles:** issue and pull request timelines with reviews, merges and reactions, and user and organization profiles. You can star and follow from the app.
- **Search:** repositories, issues and pull requests, and people, with GitHub's search syntax (`language:kotlin`, `is:open`, …). There is no code search, by design.
- **github.com links open in the app.** Android doesn't send them to Forgeline automatically, because the app can't verify a domain it doesn't own. To turn it on, go to *Settings → Apps → Forgeline → Open by default → Add links* and select `github.com`.

Sign in with the one-tap device flow, or with a classic personal access token that has the `notifications`, `read:user`, `user:follow` and `public_repo` scopes.

## Principles

- **Fast.** Cache-first: every screen renders from the local database instantly, then refreshes.
- **Native.** Kotlin, Jetpack Compose and Material You, following Android's design guidelines.
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
