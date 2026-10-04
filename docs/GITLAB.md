# GitLab

Forgeline supports `gitlab.com` and self-hosted GitLab servers ("Other" in sign-in finds out what a server runs; see [GITLAB_OAUTH_APP.md](GITLAB_OAUTH_APP.md)). Links to a self-hosted server open in the browser, as for a self-hosted Forgejo. Users can sign in, browse projects, triage issues and merge requests, review CI/CD pipelines and job logs, receive inbox notifications via GitLab Todos, view user/group activity feeds, and follow GitLab deep links.

---

## Authentication

### 1. Browser Sign-In (OAuth 2.0 with PKCE)
For `gitlab.com`, browser sign-in uses OAuth 2.0 with PKCE (Proof Key for Code Exchange, RFC 7636) and a loopback redirect on the device:
- **Authorization Endpoint:** `https://gitlab.com/oauth/authorize`
- **Token Endpoint:** `https://gitlab.com/oauth/token`
- **Scopes:** `api read_user`
- **Client ID:** gitlab.com's is `forgeline.gitlabClientId` in `gradle.properties`; a self-hosted server's is typed at sign-in. Without one, sign-in falls back to Personal Access Tokens. Registering one: [GITLAB_OAUTH_APP.md](GITLAB_OAUTH_APP.md).

### 2. Personal Access Tokens (PAT)
Users can sign in to `gitlab.com` or a self-hosted server with a Personal Access Token created at:
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
- `IssueRef(repo, number, isPullRequest)` differentiates issues from merge requests. On GitLab the kind is part of which conversation it is (#7 and !7 are two), so everything that opens one says it: the repository's lists, the Feed, Search, the Inbox, links. A reference that doesn't say is an issue; nothing is guessed.
- **State changes:** closing, reopening and merging aren't notes: they come from `GET .../resource_state_events`, asked alongside each page of notes and placed by time.
- **System notes** are read by GitLab's wording, in English whatever the reader's language (`assigned to @x`, `unassigned @x`, `locked the discussion…`, `changed due date to …`, `removed due date …`, `added 1h of time spent…`). Label and milestone changes have their own endpoints and aren't shown yet.
- A due date and deleting are for issues only (`issueOnly`): they are never sent for a merge request.
- Signed out, gitlab.com serves an issue but not its notes (401): the timeline needs an account.
- **Notes & Discussions:** `GET|POST /projects/:id/(issues|merge_requests)/:iid/notes`
- **Reactions (Award Emoji):** `GET|POST|DELETE /projects/:id/(issues|merge_requests)/:iid/award_emoji`
- **Triage Actions:** State transitions (`close`, `reopen`), label edits (`add_labels`, `remove_labels`), assignee assignment, milestone linking, issue locking/unlocking, and time tracking.

### CI/CD Pipelines & Job Logs (`GitLabActionsApi`)
GitLab CI/CD pipelines map to Forgeline's Actions interface:
- **Pipelines (Workflow Runs):** `GET /projects/:id/pipelines` and `GET /projects/:id/pipelines/:id`
- **Jobs:** `GET /projects/:id/pipelines/:id/jobs`
- **Trace Logs:** `GET /projects/:id/jobs/:id/trace` parsed with `ActionsLogParser` to extract formatted ANSI colors, timestamps, and collapsible job sections.
- **Job Controls:** Cancel (`POST /projects/:id/pipelines/:id/cancel`), retry (`POST /projects/:id/pipelines/:id/retry`), and manual dispatch (`POST /projects/:id/pipeline`).

### Inbox & Notifications (`GitLabNotificationsApi`)
GitLab handles notifications through its **Todos** system:
- **Pending Todos:** `GET /api/v4/todos?state=pending`
- **Thread Mapping:** Each todo maps to a `NotificationThread` with target issue/merge request metadata, state (`open`, `closed`, `merged`), author details, and action reason (`mentioned`, `assigned`, `directly_addressed`, `approval_required`, etc.).
- **Mark as Done:** Supports instant local dismiss with `supportsDone = true`, making `POST /api/v4/todos/:id/mark_as_done` upon swipe.
- **Read:** a todo is pending or done, nothing in between. Marking one read tells GitLab nothing, so opening a thread never takes it off the list; it shows as unread again at the next sync.
- **Unsubscribe:** `POST /projects/:id/(issues|merge_requests)/:iid/unsubscribe`, then the todo is marked done.

### Activity Feed (`GitLabFeedApi`)
- Queries `GET /api/v4/events?scope=all`: what happened in the reader's projects. Without `scope=all` it is only what the reader did themselves.
- A comment event's `target_iid` is the note's own id (past an Int); the conversation and its kind are in `note.noteable_iid` and `note.noteable_type`. New issues and merge requests are `opened`, and so are reopened ones.
- Maps push events, issue/MR creations, comments, and project actions into chronological feed entries.
- Employs an in-memory project-id-to-path cache to resolve numeric project IDs to repository names efficiently.

### Search (`GitLabSearchApi`)
- Projects: `GET /api/v4/projects?search=:query`
- Issues & Merge Requests: merges `GET /api/v4/search?scope=issues` and `GET /api/v4/search?scope=merge_requests`. Listing every matching merge request (`/merge_requests?scope=all&search=`) times out on gitlab.com (408 after 15 s, measured 2026-10-04); the search endpoint answers in a second or two but needs an account.
- Users: `GET /api/v4/users?search=:query`

---

## Deep Linking (`ForgeLinks`)

Forgeline handles GitLab URLs natively, including nested project namespaces and the GitLab `/-/` path separator:
- Repository root: `https://gitlab.com/{owner...}/{name}`
- Issues: `https://gitlab.com/{owner...}/{name}/-/issues/{id}`, and `/-/work_items/{id}`, which is what the API now gives as an issue's page
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

---

## Tests

The clients are tested against what gitlab.com really answers: the files under `forge/gitlab/src/test/resources/gitlab/captured` were read on 2026-10-04 from a scratch project (an issue #1 and a merge request !1 sharing their number), e-mail fields removed. Shapes written by hand had let through a number that doesn't fit (`target_iid`), actions GitLab doesn't send and notes it words differently.
