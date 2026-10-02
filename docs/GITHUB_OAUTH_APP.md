# GitHub OAuth App (device flow sign-in)

Forgeline signs in with GitHub's [device flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow): the app shows a short code, you type it on github.com, done. It needs an OAuth App **client ID**, but **no client secret**, so no server is involved and the ID can live in the repository.

Until the client ID is set, the "Sign in with GitHub" button is hidden and only personal access tokens are offered.

## One-time setup

1. Go to **GitHub → Settings → Developer settings → OAuth Apps → New OAuth App**
   (direct link: https://github.com/settings/applications/new).
2. Fill in:
   - **Application name:** `Forgeline`
   - **Homepage URL:** `https://github.com/RoiArthurB/forgeline`
   - **Authorization callback URL:** `https://github.com/RoiArthurB/forgeline` (required by the form, unused by the device flow)
3. Click **Register application**.
4. On the app's page, tick **Enable Device Flow** and save.
5. Copy the **Client ID** (it looks like `Ov23li…`). Do **not** generate a client secret; Forgeline doesn't need one.
6. Put it in `gradle.properties` and commit:

   ```properties
   forgeline.githubClientId=Ov23liXXXXXXXXXXXXXX
   ```

   To try a build without committing, pass it on the command line instead:
   `./gradlew :app:installDebug -Pforgeline.githubClientId=Ov23li…`

## Why this is safe to publish

A client ID only identifies the app on GitHub's consent screen ("Forgeline wants to access your account"). With the device flow, every sign-in still requires the user to approve it on github.com, and tokens are issued only to the device that started the flow. The secret-less flow is exactly what GitHub recommends for apps that can't keep a secret, like mobile apps.

## Scopes requested

`notifications read:user user:follow repo`: read and manage notifications, read your profile, follow/unfollow people, and read, star, comment on, open issues in and run workflows in your repositories, private ones included. GitHub has no narrower scope that reads a private repository, so its consent screen says "full control of private repositories"; Forgeline never writes code. A sign-in made with the earlier `public_repo` scope keeps working on public repositories, and the You tab offers to renew it. The same scopes are pre-filled when creating a personal access token from the sign-in screen.
