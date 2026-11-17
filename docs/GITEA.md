# Gitea

Gitea is the forge Forgejo forked from, and its API is still Forgejo's (`/api/v1`). Forgeline talks to gitea.com and to any self-hosted Gitea with the Forgejo client (`forge/forgejo`), as `ForgeType.GITEA`, so everything in [CODEBERG.md](CODEBERG.md) holds unless this page says otherwise.

## How the two were compared

Every call the Forgejo client makes, and every query parameter it sends, was checked against Gitea's own API description (`templates/swagger/v1_json.tmpl`) for v1.24, v1.26 and v1.27, on 2026-11-02. Gitea itself wasn't reachable from where that was done, so `GiteaLiveTest` (run nightly with the other live tests) checks the same against gitea.com.

## Telling one from the other

Both answer `GET /api/v1/version` to anyone. Forgejo since v7 numbers itself and adds the Gitea it's compatible with (`16.0.0-dev-753-6bcc6da0+gitea-1.22.0`); its 1.x releases ended in a release number (`1.21.11-1`). Gitea has neither, whatever its number: `1.x.y`, `1.x.y+dev-…` for a build of main, and `29.0.0+dev-…` on gitea.com since November 2026. `forgejoOrGitea` in `ForgeProbe.kt` reads that, so "your own server" needs no more than its address.

## Where Gitea differs

| Need | Forgejo | Gitea | In Forgeline |
|---|---|---|---|
| Re-run a run | none | `POST …/actions/runs/{id}/rerun` and `rerun-failed-jobs` (v1.26+) | Offered on Gitea |
| Cancel a run | `POST …/actions/runs/{id}/cancel` | none | The button is hidden on Gitea |
| List runs and their jobs | yes | since v1.26 | Older servers show the run's page link, as older Forgejo does |
| Run and job answers | Forgejo's own shape | GitHub's shape (`display_title`, `status` + `conclusion`, jobs in `{"jobs": […]}` with their steps) | Each read in its own shape |
| Runs signed out | listed | gitea.com lists them only to someone signed in (2026-11-09), but shows each run and the jobs list (`…/actions/tasks`) | Signed out, runs come from the newest jobs, each run read on its own |
| Workflows | no list (read from `.forgejo/workflows`, `.gitea/`, `.github/`) | `GET …/actions/workflows` | The folders are read on both, which works on both |
| Profile README | `.profile` repository | `.profile` repository | Same |

## Signing in

With an access token, on gitea.com and on any server (Settings → Applications → Generate New Token on gitea.com, with read and write access to issues, notifications, repository and user). On gitea.com, also through the browser, the same way as Codeberg's ([CODEBERG_OAUTH_APP.md](CODEBERG_OAUTH_APP.md)): pick **Gitea** on the sign-in screen and **Sign in with Gitea**. Its OAuth application is registered on gitea.com (Settings → Applications → OAuth2 Applications) as a public client (**Confidential client** unticked) with the redirect URI `http://127.0.0.1/oauth/gitea`; Gitea, like Forgejo, ignores the port of a loopback redirect for public clients. Its client ID is `forgeline.giteaClientId` in `gradle.properties`; empty, gitea.com offers access tokens only. A self-hosted Gitea has no application of Forgeline's, so it signs in with a token.

## Trending

Gitea has no Trending, so gitea.com's is measured by the same daily job as Codeberg's, with the same code and the same rules ([CODEBERG.md](CODEBERG.md#trending)): its repositories by stars, then its newest forks, published as `gitea/trending.json` (`forgeline.giteaTrendingUrl`) and mixed into everyone's Trending page.

- **Signed out.** gitea.com answered repository search anonymously on 2026-11-17, so the job reads it without a token; a `GITEA_TOKEN` repository secret would be used if it ever starts refusing, as Codeberg did. `GiteaLiveTest` checks the job's two queries nightly.
- **Small.** On 2026-11-17 its most starred repository had 412 stars, the 50th had 8, and repositories fell under 2 stars on the fourth page: the job tracks about 220 repositories in four to five pages, 7 to 10 seconds each. Most days nothing will pass the app's floors (5 stars a day, 10 a week, 20 a month); a repository that does shows as any other forge's.
- **Its first days.** As for Codeberg, a period's list only holds repositories with a starting point: on day one, only those created within the period (gitea/cli, created that month with 24 stars, was the first to pass the monthly floor).

A self-hosted Gitea server has nobody publishing its list: signed in to it, it can be measured on the phone as any Forgejo can, and gitea.com too, instead of the published list.
