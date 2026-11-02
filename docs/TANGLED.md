# Tangled

[Tangled](https://tangled.org) is a forge built on the AT Protocol, the network Bluesky runs on. People are known by their handle (`tangled.org`, `oppi.li`), repositories live on "knots" (servers anyone can run), and what people do (a repository, a star, an issue, a profile) is a record kept on their own AT Protocol server. Forgeline reads it from Tangled's index at `api.tangled.org`, which gathers those records and answers XRPC queries about them, and writes the signed-in person's own records (a star, a follow) to their own server. The client is `forge/tangled`, as `ForgeType.TANGLED`.

## What works

### Signed in

- **Signing in:** "Tangled" on the sign-in screen, with the account's handle and an **app password**. Tangled accounts are AT Protocol accounts (a Bluesky account is one), so the app opens an AT Protocol session with the account's own server (its PDS, found from the handle's DID document: `plc.directory` for `did:plc`, `/.well-known/did.json` for `did:web`) with `com.atproto.server.createSession`. Accounts on Bluesky's own servers (`*.host.bsky.network`) sign in at its entryway, `bsky.social`. The app keeps the session's access token (a JWT, whose `sub` says whose it is and so which server to write to) and its refresh token, and renews it with `com.atproto.server.refreshSession` as it does a forge's OAuth token. The password itself isn't kept.
- **Stars:** starring writes an `org.tangled.feed.star` record (`subject: {$type: "org.tangled.feed.star#repo", identity: <repository DID>}`) to the person's server; whether a repository is starred is asked of the index (`org.tangled.feed.getStar`), and unstarring deletes the record it names. A star given moments ago that the index hasn't read yet is found among the person's own records (`com.atproto.repo.listRecords`). Stars given with Tangled's former web app (`sh.tangled.feed.star`) are found and taken back the same way.
- **Following:** an `org.tangled.graph.follow` record keyed by the DID followed (`putRecord`), as Tangled's web app writes it; `org.tangled.graph.getFollow` says whether there's one.
- **Someone's stars:** `org.tangled.feed.listStarsBy`, each repository then looked up by its DID (`org.tangled.repo.getRepoByRepoDid`) and its owner's handle read from their DID document.

These follow what Tangled's own web app writes (`web/src/lib/api/graph.ts` in `tangled.org/core`, read 2026-11-02).

### Without an account

As Tangled serves everything it holds publicly:

- **Trending:** Tangled's own trending repositories (`org.tangled.actor.getTrending`, as many as it gives), mixed into the Trending page and shown whole: Tangled already picked them, so the page's star floor doesn't apply to them (`ForgeType.curatesTrending`). Tangled doesn't say for which period, so what each gained today, this week or this month is counted from its stars' dates (`org.tangled.feed.listStars`), for the row's count and its place on the mixed page.
- **Repositories:** description, website, topics, stars and forks, languages, README, files and folders, any file, branches and tags. The handle is resolved to a DID (`com.atproto.identity.resolveHandle`, on Bluesky's public AppView, which resolves any handle), the repository found by owner and name (`org.tangled.temp.repo.getRepoByName`), and its git read by the repository's own DID (`org.tangled.temp.git.getTree`, `getBlob`, `listBranches`, `listTags`).
- **People:** a profile's description, location and links, who follows them and whom they follow (counts), and their repositories.
- **Links:** `tangled.org/<handle>`, `tangled.org/<handle>/<repo>` and a file's page (`/blob/<ref>/<path>`) open in the app; `@handle` works too.

## What doesn't, yet

| Need | Why | In Forgeline |
|---|---|---|
| Sign in through the browser | Tangled's web app signs in with AT Protocol OAuth, which asks a native app for a client metadata document served from a web address of its own (the client ID) and DPoP-bound tokens | An app password instead |
| Issues and pull requests | Tangled is moving them to "tickets" (`org.tangled.track.ticket`, one record kind for both), kept on the repository's knot and written there with a service-auth token, and comments to `org.tangled.feed.comment`; it removed its former web app on 2026-11-01. Their records have no number, which Forgeline's conversations are keyed by | The tabs are hidden (`RepoDetails.listsConversations`); their links open in the browser |
| Releases | Tangled has tags with notes, not releases | The tab is hidden (`RepoDetails.listsReleases`) |
| CI (spindles) | Pipelines are records on the knot, not in the index | No Actions tab |
| Feed, Inbox, search, watching | Not in the index | Not offered |
| Avatars and display names | Tangled has neither in its profile record | The handle is shown |

## Where it comes from

The queries were written from Tangled's lexicons (`lexicons/org/tangled` in `tangled.org/core`) on 2026-11-02. Several are still under `org.tangled.temp`, which Tangled may rename: `TangledLiveTest` (run nightly with the other live tests) reads `tangled.org/core` and Trending, and fails when one moves. The writes aren't checked live: that needs an account's app password as a repository secret.
