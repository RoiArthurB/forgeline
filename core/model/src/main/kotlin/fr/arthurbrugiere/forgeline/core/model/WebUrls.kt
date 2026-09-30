package fr.arthurbrugiere.forgeline.core.model

// Where things live on each forge's website, for "open in browser" and for resolving relative links.

/** The conversation's page: GitHub says `pull`, Forgejo `pulls`. */
fun IssueRef.webUrl(isPullRequest: Boolean): String {
    val kind = when {
        !isPullRequest -> "issues"
        repo.forge.type == ForgeType.GITHUB -> "pull"
        else -> "pulls"
    }
    return "${repo.webUrl}/$kind/$number"
}

/**
 * A run's page on GitHub. Forgejo's pages number runs within the repository, not by the API's [runId], so there it's
 * the repository's Actions page: use [WorkflowRun.webUrl] once the run is known.
 */
fun RepoId.runUrl(runId: Long): String =
    if (forge.type == ForgeType.GITHUB) "$webUrl/actions/runs/$runId" else "$webUrl/actions"

/** A job's page on GitHub; on Forgejo, as for [runUrl], the repository's Actions page. */
fun RepoId.jobUrl(runId: Long, jobId: Long): String =
    if (forge.type == ForgeType.GITHUB) "${runUrl(runId)}/job/$jobId" else runUrl(runId)

/** Where raw files at [ref] live, ending with `/`. */
fun RepoId.rawBaseUrl(ref: String): String =
    if (forge.type == ForgeType.GITHUB) "https://raw.githubusercontent.com/$fullName/$ref/" else "$webUrl/raw/branch/$ref/"

/** Where file pages at [ref] live, ending with `/`. */
fun RepoId.blobBaseUrl(ref: String): String =
    if (forge.type == ForgeType.GITHUB) "$webUrl/blob/$ref/" else "$webUrl/src/branch/$ref/"
