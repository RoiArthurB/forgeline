# Codeberg OAuth application (sign-in with PKCE)

Forgeline signs in to Codeberg with OAuth2's authorization code flow and [PKCE](https://datatracker.ietf.org/doc/html/rfc7636): the app opens Codeberg's consent page in a Custom Tab, you approve, and Codeberg sends the browser back to a tiny web server the app runs on `127.0.0.1` for that moment. Forgejo has no device flow, so this replaces GitHub's "type a code" step. It needs an OAuth application **client ID**, but **no client secret**, so no server is involved and the ID can live in the repository.

Until the client ID is set, the "Sign in with Codeberg" button is hidden and only access tokens are offered.

## One-time setup

1. Sign in to Codeberg and go to **Settings → Applications → Manage OAuth2 applications**
   (direct link: https://codeberg.org/user/settings/applications).
2. Fill in:
   - **Application name:** `Forgeline`
   - **Redirect URIs:** `http://127.0.0.1/oauth/codeberg`
   - **Confidential client:** **untick it.** A public client is what makes PKCE mandatory and the secret unnecessary.
3. Click **Create application**.
4. Copy the **Client ID** (a UUID). Codeberg also shows a client secret: don't copy it anywhere, Forgeline doesn't use it.
5. Put the ID in `gradle.properties` and commit:

   ```properties
   forgeline.codebergClientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   ```

   To try a build without committing, pass it on the command line instead:
   `./gradlew :app:installDebug -Pforgeline.codebergClientId=…`

**Why not `forgeline://`?** Codeberg rejects it: "`forgeline://oauth/codeberg` is not a valid URL" (tried 2026-09-29). Forgejo only accepts `http` and `https` redirect URIs (`modules/validation/helpers.go`). For public clients it ignores the port of an `http` redirect to a loopback IP address (`ContainsRedirectURI` in `models/auth/oauth2.go`, following [RFC 8252 §7.3](https://datatracker.ietf.org/doc/html/rfc8252#section-7.3)), so registering `http://127.0.0.1/oauth/codeberg` accepts `http://127.0.0.1:43123/oauth/codeberg` and any other port. It must be the IP address: `localhost` isn't treated as a loopback there.

## How the flow works

1. The app starts listening on `127.0.0.1` on a free port, makes a random `code_verifier` and its SHA-256 `code_challenge`, and opens
   `https://codeberg.org/login/oauth/authorize?client_id=…&redirect_uri=http://127.0.0.1:<port>/oauth/codeberg&response_type=code&state=…&code_challenge=…&code_challenge_method=S256`.
2. After approval, Codeberg redirects the browser to `http://127.0.0.1:<port>/oauth/codeberg?code=…&state=…`. The app answers with a short "Signed in" page and a "Back to Forgeline" link, stops listening, and checks `state`. Closing the tab also leads back to the app.
3. The app exchanges the code at `POST https://codeberg.org/login/oauth/access_token` with `grant_type=authorization_code`, the `code`, the `redirect_uri`, the `client_id` and the `code_verifier`.
4. The response holds an `access_token` (valid for an hour) and a `refresh_token` (valid for 730 hours by Forgejo's default). The app stores both, encrypted with the Android Keystore like GitHub tokens, and trades the refresh token for a new pair (`grant_type=refresh_token`) a minute before the access token expires. An account unused for longer than the refresh token's life asks to sign in again.

## Why this is safe to publish

A client ID only identifies the app on Codeberg's consent screen. PKCE ties each authorization code to the device that started the flow: without the `code_verifier`, which never leaves the phone, an intercepted code is useless. This is the flow [RFC 8252](https://datatracker.ietf.org/doc/html/rfc8252) recommends for native apps, and Forgejo requires PKCE for every public client.

## Access tokens instead

For self-hosted Forgejo instances, or if you'd rather not use OAuth, create a token under **Settings → Applications → Access tokens** with these permissions:

| Permission | Level | Used for |
|---|---|---|
| notification | read and write | the Inbox, marking threads read |
| user | read and write | your profile, following people, starring |
| repository | read | repositories, READMEs, code |
| issue | read | issues and pull requests |
| organization | read | organization profiles and feeds |

Access tokens don't expire unless you set an expiry, and no refresh is involved.
