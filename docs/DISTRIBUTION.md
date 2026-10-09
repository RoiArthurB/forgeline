# Distributing Forgeline

Forgeline is published as a signed APK on [GitHub Releases](https://github.com/RoiArthurB/forgeline/releases) (see [RELEASING.md](RELEASING.md)). It isn't on F-Droid or Google Play yet. This page says what is ready for them in the repository, and what is left, which only the maintainer can do.

## What is ready

- **The store listing**, in English and French, under `fastlane/metadata/android/<locale>/`: `title.txt`, `short_description.txt` (80 characters at most), `full_description.txt` (4000 at most) and `images/phoneScreenshots/`. F-Droid reads this layout straight from the repository, and `fastlane supply` uploads the same files to Google Play.
- **Nothing proprietary to remove.** Every dependency is free software (AndroidX, Kotlin, Hilt, Room, Ktor, OkHttp, Coil, the Markdown renderer, jsoup, SnakeYAML). There are no Google Play services, no analytics and no trackers, so F-Droid has no anti-feature to flag beyond the forges being network services the app doesn't control.
- **The build needs no secret.** The OAuth application IDs in `gradle.properties` are public by nature (device flow and PKCE, no client secret), so a build from source signs in like the published one.

The screenshots there are the test suite's own (`app/src/test/screenshots`), with its made-up repositories. They are honest pictures of the app; replace them with ones of real content before a listing goes live if you prefer.

## What is left

### The version code (both stores)

A release's `versionCode` is the number of the workflow run that built it. A store that builds from source (F-Droid) can't know that number, and both stores need each release's code to be higher than the last.

The simplest way out is to derive it from the tag: `major × 10000 + minor × 100 + patch`, so `v0.8.0` is `800`. Every code published so far is a small run number, so the first derived one is already higher and existing installs update normally. It is a change to `release.yml` (one line) and to RELEASING.md, to make before the first store release, and never to undo.

### F-Droid

1. Make the version code derivable (above).
2. Open a merge request on [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/fr.arthurbrugiere.forgeline.yml`. A starting point:

   ```yaml
   Categories:
     - Development
     - Internet
   License: GPL-3.0-only
   SourceCode: https://github.com/RoiArthurB/forgeline
   IssueTracker: https://github.com/RoiArthurB/forgeline/issues
   Changelog: https://github.com/RoiArthurB/forgeline/tree/main/docs/release-notes

   AutoName: Forgeline

   RepoType: git
   Repo: https://github.com/RoiArthurB/forgeline.git

   Builds:
     - versionName: 0.8.0
       versionCode: 800
       commit: v0.8.0
       subdir: app
       gradle:
         - yes
       gradleprops:
         - forgeline.versionName=0.8.0
         - forgeline.versionCode=800

   AutoUpdateMode: Version
   UpdateCheckMode: Tags ^v[0-9.]+$
   ```

   `AutoUpdateMode` needs F-Droid to read the version from the repository at each tag: either keep `forgeline.versionName` and `forgeline.versionCode` in `gradle.properties` up to date when tagging, or have the build compute them from the tag.
3. F-Droid signs with its own key unless the build is reproducible, so an install from F-Droid and one from GitHub Releases can't update each other. That is the usual arrangement; say so in the README once it is listed.
4. Codeberg's and GitLab's Trending lists are read from `arthurbrugiere.fr`. F-Droid's reviewers may ask about it: it is a static file of public data, the app works without it, and its address is a build property.

### Google Play

1. A Google Play developer account (a one-time fee, and identity verification), then an app created for `fr.arthurbrugiere.forgeline`.
2. At the time of writing, a new personal account must run a closed test with at least twelve testers for fourteen days before it can publish to production.
3. The forms only the owner can fill: data safety (no data collected; tokens stay on the device), content rating, target audience, and a privacy policy address.
4. Play wants an Android App Bundle (`./gradlew :app:bundleRelease`) signed with an upload key; the current keystore can serve as that key under Play App Signing.
5. The listing texts and screenshots upload with `fastlane supply --metadata_path fastlane/metadata/android`, or by hand.
