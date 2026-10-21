package fr.arthurbrugiere.forgeline.feed

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import fr.arthurbrugiere.forgeline.R
import androidx.compose.runtime.setValue
import fr.arthurbrugiere.forgeline.core.model.FeedAction
import fr.arthurbrugiere.forgeline.core.model.IssueAction
import fr.arthurbrugiere.forgeline.core.model.PullRequestAction
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue

/** A word joiner after each slash, so "owner/name" never breaks across lines. */
internal fun String.unbreakable(): String = replace("/", "/\u2060")

/** The steps a repository's name gives way by before a row takes another line: see [feedRepoLabel]. */
internal const val LAST_SQUEEZE = 2

/**
 * A repository's name in a Feed line, shortened as [squeeze] grows: whole, then a nested path without its middle
 * (`group/…/project`), then the project alone. Tapping the row still opens the repository, which says where it is.
 */
internal fun feedRepoLabel(fullName: String, squeeze: Int): String {
    val parts = fullName.split('/')
    return when {
        squeeze <= 0 -> fullName
        squeeze == 1 && parts.size > 2 -> "${parts.first()}/…/${parts.last()}"
        squeeze == 1 -> fullName
        else -> parts.last()
    }
}

/**
 * The step after [squeeze] for a line that still doesn't fit: the next one that changes the name (a step that leaves
 * the line as it was would never be laid out again), then the extra line, which is the last.
 */
internal fun nextFeedSqueeze(fullName: String, squeeze: Int): Int {
    val current = feedRepoLabel(fullName, squeeze)
    return ((squeeze + 1)..LAST_SQUEEZE).firstOrNull { feedRepoLabel(fullName, it) != current } ?: (LAST_SQUEEZE + 1)
}

@Composable
internal fun FeedItem.headline(repoLabel: String): String = rawHeadline(repoLabel).unbreakable()

@Composable
private fun FeedItem.rawHeadline(repoLabel: String): String {
    val who = when (actors.size) {
        1 -> actors[0].login
        2 -> stringResource(R.string.feed_actors_two, actors[0].login, actors[1].login)
        else -> pluralStringResource(R.plurals.feed_actors_many, actors.size - 1, actors[0].login, actors.size - 1)
    }
    val repo = repoLabel
    return when (val a = action) {
        FeedAction.Starred -> stringResource(R.string.feed_line_starred, who)
        is FeedAction.Forked -> stringResource(R.string.feed_line_forked, who, a.fork.fullName)
        is FeedAction.CreatedRepo -> stringResource(R.string.feed_line_created, who)
        FeedAction.MadePublic -> stringResource(R.string.feed_line_made_public, who)
        // A release is the project's news: the line names the project, the row leads with its logo.
        is FeedAction.Released -> stringResource(R.string.feed_line_repo_released, repo)
        is FeedAction.Announced -> stringResource(R.string.feed_line_announced, who, repo)
        is FeedAction.Issue -> stringResource(
            when (a.action) {
                IssueAction.OPENED -> R.string.feed_issue_opened
                IssueAction.CLOSED -> R.string.feed_issue_closed
                IssueAction.REOPENED -> R.string.feed_issue_reopened
            },
            who,
            repo,
        )
        is FeedAction.PullRequest -> stringResource(
            when (a.action) {
                PullRequestAction.OPENED -> R.string.feed_line_pr_opened
                PullRequestAction.MERGED -> R.string.feed_line_pr_merged
                PullRequestAction.CLOSED -> R.string.feed_line_pr_closed
                PullRequestAction.REOPENED -> R.string.feed_line_pr_reopened
            },
            who,
            repo,
        )
        is FeedAction.Commented -> stringResource(R.string.feed_line_commented, who, repo)
        is FeedAction.Reviewed -> stringResource(R.string.feed_line_reviewed, who, repo)
        is FeedAction.Pushed -> stringResource(R.string.feed_line_pushed, who, repo)
        is FeedAction.Branch -> stringResource(
            when {
                a.isTag && a.deleted -> R.string.feed_line_tag_deleted
                a.isTag -> R.string.feed_line_tag_created
                a.deleted -> R.string.feed_line_branch_deleted
                else -> R.string.feed_line_branch_created
            },
            who,
            repo,
        )
        is FeedAction.AddedMember -> stringResource(R.string.feed_member_added, who, repo, a.login)
    }
}

/** The people and repos a headline names, emphasized so the timeline can be skimmed. */
internal fun FeedItem.names(repoLabel: String): List<String> = rawNames(repoLabel).map { it.unbreakable() }

private fun FeedItem.rawNames(repoLabel: String): List<String> = buildList {
    addAll(actors.take(2).map { it.login })
    add(repoLabel)
    when (val a = action) {
        is FeedAction.Forked -> add(a.fork.fullName)
        is FeedAction.AddedMember -> add(a.login)
        else -> Unit
    }
}

internal fun String.emphasizing(names: List<String>, ink: Color): AnnotatedString = buildAnnotatedString {
    append(this@emphasizing)
    names.forEach { name ->
        val start = this@emphasizing.indexOf(name)
        if (start >= 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = ink), start, start + name.length)
    }
}
