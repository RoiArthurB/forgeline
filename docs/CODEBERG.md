# Codeberg (Forgejo) integration

A design note for adding Codeberg, and any Forgejo instance, next to GitHub: one Inbox and one Feed for every signed-in forge, and a Trending page for Codeberg, which has none of its own.

Everything about Forgejo below was checked against its source (Forgejo v1.21 and Gitea `main`) and the Forgejo Go SDK v3 (March 2026), not against the live Codeberg API. Items marked **verify** still need a check against codeberg.org itself.

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
| Trending | scraped from `github.com/trending` | **none** ([Codeberg/Community#213](https://codeberg.org/Codeberg/Community/issues/213)) | Computed on the phone (see [Trending](#trending)). |
| Star history | `starred_at` via a media type | stars have `created_unix` in the database, but `/stargazers` returns users only, **unordered** | Star history can't be rebuilt from the API. |
| Sign-in | device flow, or classic PAT | **no device flow**; OAuth2 authorization code with **PKCE** (required for public clients), or a PAT | New sign-in path. |
| Rate limits | 5000/h, documented | fair use, no published numbers | Keep requests per refresh small and bounded. |
| Page size | up to 100 | 50 (`MAX_RESPONSE_ITEMS` default), `Link` + `X-Total-Count` headers | |

A PAT for Forgejo needs `read:notification`, `write:notification`, `read:user`, `write:user` (follow and star), `read:repository`, `read:issue` and `read:organization`; add `write:issue` once writing issues and reacting land.

## Groundwork: nothing is tied to one forge

Today the app assumes one forge in three places, and all of them need to change before anything Codeberg-specific.

1. **`RepoId` has no forge.** `RepoId("alice", "tool")` on GitHub and on Codeberg are equal, so caches, Feed merging (`feedItems` keys by `RepoId`), Inbox grouping (by `owner.lowercase()`), star state and previews would silently mix them. `RepoId` gains a `forge: ForgeInstance` (203 references in 46 files). The same goes for `IssueRef` (through `RepoId`), user logins (`UserRoute(login)` becomes `UserRoute(host, login)`), and every `*Route` in `navigation/Routes.kt`. Room entities keyed by `owner, name` gain a `host` column; the database is a rebuildable cache (`fallbackToDestructiveMigration`), so no migration code.
2. **One implementation per API, bound in `ForgeModule`.** Forgejo is many instances, so the APIs are looked up per forge instead: a `ForgeClients` registry returns the `RepoApi`, `NotificationsApi`, … for a `ForgeInstance`, building a Forgejo client for a host on first use. A new `forge:forgejo` module implements the `core/forge` interfaces, as `forge:github` does. `ForgeType` gains `FORGEJO`, and `ForgeInstance.Codeberg = ForgeInstance(FORGEJO, "codeberg.org")`.
3. **"The active account".** The Inbox, the Feed and the background check read `accounts.activeAccount`. They read *all* accounts instead. Screens that act on one repository (star, follow, comment) pick the account signed in to that repository's forge, and read anonymously when there isn't one.

Forge differences that the UI has to know about are capabilities on the client, not `if (forge == GITHUB)` checks: `supportsDone`, `feedHasStars`, `trending: TrendingSource` (scraped or computed), `signIn: DeviceFlow | Pkce | TokenOnly`.

Hard-coded `https://github.com/…` URLs (in `UserScreen`, `RepoScreen`, `IssueScreen`, `InboxNotifier`) become `forge.webUrl(…)`. `ForgeLinks` learns `codeberg.org` links.

## Inbox

**One list, several accounts.** The `notifications` table is already keyed by `(accountId, id)` and `inbox_sync` by `accountId`, so the merged Inbox is the same query without the `WHERE accountId = …`, ordered by `updatedAtMillis`. `sync()` runs every account in parallel, each with its own poll interval and error. An account failing shows a per-forge error, and the others still update. Actions (`markRead`, `markDone`, `unsubscribe`, undo) route by the thread's `accountId`, so `InboxViewModel`'s pending-undo map is keyed by `(accountId, threadId)`, not by the bare thread id, which could collide across forges.

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

**What it looks like.** The Inbox stays one list: "Needs you" first, then the rest grouped by owner. Grouping keys by `(forge, owner)`, so `alice` on GitHub and `alice` on Codeberg don't merge. When more than one forge is signed in, a group header carries a small forge mark; with one forge, nothing changes.

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

Issue and PR events carry `"<number>|…"` in `content`, not the title. Titles come through `FeedPreviewRepository`, which already does this for GitHub PR titles and gains issue titles.

**Merging GitHub and Codeberg in strict order.** Each forge pages back in time at its own pace: one GitHub page might reach back three days, one Codeberg page one day. Showing everything loaded would put Codeberg's day-two events in *above* GitHub's older events later, when the next Codeberg page arrives, and move rows under the reader. So the merged Feed shows events only down to a **horizon**: the newest of the sources' oldest loaded event. Older events wait in the cache until every source has loaded that far, and "load more" pages the source that is furthest behind. The Feed stays strictly chronological, and loaded rows never move. Merging identical events ("alice and 2 others starred…") keys by `RepoId`, which now includes the forge, so it never merges across forges.

## Trending

Codeberg has no trending page, no star timestamps, and no star events. "Stars gained this week" can't be read from the forge, so it is **measured on the phone**, from three signals.

1. **New repositories: exact from the first run.** A repository created within the period gained all its stars within it. Walk `GET /repos/search?mode=source&sort=newest&order=desc&limit=50` until `created_at` is older than the period, and take `stars_count` as-is.
2. **Forks: exact from the first run.** Walk `GET /repos/search?mode=fork&sort=newest&order=desc&limit=50` the same way and count new forks per `parent`. Recent forks are a cold-start signal for established repositories.
3. **Established repositories: star deltas, which build up over time.** Keep a candidate set: the top ~500 source repositories by stars (`sort=stars`, 10 pages), repositories recently updated (`sort=updated`, a few pages), and everything signals 1 and 2 turned up. Snapshot their `stars_count` on each refresh, keyed by the numeric repository id so renames don't break the history. Stars gained in the period = today's count minus the snapshot closest to the start of the period, clamped at zero.

**Ranking.** Repositories are ranked by stars gained in the period, with new forks in the period as the tie-breaker. "12 stars this week" is only shown when it's exact (signal 1, or a snapshot that covers the whole period). Otherwise the row says what was measured ("12 stars since Tuesday"). On the first day, weekly and monthly are mostly signal 1, and they fill in by themselves.

**Keeping history without opening the app.** A daily browse would give daily Trending one snapshot a day, which is too few. A periodic WorkManager job (unmetered, battery not low, every 6 hours) takes snapshots in the background. Snapshots are thinned (hourly for two days, then daily) and dropped after 35 days, so the table stays around a few thousand rows.

**Cost.** A refresh costs about 10 (top by stars) + 3 (recently updated) + the newest-repository and newest-fork pages, bounded at 10 pages each. That's 20–35 requests, at most once an hour, and only on unmetered networks in the background. **verify**: how many source repositories and forks Codeberg gets per day and per month, which decides whether the page caps cover a whole month. If they don't, monthly Trending lists what the caps reached and says so.

**Honesty.** PRODUCT.md says the app shows "the forge's data as the forge ranks and orders it". Codeberg doesn't rank at all, so this ranking is Forgeline's. The Codeberg tab has to say so plainly ("Measured on this phone from Codeberg's public data"), and Trending stays per forge (a GitHub | Codeberg switch), because the two rankings aren't comparable enough to merge.

**Alternative: a shared daily snapshot.** A scheduled job (for example a GitHub Action in this repository) could crawl once a day and publish a static JSON file. Every phone would get full history from day one, and Codeberg would see one crawl instead of one per phone. The catch is that it's a server-side component, even if static and open, which runs against "no backend".

## Sign-in

- **codeberg.org:** OAuth2 authorization code with PKCE, through a public OAuth application registered on Codeberg by the maintainer, redirecting to a custom scheme (`fr.arthurbrugiere.forgeline:/oauth`) opened in a Custom Tab. **verify**: that Codeberg accepts a custom-scheme redirect URI for a public client (Forgejo's code requires PKCE for them and doesn't restrict the scheme).
- **Other Forgejo instances:** a PAT with the scopes above, after the user enters the host. An OAuth app can't be registered on every instance in advance.
- An account is identified by `forge:host:login`, as `Account.idFor` already does.

## Suggested order

1. Groundwork: `RepoId`/routes/entities carry the forge, a `ForgeClients` registry, all-accounts Inbox and Feed (still GitHub-only). No visible change; the existing screenshot tests keep it honest.
2. `forge:forgejo` read-only: repositories, READMEs, issues, profiles, search, anonymous browsing of codeberg.org links.
3. Codeberg sign-in (PAT first, then PKCE).
4. Merged Inbox (reasons, tombstones, `/new` polling, background check).
5. Merged Feed (fan-out, deduplication, horizon merge).
6. Codeberg Trending (signals 1–2 first, then snapshots and the background job).

Each step ships by itself, and the Forgejo module is reused for self-hosted instances.
