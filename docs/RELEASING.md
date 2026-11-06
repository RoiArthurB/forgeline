# Releasing Forgeline

Releases are built and signed by GitHub Actions (`.github/workflows/release.yml`) when a `v*` tag is pushed. The signed APK is attached to a GitHub Release.

## One-time setup: the signing key

Android only lets an update install over an existing app if **both are signed with the same key**. Keep the keystore and its passwords somewhere safe (a password manager); if you lose them, existing users will have to uninstall to get updates.

1. Generate the keystore (valid for ~27 years):

   ```sh
   keytool -genkeypair -v \
     -keystore forgeline-release.jks \
     -alias forgeline \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

   `keytool` ships with any JDK. Gradle's own JDK is in `~/.gradle/jdks/*/bin/keytool` if you don't have one installed.

2. Add four repository secrets (GitHub → Settings → Secrets and variables → Actions → New repository secret), or with the `gh` CLI:

   ```sh
   base64 -w0 forgeline-release.jks | gh secret set KEYSTORE_BASE64 --repo RoiArthurB/forgeline
   gh secret set KEYSTORE_PASSWORD --repo RoiArthurB/forgeline   # prompts for the value
   gh secret set KEY_ALIAS --repo RoiArthurB/forgeline --body forgeline
   gh secret set KEY_PASSWORD --repo RoiArthurB/forgeline        # same as KEYSTORE_PASSWORD unless you chose another
   ```

3. Never commit the `.jks` file (`*.jks` is git-ignored).

## Cutting a release

1. Write the release notes in `docs/release-notes/<tag>.md` (e.g. `docs/release-notes/v0.2.0.md`) and commit them. They become the GitHub Release's text; the workflow refuses a tag without them.
2. Tag and push:

   ```sh
   git tag -a v0.2.0 -m "Forgeline 0.2.0"
   git push origin v0.2.0
   ```

- The tag must be `vX.Y.Z`, with Y and Z at most 99. Without the `v` it becomes `versionName`, and `versionCode` is worked out from it as `X × 10000 + Y × 100 + Z` (`v0.12.0` is `1200`) by `tools/release/version.sh`. Any other tag stops the workflow before it builds. Nothing in the repository is bumped by hand: `gradle.properties` only versions local builds.
- The release gets a `version.txt` next to the APK, with both values. F-Droid reads it to find new releases (see [DISTRIBUTION.md](DISTRIBUTION.md)); keep the file and its two keys.
- Releases v0.1.0 to v0.11.0 were coded with the workflow's run number (10 at most), so the first worked-out code is higher and installs update normally. Codes must keep rising: if the formula changes, its weights can only grow.
- GitHub's generated release notes aren't used: they list pull requests, and Forgeline commits straight to `main`.
- Once the release is up, the workflow republishes the landing page, which names the latest release wherever `site/index.html` marks an element `data-version`. Nothing to bump by hand.

## Signing a build locally

The release build type picks up the same environment variables as CI:

```sh
FORGELINE_KEYSTORE_PATH=/path/to/forgeline-release.jks \
FORGELINE_KEYSTORE_PASSWORD=... FORGELINE_KEY_ALIAS=forgeline FORGELINE_KEY_PASSWORD=... \
./gradlew :app:assembleRelease
```

Without them, `assembleRelease` produces a debug-signed APK, fine for testing on your own device but not for distribution.
