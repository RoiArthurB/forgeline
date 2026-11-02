# SourceHut

[SourceHut](https://sourcehut.org) is a suite of services rather than one forge: git.sr.ht holds repositories, todo.sr.ht tickets, lists.sr.ht mailing lists (where patches are sent and reviewed, instead of pull requests), builds.sr.ht CI, and meta.sr.ht accounts. Forgeline reads git.sr.ht through its GraphQL API (`https://git.sr.ht/query`), with the client in `forge/sourcehut`, as `ForgeType.SOURCEHUT`. People are written as SourceHut writes them, `~name`, so a repository's address in the app is its address on the web.

## Signing in

SourceHut's API answers nothing without a token: every call is a person's, even for a public repository. So SourceHut has its own choice on the sign-in screen, which asks for a personal access token made on meta.sr.ht (OAuth → Generate new token). Read access to git.sr.ht (`PROFILE`, `REPOSITORIES` and `OBJECTS`) is enough for everything Forgeline does there. Without signing in, a SourceHut repository or link opened in the app asks to sign in.

Browser sign-in isn't offered: SourceHut's OAuth 2.0 clients are registered with a secret, which an app with no server of its own can't keep, and it has no device flow. Tokens last a year at most; when one runs out, the account asks to sign in again, as any forge's does.

## What works

- **Repositories:** description, README, files and folders, any file (from its content address beyond 128 KiB), branches and tags.
- **Releases:** SourceHut's are its tags, as its refs page shows them: an annotated tag's message, and the files attached to it (signatures, tarballs). The source archive is git.sr.ht's `/archive/<tag>.tar.gz`.
- **People:** a profile's bio, location, website and repositories, private ones too for the token's owner.
- **Links:** `git.sr.ht/~name`, a repository, a file (`/tree/<ref>/item/<path>`) and a tag (`/refs/<tag>`) open in the app.

## What doesn't

| Need | Why | In Forgeline |
|---|---|---|
| Issues | Tickets are on todo.sr.ht, in trackers that aren't tied to a repository | The tab is hidden; a ticket's link opens in the browser |
| Pull requests | Patches are sent by email to lists.sr.ht | The tab is hidden |
| CI | builds.sr.ht is a service of its own | No Actions tab |
| Stars, watching, forks, followers | SourceHut has none of them | Not shown (`ForgeType.isSocial`) |
| Feed, Inbox, search | No activity feed or notifications API; search covers one's own repositories only | Not offered for a SourceHut account |
| Trending | Nothing to rank by | SourceHut isn't on the Trending page |
| Languages, topics, avatars | Not in the API | Left out |

Self-hosted SourceHut instances aren't supported yet: each service has its own host, which the sign-in screen would have to ask for.

## Where it comes from

The queries were written from git.sr.ht's schema (`api/graph/schema.graphqls`) and resolvers, read from its source on 2026-11-02. `SourceHutLiveTest` checks them nightly against `~sircmpwn/scdoc` when the `SOURCEHUT_LIVE_TOKEN` secret is set (a read-only personal access token), and is skipped without it.
