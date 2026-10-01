# Codeberg (Forgejo) integration

A design note for adding Codeberg, and any Forgejo instance, next to GitHub.

**The rule: Forgeline is one app, not one app per forge.** The Feed, Trending, search and everything else are a single page each, mixing every forge. Only the Inbox may be split per forge, and only if the user asks for it in Settings.

Everything about Forgejo below was checked against its source (Forgejo v1.21 and Gitea `main`), and then against codeberg.org itself on 2026-09-29 (Forgejo `16.0.0-dev`): the public endpoints by calling them, the signed-in ones (notifications) against Codeberg's published OpenAPI description. Items marked **verify** still need a Codeberg account or OAuth application to check. `forge/forgejo`'s live contract test (`CodebergLiveContractTest`, run nightly with GitHub's) re-checks the rest every night, so a change on Codeberg's side shows up before the app depends on it.

## What Forgejo gives us, and what it doesn't

| Need | GitHub | Forgejo | Consequence |
|---|---|---|---|
| Notifications | `GET /notifications`, with `reason` | `GET /notifications?all=true`, **no `reason`** | "Needs you" has to be rebuilt (see [Inbox](#inbox)). |
| Mark done | `DELETE /notifications/threads/{id}` | **none**: only `unread`, `read`, `pinned` | Done becomes a local tombstone plus mark-read. |
| Cheap poll | `If-Modified-Since` → 304, `X-Poll-Interval` | no conditional GET, but `GET /notifications/new` → `{"new": n}` | Poll `/new`, fetch the list only when something changed. |
| Unsubscribe | `DELETE …/threads/{id}/subscription` | `DELETE /repos/{o}/{r}/issues/{n}/subscriptions/{me}` | Issues and PRs only. |
| Subject types | Issue, PullRequest, Release, Discussion, CheckSuite, Commit | `Issue`, `Pull`, `Commit`, `Repository` (+ `state`: open, closed, merged) | Maps onto `SubjectType`. |
| Feed of people you follow | `GET /users/{me}/received_events` | **none**: `GET /users/{me}/activities/feeds` only holds your own actions, repos you watch and your orgs (`NotifyWatchers` never fans out to followers) | Fan out to each followed user's feed (see [Feed](#feed)). |
| Star events | `WatchEvent` | **never emitted** (`ActionStarRepo` exists but nothing creates it) | No stars in a Codeberg Feed. |
| Trending | scraped from `github.com/trending` | **none** ([Codeberg/Community#213](https://codeberg.org/Codeberg/Community/issues/213)) | Measured once a day for everyone (see [Trending](#trending)). |
| Star history | `starred_at` via a media type | stars have `created_unix` in the database, but `/stargazers` returns users only, in no particular order | Star history can't be rebuilt from the API. |
| Sign-in | device flow, or classic PAT | **no device flow** (Codeberg's OpenID configuration lists only `authorization_code` and `refresh_token`); OAuth2 authorization code with **PKCE** (`S256`, required for public clients), or a PAT | New sign-in path (see [Sign-in](#sign-in)). |
| Token lifetime | OAuth tokens don't expire | OAuth access tokens expire after **1 hour**, refresh tokens after **730 hours** (Forgejo defaults, **verify** on Codeberg) | Accounts store a refresh token. |
| Rate limits | 5000/h, documented | **2000 requests per 10 minutes**, announced in `RateLimit-Policy: "baseline";q=2000;w=600` and `RateLimit` headers | Generous, but keep requests per refresh small and bounded; read `RateLimit` rather than guessing. |
| Page size | up to 100 | 50 (a larger `limit` is cut to 50), `Link` + `X-Total-Count` headers; activity feeds only send `X-Total-Count` | |

## Groundwork: nothing is tied to one forge

Today the app assumes one forge in three places, and all of them need to change before anything Codeberg-specific.

1. **`RepoId` has no forge.** `RepoId("alice", "tool")` on GitHub and on Codeberg are equal, so caches, Feed merging (`feedItems` keys by `RepoId`), Inbox grouping (by `owner.lowercase()`), star state and previews would silently mix them. `RepoId` gains a `forge: ForgeInstance`. The same goes for `IssueRef` (through `RepoId`), user logins (`UserRoute(login)` becomes `UserRoute(host, login)`), and every `*Route` in `navigation/Routes.kt`. Room entities keyed by `owner, name` gain a `host` column; the database is a rebuildable cache (`fallbackToDestructiveMigration`), so no migration code.
2. **One implementation per API, bound in `ForgeModule`.** Forgejo is many instances, so the APIs are looked up per forge instead: a `ForgeClients` registry returns the `RepoApi`, `NotificationsApi`, … for a `ForgeInstance`, building a Forgejo client for a host on first use. A new `forge:forgejo` module implements the `core/forge` interfaces, as `forge:github` does. `ForgeType` gains `FORGEJO`, and `ForgeInstance.Codeberg = ForgeInstance(FORGEJO, "codeberg.org")`.
3. **"The active account".** The Inbox, the Feed and the background check read `accounts.activeAccount`. They read *all* accounts instead. Screens that act on one repository (star, follow, comment) pick the account signed in to that repository's forge, and read anonymously when there isn't one.

Forge differences that the UI has to know about are capabilities on the client, not `if (forge == GITHUB)` checks: `supportsDone`, `feedHasStars`, `signIn: DeviceFlow | Pkce | TokenOnly`.

Hard-coded `https://github.com/…` URLs (in `UserScreen`, `RepoScreen`, `IssueScreen`, `InboxNotifier`) become `forge.webUrl(…)`. `ForgeLinks` learns `codeberg.org` links.

**Showing the forge.** Rows in unified lists carry a small forge mark (GitHub, Codeberg, or a generic Forgejo mark with the host for self-hosted instances), only when more than one forge is in play. With GitHub alone, nothing changes on screen.

## Inbox

**One list by default.** The `notifications` table is already keyed by `(accountId, id)` and `inbox_sync` by `accountId`, so the unified Inbox is the same query without the `WHERE accountId = …`, ordered by `updatedAtMillis`. `sync()` runs every account in parallel, each with its own poll interval and error. An account failing shows a per-forge error, and the others still update. Actions (`markRead`, `markDone`, `unsubscribe`, undo) route by the thread's `accountId`, so `InboxViewModel`'s pending-undo map is keyed by `(accountId, threadId)`, not by the bare thread id, which could collide across forges.

**Split per forge, as an option.** Settings → Inbox → "Separate Inbox per forge" (off by default). When on, the Inbox shows one pill tab per signed-in account above the existing Unread / Participating / All filters, and each tab is the same list filtered by `accountId`. It is only offered when more than one account is signed in.

**Rebuilding "Needs you" on Forgejo.** Forgejo sends no reason, but the cross-repository issue search takes the same filters GitHub's reasons express. After fetching the threads, one sync makes up to four extra calls:

```
GET /repos/issues/search?review_requested=true&state=open&limit=50
GET /repos/issues/search?mentioned=true&since=<oldest thread>&limit=50
GET /repos/issues/search?assigned=true&state=open&limit=50
GET /repos/issues/search?created=true&since=<oldest thread>&limit=50
```

Each thread whose `(repo, number)` appears in a result takes the strongest matching reason: `REVIEW_REQUESTED` > `MENTION` > `ASSIGN` > `AUTHOR`, then `SUBSCRIBED` for everything else. `needsYou` and `isParticipating` then work unchanged. One approximation: `mentioned` means "mentioned anywhere in the thread", not "mentioned by the latest comment", so an old mention keeps a thread under "Needs you". These calls only run when the thread list changed.

**Done without a done endpoint.** Swiping left on a Forgejo thread marks it read on the forge and writes a local tombstone `(accountId, threadId, updatedAtMillis)`. The Inbox hides a thread while its `updatedAt` is not newer than its tombstone, which is exactly GitHub's "gone until there's new activity". Undo deletes the tombstone. Tombstones for threads the forge no longer returns are pruned at sync.

**Polling.** `GET /notifications/new` is the cheap check. The full list (`all=true`, 50 per page, up to 3 pages like GitHub) is fetched when `new > 0`, on a forced refresh, and every Nth check to pick up threads read on the web.

**Background check.** `InboxSyncWorker` syncs every account, and `takeThreadsToNotify()` works per account (its baseline is already per account). Phone notifications keep their per-reason channels. With more than one forge signed in, each notification's subtitle names the forge.

**What it looks like.** "Needs you" first, then the rest grouped by owner. Grouping keys by `(forge, owner)`, so `alice` on GitHub and `alice` on Codeberg don't merge.

## Feed

**Sources.** A Codeberg Feed is assembled from:

1. `GET /users/{me}/activities/feeds` (repos you watch, your orgs, you), and
2. `GET /users/{login}/activities/feeds?only-performed-by=true` for each person you follow (`GET /user/following`, cached for a day).

Following 200 people would mean 200 calls, so the fan-out is bounded. Each refresh fetches at most ~20 followed users (`limit=20` each, 4 at a time), picking those not fetched for longest, and weighting people who were recently active. A full rotation takes a few refreshes, which is fine for people who post a few times a day.

The same action shows up in both sources under different ids, since Forgejo stores one row per receiver. Events are deduplicated on `(act_user_id, op_type, repo_id, ref_name, content, created)`.

**Mapping `op_type` onto `FeedAction`.**

| Forgejo | Feed |
|---|---|
| `create_repo` | `CreatedRepo`, or `Forked` when the activity's repo is a fork |
| `create_issue`, `close_issue`, `reopen_issue` | `Issue` |
| `create_pull_request`, `close_pull_request`, `reopen_pull_request`, `merge_pull_request`, `auto_merge_pull_request` | `PullRequest` |
| `comment_issue`, `comment_pull` | `Commented` |
| `approve_pull_request`, `reject_pull_request`, `pull_review_dismissed` | `Reviewed` (approved, changes requested, dismissed) |
| `commit_repo` | `Pushed` (`ref_name`) |
| `push_tag`, `delete_tag`, `delete_branch` | `Branch` |
| `publish_release` | `Released` |
| `mirror_sync_*`, `rename_repo`, `transfer_repo`, `star_repo`, `watch_repo` | not shown |

Issue and PR events carry a JSON array in `content`: `["2471","shm: clang 23 complains…"]`, the number then the title (or, for comments and reviews, the comment's text; `close_issue` sends an empty title). So unlike GitHub's pull request events, Codeberg's need no title lookup. `commit_repo` carries a JSON object with the pushed commits.

**One Feed, strictly in order.** Each forge pages back in time at its own pace: one GitHub page might reach back three days, one Codeberg page one day. Showing everything loaded would put Codeberg's day-two events in *above* GitHub's older events later, when the next Codeberg page arrives, and move rows under the reader. So the unified Feed shows events only down to a **horizon**: the newest of the sources' oldest loaded event. Older events wait in the cache until every source has loaded that far, and "load more" pages the source that is furthest behind. The Feed stays strictly chronological, and loaded rows never move. Merging identical events ("alice and 2 others starred…") keys by `RepoId`, which now includes the forge, so it never merges across forges.

## Trending

One Trending page, mixing GitHub and Codeberg. Two separate problems: getting a Codeberg list at all, and ranking two forges' lists together.

### Getting Codeberg's list: one crawl a day, for everyone

Codeberg has no trending page, no star timestamps and no star events, so "stars gained today" can only be measured by comparing star counts over time. Doing that on every phone would cost storage, battery, data, and many times the load on Codeberg. Instead, **one scheduled job measures it once a day for everyone**, within a fixed budget of **30 anonymous API requests per run**.

**Where it runs.** A GitHub Actions workflow in this repository (`.github/workflows/trending.yml`, which measures gitlab.com the same way: see [GITLAB.md](GITLAB.md#trending)), daily at 02:17 UTC, running the `tools:trending` module. It publishes two files on the repository's GitHub Pages site, under `codeberg/`:

- `state.json`: for each tracked repository (keyed by Codeberg's numeric id, so renames don't break history), its star count on each of the last 31 recorded days, plus new forks per repository per day. The first real run (2026-09-29) tracked 1,400 repositories in 84 KB for one day.
- `trending.json`: the daily, weekly and monthly lists, each already in its final order (`TrendingFile` in `forge:forgejo`, shared by the job and the app so they can't drift apart).

Each run first downloads yesterday's `state.json` from the site. Only a 404 (the very first run) starts afresh; any other failure stops the run, since publishing without it would throw away a month of history. A run that can't read the whole ranking (each page is retried twice) fails rather than publish a partial one, and the site keeps the previous day's files.

The app fetches `trending.json` (its address is `forgeline.codebergTrendingUrl` in `gradle.properties`) with one request, cached for an hour like GitHub's trending. No Codeberg request comes from the phone for Trending. The download comes from GitHub, which the app already talks to; nothing identifying is sent.

**The 30 requests.** Measured on Codeberg: about 407,000 source repositories, about 1,150 new ones a day (almost none of them starred) and about 68 new forks a day. Walking the newest repositories would take 23 pages for a single day, so the budget goes where stars are:

| Pages | Query | Why |
|---|---|---|
| 28 | `GET /repos/search?mode=source&sort=stars&order=desc&limit=50` | The top 1,400 by stars, which on 2026-09-29 was every source repository with 13 stars or more. |
| 2 | `GET /repos/search?mode=fork&sort=created&order=desc&limit=50` | The day's new forks, counted per `parent`: the tie-breaker. |

(The API's sort key is `created`; "newest" is the web UI's name for it and the API rejects it.) A run takes about three minutes at roughly six seconds a page.

Anything gaining stars quickly climbs into the top 1,400, so tracking that ranking finds it. **Stars gained in a period** (1, 7 or 30 days) are only ever exact:

- a repository that was in the tracked set at the start of the period: today's count minus that day's, clamped at zero;
- a repository that wasn't, but was created within the period: all its stars were gained within it, so `stars_count`;
- otherwise (an older repository that just crossed 13 stars) it isn't listed for that period until it has a starting point. It never shows a guess.

New forks are summed from the stored daily counts, so weekly and monthly forks don't need more pages. On launch, the lists fill in over the first 1, 7 and 30 days; after that the history is always there, for everyone.

Each list is ordered by stars gained in the period, then new forks in the period, and holds at most 25 repositories with at least 2 stars gained, so a lone star doesn't make a repository "trending". `language` comes from the forge; `languageColor` comes from the app's own table; `builtBy` is left empty, since Forgejo has nothing like it.

### Ranking GitHub and Codeberg together: share of the forge's stars

Raw counts can't be compared: GitHub's tenth repository of the day might gain 400 stars when Codeberg's first gains 30. So each repository is scored by its **share of its forge's stars in the list**:

```
score = periodStars / (sum of periodStars over that forge's list)
```

The rows still show the real numbers (total stars and "+30 today"); only the order uses the score.

Worked example, daily:

| Forge | Repository | +stars | Share |
|---|---|---|---|
| GitHub | #1 | 2400 | 2400 / 12000 = 0.20 |
| GitHub | #2 | 1200 | 0.10 |
| Codeberg | #1 | 30 | 30 / 150 = 0.20 |
| Codeberg | #2 | 12 | 0.08 |

Merged order: GitHub #1 and Codeberg #1 (tied at 0.20, GitHub wins ties), GitHub #2, Codeberg #2, and so on.

What checking the ratio turned up:

- **The forge's own order is kept.** GitHub's trending order isn't strictly by stars gained, and PRODUCT.md says a forge's ranking must be kept. So the lists aren't re-sorted by score; they are *merged* like the merge step of a merge sort: at each step, the head with the higher score goes next. GitHub's #3 can never jump above its #2.
- **Same list length on both sides.** The sum is over each forge's top 25, so a forge doesn't get bigger shares just by listing fewer repositories. A forge with fewer than 25 entries is summed over what it has. Under 10 entries its shares are scaled down in proportion (`entries / 10`): the 2-star floor alone didn't keep a handful of stars from turning into huge shares, and three projects with 3, 2 and 2 stars would have stood above a repository that gained over a thousand. Lists are that short on quiet days and while a forge's history builds up.
- **The true totals aren't available.** Neither forge publishes how many stars were given in total that day, so "share of the forge's total" is taken over the trending list, which is where nearly all of a day's attention goes anyway.
- **One standout wins.** A forge with one repository far ahead of the rest gives it a big share and puts it near the top of the merged page. That's intended: it is the story of the day on that forge.

The same merge works for any number of forges, so a self-hosted Forgejo instance could join later if someone ran the same job for it.

**Measured on the phone.** A self-hosted Forgejo server has nobody publishing its list, so Settings → Trending offers to measure it on the phone, and Codeberg (and gitlab.com, once GitLab accounts arrive) too for anyone signed in who'd rather not use the shared list. It's the same measurement as the daily job, and the same code (`forge:forgejo`'s `trending` package): once a day, a background task reads the server's repositories by stars, then its newest forks, with the account's token, and keeps a month of star counts in the app's database. Reading stops as soon as the server runs out or repositories fall under 2 stars, so a small server costs a couple of requests; at most 20 pages (1,000 repositories), 28 for Codeberg. It's off by default: nothing is read from a server unless asked. Turning it on measures at once, so the history starts that day.

**Which forges are on the page.** GitHub's Trending and the lists published for Codeberg and gitlab.com, always, since none needs an account, plus each server measured on the phone. Measuring on the phone is offered once signed in to the forge; signing out goes back to the published list. Each forge's ranking is cached and refreshed separately. A forge that can't be read keeps its cached rows, and the refresh only reports an error when no forge could be read. Rows name their forge only when the page mixes several.

## Actions

Forgejo Actions has an API close to GitHub's, checked against codeberg.org on 2026-09-30 (`forgejo/website`, Forgejo `16.0.0-dev`). The Actions tab, run pages, job logs, starting a workflow by hand and cancelling all work on Codeberg, with these differences:

| | GitHub | Forgejo | Consequence |
|---|---|---|---|
| Reading runs, jobs, logs | logs need an account | **public repositories need none**, logs included | Signed out, Codeberg logs still open. |
| Run list | `per_page` | `page` and `limit`, but **without `page` the limit is ignored**: `limit=3` answered all 4,235 runs, 56 MB | Always send `page=1&limit=20`, pinned by a unit test and the nightly live test. |
| Run ids | one id, used by the web pages too | an API `id` (7368141) and a per-repository `index_in_repo` (4235) that the web page uses | The run page links to the run's `html_url`; before the run is loaded, or for a job, "open on web" goes to the repository's Actions page rather than to a wrong run. |
| Jobs | name, times, steps | name and state only, **no steps, no times**, and no single-job call | A job is read from its run's list; Codeberg jobs show no step breakdown. The runner's log doesn't mark where `run:` steps start either, so steps can't be rebuilt from it. |
| States | `status` + `conclusion` | one `status`: `waiting`, `blocked`, `running`, `success`, `failure`, `cancelled`, `skipped` | Mapped onto GitHub's pair. |
| Workflows | `GET /actions/workflows` | **no list**: the files in `.forgejo/workflows`, else `.gitea/workflows`, else `.github/workflows` | Listed from the first of those folders that exists; dispatched by file name. |
| Logs | `##[group]` format | the same format (the runner is based on act), plus the runner's own lines | One shared parser in `core/forge`. |
| Re-run | yes | **no API** | Re-run buttons are hidden on Codeberg; cancelling works. |

A repository can switch Actions off (`has_actions: false`), and then it has no Actions tab. Older Forgejo servers list runs but answer 404 for a run's jobs (reported on a self-hosted server, 2026-09-30): the run still opens, with a note that its jobs are on the run's page.

## Search

One search across forges: GitHub's, always, and that of each forge an account is signed in to (Codeberg answers anonymous issue searches with a 500, so signed-out Codeberg search wouldn't work anyway). Every forge is searched at once, with its own token. Forges rank by relevance in their own ways, so scores can't be compared: results are interleaved by rank instead, every forge's best match, then every forge's second, GitHub first. Each forge pages on its own: the next page only asks the forges that have more. A forge that fails is left out and the others still answer; the search fails only when every forge did. Results name their forge once more than one is searched, and a person found on Codeberg opens on Codeberg.

## Sign-in

Like GitHub, Codeberg offers both, and both are shown when the OAuth client ID is set:

- **"Sign in with Codeberg":** OAuth2 authorization code with PKCE, through a public OAuth application registered on Codeberg, redirecting to a loopback address on the phone (`http://127.0.0.1:<port>/oauth/codeberg`; Codeberg rejects custom schemes, checked 2026-09-29). See [CODEBERG_OAUTH_APP.md](CODEBERG_OAUTH_APP.md) for creating it. Access tokens expire after an hour, so the account stores the refresh token (encrypted with the same Keystore cipher) and refreshes on a 401 or when expired.
- **Access token:** a Forgejo access token created by the user, which is also the only option for self-hosted Forgejo instances (after entering the host), since an OAuth app can't be registered on every instance in advance. Scopes: `read:notification`, `write:notification`, `read:user`, `write:user` (follow, star), `read:repository`, `write:repository` (start and cancel workflow runs), `read:issue`, `write:issue` (comment) and `read:organization`, on **all** repositories (public, private and limited), not public only.

An account is identified by `forge:host:login`, as `Account.idFor` already does, so a GitHub and a Codeberg account with the same login are different accounts.

## Suggested order

1. Groundwork: `RepoId`, routes and entities carry the forge; the Inbox and the Feed read every account (still GitHub-only). No visible change; the existing screenshot tests keep it honest.
2. `forge:forgejo` read-only: repositories, READMEs, issues, profiles, search, anonymous browsing of codeberg.org links.
3. Codeberg sign-in (access token, then OAuth with PKCE).
4. Unified Inbox (reasons, tombstones, `/new` polling, background check, the "separate per forge" setting).
5. Unified Feed (fan-out, deduplication, horizon merge).
6. Codeberg Trending: the daily job, then the unified page with the share-of-stars merge.
7. Unified search: each forge's results, merged by rank.

Each step ships by itself, and the Forgejo module is reused for self-hosted instances. All seven shipped on 2026-09-30, followed by Codeberg's Actions (see [Actions](#actions)).
