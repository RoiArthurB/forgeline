# Forgeline

A fast, discovery-first Android client for code forges.

Forgeline brings your forges to your phone as a timeline: an **Inbox** for what needs you, a **Feed** of what the people you follow are doing, and a **Trending** page for the daily discovery ritual. It opens in about a second and renders everything natively, so you never get bounced to a mobile web page.

> **Status:** early development (v0.1 PoC). GitHub first; GitLab and Gitea/Forgejo (including self-hosted instances) are planned.

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

## License

Forgeline is free software, licensed under the [GNU General Public License v3.0](LICENSE).
