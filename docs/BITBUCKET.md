# Bitbucket

[Bitbucket Cloud](https://bitbucket.org) is Atlassian's forge. Forgeline reads and writes it through its REST API (`https://api.bitbucket.org/2.0`), with the client in `forge/bitbucket`, as `ForgeType.BITBUCKET`. A repository's owner is its workspace, named by its slug as in its address; a person is named by their nickname, which Bitbucket shows on their comments, and found again by the account id behind it. Bitbucket Data Center (self-hosted) has another API and isn't supported.

## Signing in

Bitbucket retired app passwords in 2026: an app signs in with an Atlassian account's **email and an API token** made at [id.atlassian.com](https://id.atlassian.com/manage-profile/security/api-tokens) ("Create API token with scopes", app Bitbucket), which Bitbucket takes as HTTP Basic. The sign-in screen asks for both, checks them against `/user`, and keeps them together as the account's credential (`bitbucketBasicCredential`). Reading takes `read:user:bitbucket`, `read:workspace:bitbucket`, `read:repository:bitbucket`, `read:pullrequest:bitbucket` and `read:pipeline:bitbucket`; writing adds `write:repository:bitbucket`, `write:pullrequest:bitbucket` and `write:pipeline:bitbucket`, and a repository's settings `admin:repository:bitbucket` (and `delete:repository:bitbucket` to delete it). A token pasted on its own (a repository's, project's or workspace's access token) is sent as a bearer token.

Browser sign-in isn't offered: Bitbucket's OAuth consumers need their secret to trade a code for a token, which an app with no server of its own can't keep, and it has no device flow.

## What works

- **Repositories:** description, README (the root's `README.md`, else another README), files and folders at any branch or tag, branches and tags, history of a repository, folder or file, and a commit's changes. Forks, watchers and open pull requests are counted from their lists.
- **Releases:** Bitbucket's are its tags, newest first, each with its source archives (`/get/<tag>.zip` and `.tar.gz`).
- **Pull requests:** listed open (`OPEN`) or closed (`MERGED`, `DECLINED`, `SUPERSEDED`), searched by words in their title and description. One reads with its branches, size, reviewers still to answer, its files as a diff, its commits and their build statuses, and its activity: comments, approvals, requests for changes, pushes, renames, merges and declines. Signed in, comment and rewrite or delete one's comments; remark on a line, a run of lines or a file, answer, rewrite, delete and resolve threads; approve or request changes; ask for reviewers among the workspace's members; mark a draft or ready; move its base; decline; open one between two branches; and merge with the repository's strategies (merge commit, squash, fast-forward and the rest), closing the branch if one likes. What stands in a merge's way is read from Bitbucket's merge checks.
- **Pipelines:** a pipeline is a run named by its build number, its steps are its jobs, and a step's log opens whole. The pipelines one can start are the branch's own and each custom one in `bitbucket-pipelines.yml`, with the variables it declares (required unless given a default, a choice when it lists `allowed-values`). Signed in, start one on a branch and stop a running one.
- **People and workspaces:** a workspace's profile and repositories, a person's profile, the workspaces one is in (as organizations), and every repository one can reach.
- **Search:** repositories by name among one's workspaces' (best match or recently updated). **Your work:** one's open pull requests, and those one is asked to review among the 30 most recently updated repositories one can reach.
- **Repository settings:** description, website, default branch and visibility; renaming and deleting. Forking into one's own workspace.
- **Links:** `bitbucket.org/workspace`, a repository, a file (`/src/<ref>/<path>`, with `#lines-12:20`), a pull request (`/pull-requests/12`), a pipeline (`/pipelines/results/12`) and a commit (`/commits/<sha>`) open in the app. A private repository's README pictures (`/raw/`) are read from the API with the account's credential.

## What doesn't

| Need | Why | In Forgeline |
|---|---|---|
| Issues and wikis | Bitbucket removed both on 2026-08-20 (the API answers 410) | No Issues tab or wiki; `#12` in a text is a pull request |
| Stars, follows | Bitbucket has neither | Not shown (`ForgeType.isSocial`); forking stays (`ForgeType.forks`) |
| Watching | Bitbucket counts watchers, but its API can't watch or stop | The count shows; no Watch button |
| Feed, Inbox | No activity feed, and no notifications an app can read | Not offered for a Bitbucket account |
| Trending | No stars, and no list of every public repository since the cross-workspace APIs went (2026-04-14) | Bitbucket isn't on the Trending page |
| Searching issues, people, topics, or another workspace's repositories | Bitbucket's search is per workspace, has no user directory and no topics | Bitbucket answers none of them |
| Re-running a pipeline, merging once checks pass, reopening a declined pull request | Not in the API | Not offered |
| Following a step's log live | Logs are read whole | A step's log is downloaded as it stands |
| Labels, milestones, assignees, reactions, locks | Bitbucket's pull requests have none | Not offered |

What is deferred is tracked in GitHub issue #59.

## Where it comes from

The models are generated by OpenAPI Generator from Bitbucket's own Swagger 2.0 document, `forge/bitbucket/spec/bitbucket.swagger.json`, downloaded from `https://api.bitbucket.org/swagger.json` (`python3 tools/specs/fetch.py bitbucket`). Before generating, the build's `prepareSpec` task fills the fields Bitbucket answers but leaves out of the document from `spec/bitbucket.additions.json` (kept by hand), widens sizes to 64 bits (a repository's size passes 2 GiB), loosens enums and formats so a new value doesn't fail a whole answer, and lifts inline objects into models of their own. The calls are written by hand on Ktor, as no maintained Kotlin or Java SDK for Bitbucket Cloud exists.

The unit tests' answers were recorded from `atlassian/atlassian-connect-express` on 2026-11-18. `BitbucketPublicLiveTest` reads it nightly without an account; `BitbucketSignedInLiveTest` runs when the `BITBUCKET_LIVE_EMAIL` and `BITBUCKET_LIVE_TOKEN` secrets are set (read scopes), and is skipped without them. `BitbucketWriteLiveTest` changes and puts back a scratch repository of one's own (`BITBUCKET_LIVE_REPO=workspace/name`, `BITBUCKET_LIVE_WRITES=1`, write scopes), and no workflow runs it.
