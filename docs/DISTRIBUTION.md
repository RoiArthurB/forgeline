# Distributing Forgeline

Forgeline is published as a signed APK on [GitHub Releases](https://github.com/RoiArthurB/forgeline/releases) (see [RELEASING.md](RELEASING.md)). It isn't on F-Droid, IzzyOnDroid or Google Play yet. This page says what is ready for them in the repository, and what is left, which only the maintainer can do.

## What is ready

- **The store listing**, in English and French, under `fastlane/metadata/android/<locale>/`: `title.txt`, `short_description.txt` (80 characters at most), `full_description.txt` (4000 at most) and `images/phoneScreenshots/`. F-Droid reads this layout straight from the repository, and `fastlane supply` uploads the same files to Google Play.
- **Nothing proprietary to remove.** Every dependency is free software (AndroidX, Kotlin, Hilt, Room, Ktor, OkHttp, Coil, the Markdown renderer, jsoup, SnakeYAML). There are no Google Play services, no analytics and no trackers, so F-Droid has no anti-feature to flag beyond the forges being network services the app doesn't control.
- **No blob only Google can read.** The APK leaves out the dependency list the Android Gradle plugin encrypts with Google Play's key, which F-Droid and IzzyOnDroid flag (`dependenciesInfo` in `app/build.gradle.kts`).
- **The build needs no secret.** The OAuth application IDs in `gradle.properties` are public by nature (device flow and PKCE, no client secret), so a build from source signs in like the published one.

The screenshots there are the test suite's own (`app/src/test/screenshots`), with its made-up repositories. They are honest pictures of the app; replace them with ones of real content before a listing goes live if you prefer.

## What is left

### The version code

Done: a release's `versionCode` is worked out from its tag, `major × 10000 + minor × 100 + patch` (`v0.12.0` is `1200`), and the release carries a `version.txt` next to the APK:

```
versionName=0.12.0
versionCode=1200
```

Nothing in the repository is bumped by hand (see [RELEASING.md](RELEASING.md)). F-Droid reads that file to find new releases; IzzyOnDroid reads the APK itself.

### IzzyOnDroid

It repackages nothing: it takes the APK from the latest GitHub release, with its code and signature as they are.

1. Ask for inclusion on [its issue tracker](https://gitlab.com/IzzyOnDroid/repo/-/issues) with the repository address. The listing texts and screenshots come from `fastlane/metadata/android/`.
2. Every release after that is picked up on its own, as long as the APK stays named `forgeline-*.apk` and signed with the same key.

### F-Droid

1. Open a merge request on [fdroiddata](https://gitlab.com/fdroid/fdroiddata) adding `metadata/fr.arthurbrugiere.forgeline.yml`, with the latest release in `Builds` (here 0.12.0):

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
     - versionName: 0.12.0
       versionCode: 1200
       commit: v0.12.0
       subdir: app
       prebuild: sed -i -e 's/^forgeline.versionName=.*/forgeline.versionName=$$VERSION$$/' -e 's/^forgeline.versionCode=.*/forgeline.versionCode=$$VERCODE$$/' ../gradle.properties
       gradle:
         - yes

   AutoUpdateMode: Version v%v
   UpdateCheckMode: HTTP
   UpdateCheckData: https://github.com/RoiArthurB/forgeline/releases/latest/download/version.txt|versionCode=(\d+)|.|versionName=(\S+)
   ```

   How it keeps up: F-Droid's bot fetches `version.txt` from the latest release. When its code is new, it copies the last build entry with the new name and code, and builds the tag `v<versionName>`. The `prebuild` line writes both values into `gradle.properties` before building, through F-Droid's `$$VERSION$$` and `$$VERCODE$$` variables: `gradleprops` would be copied as they are from the previous entry, with the old numbers. So F-Droid needs no change in the repository at each release, and the two `forgeline.version*` lines of `gradle.properties` must stay.
2. F-Droid signs with its own key unless the build is reproducible, so an install from F-Droid and one from GitHub Releases can't update each other. That is the usual arrangement; say so in the README once it is listed.
3. Codeberg's and GitLab's Trending lists are read from `arthurbrugiere.fr`. F-Droid's reviewers may ask about it: it is a static file of public data, the app works without it, and its address is a build property.

### Google Play

1. A Google Play developer account (a one-time fee, and identity verification), then an app created for `fr.arthurbrugiere.forgeline`.
2. At the time of writing, a new personal account must run a closed test with at least twelve testers for fourteen days before it can publish to production.
3. The forms only the owner can fill: data safety (no data collected; tokens stay on the device), content rating, target audience, and a privacy policy address.
4. Play wants an Android App Bundle (`./gradlew :app:bundleRelease`) signed with an upload key; the current keystore can serve as that key under Play App Signing.
5. The listing texts and screenshots upload with `fastlane supply --metadata_path fastlane/metadata/android`, or by hand.
