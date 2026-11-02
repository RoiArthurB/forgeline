# Tangled

[Tangled](https://tangled.org) is a forge built on the AT Protocol, the network Bluesky runs on. People are known by their handle (`tangled.org`, `oppi.li`), repositories live on "knots" (servers anyone can run), and what people do (a repository, a star, an issue, a profile) is a record kept on their own AT Protocol server. Forgeline reads it from Tangled's index at `api.tangled.org`, which gathers those records and answers XRPC queries about them. The client is `forge/tangled`, as `ForgeType.TANGLED`.

## What works

Without an account, as Tangled serves everything it holds publicly:

- **Trending:** Tangled's own trending repositories (`org.tangled.actor.getTrending`, up to four), mixed into the Trending page. Tangled doesn't say for which period, so what each gained today, this week or this month is counted from its stars' dates (`org.tangled.feed.listStars`), and the page's star floor applies as for any forge.
- **Repositories:** description, website, topics, stars and forks, languages, README, files and folders, any file, branches and tags. The handle is resolved to a DID (`com.atproto.identity.resolveHandle`, on Bluesky's public AppView, which resolves any handle), the repository found by owner and name (`org.tangled.temp.repo.getRepoByName`), and its git read by the repository's own DID (`org.tangled.temp.git.getTree`, `getBlob`, `listBranches`, `listTags`).
- **People:** a profile's description, location and links, who follows them and whom they follow (counts), and their repositories.
- **Links:** `tangled.org/<handle>`, `tangled.org/<handle>/<repo>` and a file's page (`/blob/<ref>/<path>`) open in the app; `@handle` works too.

## What doesn't, yet

| Need | Why | In Forgeline |
|---|---|---|
| Signing in | Tangled signs in with AT Protocol OAuth (a confidential or DPoP-bound public client), which Forgeline doesn't speak yet | No Tangled choice on the sign-in screen |
| Issues and pull requests | The index lists them (`org.tangled.repo.listIssues`, `listPulls`), but its comment views don't name their author yet | The tabs are hidden (`RepoDetails.listsConversations`); their links open in the browser |
| Releases | Tangled has tags with notes, not releases | The tab is hidden (`RepoDetails.listsReleases`) |
| CI (spindles) | Pipelines are records on the knot, not in the index | No Actions tab |
| Stars, follows, Feed, Inbox, search | Need an account, or aren't in the index | Not offered |
| Avatars and display names | Tangled has neither in its profile record | The handle is shown |

## Where it comes from

The queries were written from Tangled's lexicons (`lexicons/org/tangled` in `tangled.org/core`) on 2026-11-02. Several are still under `org.tangled.temp`, which Tangled may rename: `TangledLiveTest` (run nightly with the other live tests) reads `tangled.org/core` and Trending, and fails when one moves.
