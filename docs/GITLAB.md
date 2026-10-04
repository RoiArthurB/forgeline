# GitLab

Forgeline natively supports GitLab (`gitlab.com` and self-hosted instances running GitLab CE/EE). Users can sign in, browse projects, triage issues and merge requests, review CI/CD pipelines and job logs, receive inbox notifications via GitLab Todos, view user/group activity feeds, and follow GitLab deep links.

---

## Authentication

### 1. Browser Sign-In (OAuth 2.0 with PKCE)
For `gitlab.com`, browser sign-in uses OAuth 2.0 with PKCE (Proof Key for Code Exchange, RFC 7636) and a loopback redirect on the device:
- **Authorization Endpoint:** `https://gitlab.com/oauth/authorize`
- **Token Endpoint:** `https://gitlab.com/oauth/token`
- **Scopes:** `api read_user openid`
- **Client ID:** Configured via `forgeline.gitlabClientId` in `gradle.properties`. When omitted, sign-in falls back to Personal Access Tokens.

### 2. Personal Access Tokens (PAT)
Users can sign in to `gitlab.com` or self-hosted instances with a Personal Access Token created at:
- `https://<host>/-/user_settings/personal_access_tokens`
- Required scopes: `api`, `read_user`

---

## Architecture & API Mapping (`:forge:gitlab`)

GitLab's REST API v4 (`/api/v4`) is wrapped cleanly into Forgeline's `core:forge` contracts.

### Project Identifiers & URL Encoding
In GitLab, project paths are hierarchical and can contain multiple nested namespaces (e.g. `gitlab-org/subgroup/project`).
- API paths require URL-encoding slashes in the path: `/api/v4/projects/gitlab-org%2Fsubgroup%2Fproject`.
- Forgeline's `encodePath(repo.fullName)` encodes namespaces to `%2F` while preserving parameter safety across Ktor 3 requests.

### Repositories (`GitLabRepoApi`)
- **Metadata:** `GET /projects/:id`
- **README:** `GET /projects/:id/repository/files/:file_path?ref=:ref` (with automatic fallback to default branches and README file patterns: `README.md`, `README.rst`, `README`, etc.).
- **Branches & Tags:** `GET /projects/:id/repository/branches` and `GET /projects/:id/repository/tags` (tags sorted using `VersionOrder.reversed()`).
- **File Tree & Contents:** `GET /projects/:id/repository/tree` and raw downloads from `GET /projects/:id/repository/files/:file/raw`.
- **Releases:** `GET /projects/:id/releases` with release asset descriptions and source code links.

### Issues & Merge Requests (`GitLabIssueApi`)
Unlike GitHub and Forgejo where pull requests share the issue numbering namespace, GitLab separates issues and merge requests with independent numeric IDs:
- **Issues:** `GET|POST|PUT /projects/:id/issues`
- **Merge Requests:** `GET|POST|PUT /projects/:id/merge_requests`
- `IssueRef(repo, number, isPullRequest)` differentiates issues from merge requests.
- **Notes & Discussions:** `GET|POST /projects/:id/(issues|merge_requests)/:iid/notes`
- **Reactions (Award Emoji):** `GET|POST|DELETE /projects/:id/(issues|merge_requests)/:iid/award_emoji`
- **Triage Actions:** State transitions (`close`, `reopen`), label edits (`add_labels`, `remove_labels`), assignee assignment, milestone linking, issue locking/unlocking, and time tracking.

### CI/CD Pipelines & Job Logs (`GitLabActionsApi`)
GitLab CI/CD pipelines map to Forgeline's Actions interface:
- **Pipelines (Workflow Runs):** `GET /projects/:id/pipelines` and `GET /projects/:id/pipelines/:id`
- **Jobs:** `GET /projects/:id/pipelines/:id/jobs`
- **Trace Logs:** `GET /projects/:id/jobs/:id/trace` parsed with `ActionsLogParser` to extract formatted ANSI colors, timestamps, and collapsible job sections.
- **Job Controls:** Cancel (`POST /projects/:id/pipelines/:id/cancel`), retry (`POST /projects/:id/pipelines/:id/retry`), and manual dispatch (`POST /projects/:id/trigger/pipeline`).

### Inbox & Notifications (`GitLabNotificationsApi`)
GitLab handles notifications through its **Todos** system:
- **Pending Todos:** `GET /api/v4/todos?state=pending`
- **Thread Mapping:** Each todo maps to a `NotificationThread` with target issue/merge request metadata, state (`open`, `closed`, `merged`), author details, and action reason (`mentioned`, `assigned`, `directly_addressed`, `approval_required`, etc.).
- **Mark as Done:** Supports instant local dismiss with `supportsDone = true`, making `POST /api/v4/todos/:id/mark_as_done` upon swipe.

### Activity Feed (`GitLabFeedApi`)
- Queries user and project events: `GET /api/v4/events`.
- Maps push events, issue/MR creations, comments, and project actions into chronological feed entries.
- Employs an in-memory project-id-to-path cache to resolve numeric project IDs to repository names efficiently.

### Search (`GitLabSearchApi`)
- Projects: `GET /api/v4/projects?search=:query`
- Issues & Merge Requests: Merges results from `GET /api/v4/issues?search=:query` and `GET /api/v4/merge_requests?search=:query`.
- Users: `GET /api/v4/users?search=:query`

---

## Deep Linking (`ForgeLinks`)

Forgeline handles GitLab URLs natively, including nested project namespaces and the GitLab `/-/` path separator:
- Repository root: `https://gitlab.com/{owner...}/{name}`
- Issues: `https://gitlab.com/{owner...}/{name}/-/issues/{id}`
- Merge requests: `https://gitlab.com/{owner...}/{name}/-/merge_requests/{id}`
- Pipelines: `https://gitlab.com/{owner...}/{name}/-/pipelines/{id}`
- Releases: `https://gitlab.com/{owner...}/{name}/-/releases/tag/{tag}`
- Tree & Files: `https://gitlab.com/{owner...}/{name}/-/tree/{branch}/{path}` and `/-/blob/{branch}/{path}`

---

## Trending

gitlab.com has no trending page any more: GitLab removed the Explore → Trending tab in GitLab 19.0. Its API has no trending order either. So the daily job that measures Codeberg's Trending ([CODEBERG.md](CODEBERG.md#trending)) measures gitlab.com's too, with the same code: one run a day, star counts compared over time, the same daily, weekly and monthly lists in the same `TrendingFile` format, published at `gitlab/trending.json` and `gitlab/state.json` on the same GitHub Pages site. The app reads `trending.json` (`forgeline.gitlabTrendingUrl` in `gradle.properties`) with one request, like Codeberg's.

Checked against gitlab.com on 2026-10-01:

| | Codeberg | gitlab.com |
|---|---|---|
| Ranking read | `/api/v1/repos/search?sort=stars`, 28 pages of 50 | `/api/v4/projects?order_by=star_count&sort=desc&simple=true`, up to 50 pages of 100 |
| Repositories tracked | top 1,400 (13 stars or more) | top 5,000 (11 stars or more) |
| Owner | `owner.login` | `namespace.full_path`, which can be nested (`group/subgroup`) |
| Language | in the ranking | not in the list: one `/projects/:id/languages` call per listed project, the largest share wins (at most 75 calls) |
| New forks (tie-breaker) | 2 pages of the newest forks | none: `/projects` can't list only forks, and about 5,000 projects are created a day |
| Anonymous limit | 2,000 requests per 10 minutes | 500 per minute (`throttle_unauthenticated_api`) |

The reading lives in `forge:gitlab` (`GitLabTrendingCrawler`), used by the daily job and by `GitLabTrendingMeter` on the phone. Recording the history and building the lists are shared with Codeberg (`forge:forgejo`'s `trending` package). As with Codeberg, measuring on the phone is offered in Settings → Trending once signed in to the forge.
