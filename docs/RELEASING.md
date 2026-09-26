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

```sh
git tag v0.1.0
git push origin v0.1.0
```

- The tag (without the `v`) becomes `versionName`; the workflow run number becomes `versionCode`, so it always increases.
- Tags containing a `-` (e.g. `v0.2.0-beta1`) are published as pre-releases.
- Release notes are generated from the commits since the previous tag.

## Signing a build locally

The release build type picks up the same environment variables as CI:

```sh
FORGELINE_KEYSTORE_PATH=/path/to/forgeline-release.jks \
FORGELINE_KEYSTORE_PASSWORD=... FORGELINE_KEY_ALIAS=forgeline FORGELINE_KEY_PASSWORD=... \
./gradlew :app:assembleRelease
```

Without them, `assembleRelease` produces an unsigned APK.
