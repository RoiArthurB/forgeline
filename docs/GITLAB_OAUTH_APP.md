# GitLab OAuth applications (sign-in through the browser)

Forgeline signs in to GitLab the way it does to Codeberg ([CODEBERG_OAUTH_APP.md](CODEBERG_OAUTH_APP.md)): OAuth2's authorization code flow with [PKCE](https://datatracker.ietf.org/doc/html/rfc7636), the consent page in a Custom Tab, and a redirect to a tiny web server the app runs on `127.0.0.1` for the length of the sign-in. No client secret is involved.

A GitLab server only does this for an application registered on it. So there are two cases:

- **gitlab.com:** one application, Forgeline's own, registered once by the project's owner. Its ID is in `gradle.properties` (`forgeline.gitlabClientId`). Until it is set, gitlab.com offers access tokens only.
- **A self-hosted GitLab:** the application lives on that server, so whoever signs in creates it there (any user can, no administrator needed) and pastes its ID in the sign-in screen's "Application ID" field. Without one, an access token works.

## Registering the application

On gitlab.com: https://gitlab.com/-/user_settings/applications. On another server: **Preferences → Applications** (`https://<your server>/-/user_settings/applications`).

1. **Name:** `Forgeline`
2. **Redirect URI:** `http://127.0.0.1/oauth/gitlab`
3. **Confidential:** **untick it.** A public client is what makes PKCE mandatory and the secret unnecessary.
4. **Scopes:** `api` and `read_user`.
5. Save, then copy the **Application ID**. GitLab also shows a secret: Forgeline doesn't use it, don't copy it anywhere.

For gitlab.com, put the ID in `gradle.properties` and commit:

```properties
forgeline.gitlabClientId=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

For a self-hosted server, paste it in the app: **Sign in → Other →** the server's address **→ Application ID**. The app keeps it with the server (it is needed again each time the sign-in is renewed).

## Why the port isn't in the redirect URI

The app listens on a free port, different each time. GitLab's OAuth library (Doorkeeper) follows [RFC 8252 §7.3](https://datatracker.ietf.org/doc/html/rfc8252#section-7.3): when both the registered and the requested redirect are loopback addresses, the port is ignored and everything else must match (read in Doorkeeper's `uri_checker.rb`, 2026-10-04). **Not yet tried against a GitLab server from the app:** if a server refuses the redirect, that is where to look.

## What the tokens are

GitLab's access tokens last two hours and come with a refresh token; the app renews them by itself, like Codeberg's. A client ID only identifies the app on the consent screen: PKCE ties each authorization code to the phone that started the flow.

## How another server is recognised

"Other" takes an address and nothing else. The app asks it two things at once (`HttpForgeProbe`): `/api/v1/version`, which Forgejo and Gitea answer to anyone, and `/api/v4/version`, which GitLab refuses with a 401 in its API's own JSON. What the server runs is remembered with its host (`StoredForgeHosts`, a small preferences file read at launch), because most of the app carries only the host and would otherwise take it for a Forgejo.
