# Codeberg OAuth application (sign-in with PKCE)

Forgeline signs in to Codeberg with OAuth2's authorization code flow and [PKCE](https://datatracker.ietf.org/doc/html/rfc7636): the app opens Codeberg's consent page in a Custom Tab, you approve, and Codeberg sends you back to the app. Forgejo has no device flow, so this replaces GitHub's "type a code" step. It needs an OAuth application **client ID**, but **no client secret**, so no server is involved and the ID can live in the repository.

Until the client ID is set, the "Sign in with Codeberg" button is hidden and only access tokens are offered.

## One-time setup

1. Sign in to Codeberg and go to **Settings → Applications → Manage OAuth2 applications**
   (direct link: https://codeberg.org/user/settings/applications).
2. Fill in:
   - **Application name:** `Forgeline`
   - **Redirect URIs:** `forgeline://oauth/codeberg`
   - **Confidential client:** **untick it.** A public client is what makes PKCE mandatory and the secret unnecessary.
3. Click **Create application**.
4. Copy the **Client ID** (a UUID). Codeberg also shows a client secret: don't copy it anywhere, Forgeline doesn't use it.
5. Put the ID in `gradle.properties` and commit:

   ```properties
   forgeline.codebergClientId=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
   ```

   To try a build without committing, pass it on the command line instead:
   `./gradlew :app:installDebug -Pforgeline.codebergClientId=…`

The redirect URI must match exactly: Forgejo compares it case-insensitively, ignoring a trailing `/`, and doesn't restrict the scheme for public clients. **verify** on codeberg.org that the form accepts a custom scheme.

## How the flow works

1. The app makes a random `code_verifier` and its SHA-256 `code_challenge`, and opens
   `https://codeberg.org/login/oauth/authorize?client_id=…&redirect_uri=forgeline://oauth/codeberg&response_type=code&state=…&code_challenge=…&code_challenge_method=S256`.
2. After approval, Codeberg redirects to `forgeline://oauth/codeberg?code=…&state=…`, which Android hands back to Forgeline. The app checks `state`.
3. The app exchanges the code at `POST https://codeberg.org/login/oauth/access_token` with `grant_type=authorization_code`, the `code`, the `redirect_uri`, the `client_id` and the `code_verifier`.
4. The response holds an `access_token` (valid for an hour) and a `refresh_token` (valid for 730 hours by Forgejo's default). The app stores both, encrypted with the Android Keystore like GitHub tokens, and trades the refresh token for a new pair (`grant_type=refresh_token`) whenever the access token has expired or a call returns 401. An account unused for longer than the refresh token's life asks to sign in again.

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
