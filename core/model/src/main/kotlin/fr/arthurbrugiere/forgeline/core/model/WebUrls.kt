package fr.arthurbrugiere.forgeline.core.model

// Where things live on each forge's website, for "open in browser" and for resolving relative links.

/** The conversation's page: GitHub says `pull`, Forgejo `pulls`, GitLab `merge_requests` with `/-/`. */
fun IssueRef.webUrl(isPullRequest: Boolean): String = when (repo.forge.type) {
    ForgeType.GITLAB -> "${repo.webUrl}/-/${if (isPullRequest) "merge_requests" else "issues"}/$number"
    ForgeType.GITHUB -> "${repo.webUrl}/${if (isPullRequest) "pull" else "issues"}/$number"
    ForgeType.FORGEJO -> "${repo.webUrl}/${if (isPullRequest) "pulls" else "issues"}/$number"
}

/**
 * A run's page on GitHub or GitLab. Forgejo's pages number runs within the repository, not by the API's [runId], so there it's
 * the repository's Actions page: use [WorkflowRun.webUrl] once the run is known.
 */
fun RepoId.runUrl(runId: Long): String = when (forge.type) {
    ForgeType.GITHUB -> "$webUrl/actions/runs/$runId"
    ForgeType.GITLAB -> "$webUrl/-/pipelines/$runId"
    ForgeType.FORGEJO -> "$webUrl/actions"
}

/** A job's page on GitHub or GitLab; on Forgejo, as for [runUrl], the repository's Actions page. */
fun RepoId.jobUrl(runId: Long, jobId: Long): String = when (forge.type) {
    ForgeType.GITHUB -> "${runUrl(runId)}/job/$jobId"
    ForgeType.GITLAB -> "$webUrl/-/jobs/$jobId"
    ForgeType.FORGEJO -> runUrl(runId)
}

/** Where raw files at [ref] live, ending with `/`. */
fun RepoId.rawBaseUrl(ref: String): String = when (forge.type) {
    ForgeType.GITHUB -> "https://raw.githubusercontent.com/$fullName/$ref/"
    ForgeType.GITLAB -> "$webUrl/-/raw/$ref/"
    ForgeType.FORGEJO -> "$webUrl/raw/branch/$ref/"
}

/** Where file pages at [ref] live, ending with `/`. */
fun RepoId.blobBaseUrl(ref: String): String = when (forge.type) {
    ForgeType.GITHUB -> "$webUrl/blob/$ref/"
    ForgeType.GITLAB -> "$webUrl/-/blob/$ref/"
    ForgeType.FORGEJO -> "$webUrl/src/branch/$ref/"
}
