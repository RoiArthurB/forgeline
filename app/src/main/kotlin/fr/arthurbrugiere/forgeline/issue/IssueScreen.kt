package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.markdown.ForgelineMarkdown
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.markdown.rememberReadmeState
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueDetails
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.StateChange
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.navigation.ForgeLinks
import fr.arthurbrugiere.forgeline.navigation.IssueRoute
import fr.arthurbrugiere.forgeline.navigation.RepoRoute
import fr.arthurbrugiere.forgeline.navigation.UserRoute
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.Badge
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.LabelChip
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import java.time.Instant

@Composable
fun IssueRoute(
    route: IssueRoute,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val ref = IssueRef(RepoId(route.owner, route.name), route.number)
    val viewModel = hiltViewModel<IssueViewModel, IssueViewModel.Factory>(key = "${ref.repo.fullName}#${ref.number}") { it.create(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    IssueScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenIssue = onOpenIssue,
        onOpenUser = onOpenUser,
        onOpenInBrowser = openUrl,
        onLinkClick = { url ->
            when (val target = ForgeLinks.routeFor(url)) {
                is RepoRoute -> onOpenRepo(RepoId(target.owner, target.name))
                is IssueRoute -> onOpenIssue(IssueRef(RepoId(target.owner, target.name), target.number))
                is UserRoute -> onOpenUser(target.login)
                else -> if (!url.startsWith("#")) openUrl(url)
            }
        },
        onErrorShown = viewModel::errorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IssueScreen(
    state: IssueUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val issue = state.issue
    val isPullRequest = issue?.pullRequest != null
    val webUrl = "https://github.com/${state.ref.repo.fullName}/${if (isPullRequest) "pull" else "issues"}/${state.ref.number}"
    // Comments may link relative to the repo; HEAD resolves to the default branch on GitHub.
    val context = ReadmeContext(
        rawBaseUrl = "https://raw.githubusercontent.com/${state.ref.repo.fullName}/HEAD/",
        blobBaseUrl = "https://github.com/${state.ref.repo.fullName}/blob/HEAD/",
    )
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, issue != null) {
        if (state.error != null && issue != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // Two texts so a long title ellipsizes without hiding the number.
                        Row {
                            issue?.title?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                            Text(if (issue != null) " - #${state.ref.number}" else "#${state.ref.number}", maxLines = 1)
                        }
                        Text(
                            state.ref.repo.fullName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenInBrowser(webUrl) }) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.issue_open_on_forge))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                issue == null && state.error != null -> EmptyState(
                    icon = Icons.Outlined.CloudOff,
                    title = stringResource(R.string.issue_error_title),
                    body = stringResource(state.error.message),
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRefresh,
                )
                issue == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = onRefresh) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(key = "header") { Header(issue, nowMillis, onOpenUser) }
                        item(key = "body") {
                            CommentCard(
                                author = issue.author,
                                body = issue.body ?: stringResource(R.string.issue_no_description),
                                createdAt = issue.createdAt,
                                reactions = issue.reactions,
                                context = context,
                                nowMillis = nowMillis,
                                onOpenUser = onOpenUser,
                                onLinkClick = onLinkClick,
                            )
                        }
                        itemsIndexed(state.items, key = { index, item -> item.key(index) }) { _, item ->
                            TimelineEntry(item, context, nowMillis, onOpenUser, onOpenIssue, onLinkClick)
                        }
                        if (state.nextPage != null) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    if (state.isLoadingMore) {
                                        CircularProgressIndicator()
                                    } else {
                                        OutlinedButton(onClick = onLoadMore) { Text(stringResource(R.string.issue_load_more)) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(issue: IssueDetails, nowMillis: Long, onOpenUser: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(issue.title, style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateChip(issue)
            Text(
                stringResource(R.string.issue_opened_by, issue.author?.login ?: "ghost", relative(issue.createdAt, nowMillis)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(enabled = issue.author != null) { issue.author?.let { onOpenUser(it.login) } },
            )
        }
        issue.pullRequest?.let { pr ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.issue_pr_branches, pr.baseRef, pr.headRef),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(
                            R.string.issue_pr_stats,
                            pr.additions,
                            pr.deletions,
                            pluralStringResource(R.plurals.issue_pr_files, pr.changedFiles, pr.changedFiles),
                            pluralStringResource(R.plurals.issue_pr_commits, pr.commits, pr.commits),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (issue.labels.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                issue.labels.forEach { LabelChip(it) }
            }
        }
    }
}

@Composable
private fun StateChip(issue: IssueDetails) {
    val (label, icon) = when {
        issue.pullRequest?.isDraft == true && issue.state == IssueState.OPEN -> R.string.issue_state_draft to Icons.AutoMirrored.Outlined.CallMerge
        issue.state == IssueState.OPEN -> R.string.issue_state_open to if (issue.pullRequest != null) Icons.AutoMirrored.Outlined.CallMerge else Icons.Outlined.Adjust
        issue.state == IssueState.MERGED -> R.string.issue_state_merged to Icons.AutoMirrored.Outlined.CallMerge
        else -> R.string.issue_state_closed to Icons.Outlined.CheckCircleOutline
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Badge(stringResource(label))
    }
}

@Composable
private fun CommentCard(
    author: ForgeUser?,
    body: String,
    createdAt: Instant?,
    reactions: Map<Reaction, Int>,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable(enabled = author != null) { author?.let { onOpenUser(it.login) } },
            ) {
                Avatar(author?.avatarUrl, author?.login ?: "?", size = 28.dp)
                Text(author?.login ?: "ghost", style = MaterialTheme.typography.titleSmall)
                createdAt?.let {
                    Text(relative(it, nowMillis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Markdown(body, context, onLinkClick)
            if (reactions.isNotEmpty()) Reactions(reactions)
        }
    }
}

@Composable
private fun Markdown(body: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val parsed = rememberReadmeState(body, context, darkTheme)
    if (parsed == null) {
        Text(body, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
    } else {
        ForgelineMarkdown(parsed, onLinkClick)
    }
}

@Composable
private fun Reactions(reactions: Map<Reaction, Int>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.forEach { (reaction, count) -> Badge("${reaction.emoji} $count") }
    }
}

@Composable
private fun TimelineEntry(
    item: TimelineItem,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    val time = item.createdAt?.let { relative(it, nowMillis) }.orEmpty()
    when (item) {
        is TimelineItem.Comment -> CommentCard(item.author, item.body, item.createdAt, item.reactions, context, nowMillis, onOpenUser, onLinkClick)
        is TimelineItem.Review -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val who = item.author?.login ?: "ghost"
            val (text, icon) = when (item.state) {
                ReviewState.APPROVED -> stringResource(R.string.issue_review_approved, who) to Icons.Filled.CheckCircle
                ReviewState.CHANGES_REQUESTED -> stringResource(R.string.issue_review_changes, who) to Icons.Outlined.RateReview
                ReviewState.DISMISSED -> stringResource(R.string.issue_review_dismissed, who) to Icons.Outlined.RateReview
                ReviewState.COMMENTED -> stringResource(R.string.issue_review_commented, who) to Icons.Outlined.RateReview
            }
            EventLine(icon, "$text $time", tint = if (item.state == ReviewState.APPROVED) successColor() else null)
            item.body?.let { CommentCard(item.author, it, null, emptyMap(), context, nowMillis, onOpenUser, onLinkClick) }
        }
        is TimelineItem.StateChanged -> {
            val who = item.actor?.login ?: "ghost"
            val text = when (item.change) {
                StateChange.MERGED -> stringResource(R.string.issue_merged_event, who, time)
                StateChange.REOPENED -> stringResource(R.string.issue_reopened_event, who, time)
                StateChange.CLOSED -> when (item.stateReason) {
                    "completed" -> stringResource(R.string.issue_closed_completed_event, who, time)
                    "not_planned" -> stringResource(R.string.issue_closed_not_planned_event, who, time)
                    else -> stringResource(R.string.issue_closed_event, who, time)
                }
            }
            val icon = when (item.change) {
                StateChange.MERGED -> Icons.AutoMirrored.Outlined.CallMerge
                StateChange.REOPENED -> Icons.Outlined.Replay
                StateChange.CLOSED -> Icons.Outlined.CheckCircleOutline
            }
            EventLine(icon, text, tint = MaterialTheme.colorScheme.primary)
        }
        is TimelineItem.Labeled -> {
            val who = item.actor?.login ?: "ghost"
            // The chip goes inside the line: next to a full-width EventLine it was squeezed to nothing.
            EventLine(Icons.Outlined.Label, stringResource(if (item.added) R.string.issue_labeled_event else R.string.issue_unlabeled_event, who)) {
                LabelChip(item.label)
            }
        }
        is TimelineItem.Renamed -> EventLine(Icons.Outlined.Edit, stringResource(R.string.issue_renamed_event, item.actor?.login ?: "ghost", item.from, item.to))
        is TimelineItem.CrossReferenced -> EventLine(
            Icons.Outlined.Link,
            stringResource(R.string.issue_referenced_event, item.actor?.login ?: "ghost", item.source.number, item.sourceTitle),
            modifier = Modifier.clickable { onOpenIssue(item.source) },
        )
        is TimelineItem.Committed -> EventLine(
            Icons.Outlined.Commit,
            "${item.sha.take(7)}  ${item.message.lineSequence().first()}",
            monospace = true,
        )
    }
}

@Composable
private fun EventLine(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

@Composable
private fun successColor(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF3FB950) else Color(0xFF1A7F37)

private fun TimelineItem.key(index: Int): String = when (this) {
    is TimelineItem.Comment -> "comment-$id"
    is TimelineItem.Review -> "review-$id"
    is TimelineItem.Committed -> "commit-$sha"
    else -> "event-$index"
}

val Reaction.emoji: String
    get() = when (this) {
        Reaction.THUMBS_UP -> "👍"
        Reaction.THUMBS_DOWN -> "👎"
        Reaction.LAUGH -> "😄"
        Reaction.HOORAY -> "🎉"
        Reaction.CONFUSED -> "😕"
        Reaction.HEART -> "❤️"
        Reaction.ROCKET -> "🚀"
        Reaction.EYES -> "👀"
    }
