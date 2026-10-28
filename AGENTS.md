## Workflow

- setup: Android SDK (API 37) in `local.properties` (`sdk.dir=...`); Gradle fetches its own JDK 21
- build: `./gradlew assembleDebug`
- test all (what CI runs): `./gradlew spotlessCheck testDebugUnitTest lintDebug assembleDebug --continue`
- test one module: `./gradlew :core:data:testDebugUnitTest` (or `:app:`, `:forge:github:`, ...)
- test one class or case: `./gradlew :app:testDebugUnitTest --tests "fr.arthurbrugiere.forgeline.feed.FeedViewModelTest"` (append `.methodName` for one case)
- emulator tests: `./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest`
- live contract tests (real forges): `./gradlew :forge:github:liveTest :forge:forgejo:liveTest :forge:gitlab:liveTest --continue`, with `LIVE_TEST_PAT`, `CODEBERG_LIVE_TOKEN`, `GITLAB_LIVE_TOKEN` in the environment; checks without a token are skipped
- screenshots: `./gradlew :app:recordRoborazziDebug` renders `app/src/test/screenshots/` (git-ignored, not compared in CI)
- lint: `./gradlew lintDebug`
- format: `./gradlew spotlessApply` (CI runs `spotlessCheck`; rules in `.editorconfig`)
- baseline profile: `./gradlew :app:generateBaselineProfile` on the managed `pixel6Api34` device

## Layout

- `app`: screens, view-models, navigation, notifications, widget
- `core/model`, `core/data` (Room cache, repositories), `core/forge` (the forge interface), `core/ui`, `core/markdown`, `core/testing` (fakes)
- `forge/common`, `forge/github`, `forge/gitlab`, `forge/forgejo` (Codeberg and self-hosted Forgejo): one client per forge
- `baselineprofile`: profile generation and benchmarks
- `site`: the landing page; `fastlane/metadata`: store listings; `docs`: per-forge notes, release notes, releasing

## Conventions

- Every change ships with tests: view-model and Robolectric screen tests in `app/src/test`, API tests against recorded fixtures per forge, and a live contract test when a new forge endpoint is used
- Nothing is tied to one forge: a feature a forge can't do is hidden or says so, never faked
- Behaviour choices go in Settings, with the current behaviour as the default
- New Hilt bindings need a twin in the androidTest fake module (`app/src/androidTest/.../di`)
- Product and design rules: `PRODUCT.md` and `DESIGN.md`
- Release: tag `vX.Y.Z` with `docs/release-notes/vX.Y.Z.md` written first; the workflow takes the version name from the tag (see `docs/RELEASING.md`)
- Scheduled workflows: live contract tests nightly at 03:00 UTC, Trending snapshot daily, performance benchmarks weekly

## Commits

Trunk-based: commit to `main`. Subjects are short imperatives saying what the user gets, as in `git log` (e.g. "Show an issue's parent and sub-issues, and change them", "Mark a pull request's files as viewed"). Each commit is self-contained and does one logical change.
