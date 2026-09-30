package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.model.webUrl
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
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
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.outlined.ChevronRight
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
import fr.arthurbrugiere.forgeline.ui.LabelChip
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import java.time.Instant
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Snackbar
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue

@Composable
fun IssueRoute(
    route: IssueRoute,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
) {
    val ref = route.issue
    val viewModel = hiltViewModel<IssueViewModel, IssueViewModel.Factory>(key = "${ref.repo.key}#${ref.number}") { it.create(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    IssueScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onOpenIssue = onOpenIssue,
        onOpenRepo = onOpenRepo,
        onOpenUser = onOpenUser,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, ref.repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl) },
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
    onOpenRepo: (RepoId) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val issue = state.issue
    val isPullRequest = issue?.pullRequest != null
    val webUrl = state.ref.webUrl(isPullRequest)
    // Comments may link relative to the repo; HEAD resolves to the default branch on GitHub.
    val context = ReadmeContext(
        rawBaseUrl = state.ref.repo.rawBaseUrl("HEAD"),
        blobBaseUrl = state.ref.repo.blobBaseUrl("HEAD"),
    )
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, issue != null) {
        if (state.error != null && issue != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }

    val colors = Soft.colors
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing && issue != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing && issue != null,
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars),
                    containerColor = colors.raised,
                    color = colors.accent,
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(bottom = listBottomPadding()),
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)),
            ) {
                item(key = "header") {
                    SoftHeader(
                        tint = colors.fields[issue?.tintIndex ?: 2],
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            IconButton(onClick = { onOpenInBrowser(webUrl) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.issue_open_on_forge), tint = colors.ink)
                            }
                        },
                    ) {
                        Header(state.ref, issue, nowMillis, onOpenRepo, onOpenUser)
                    }
                }
                when {
                    issue == null && state.error != null -> item(key = "error") {
                        SoftNotice(
                            stringResource(R.string.issue_error_title),
                            stringResource(state.error.message),
                            action = stringResource(R.string.retry),
                            onAction = onRefresh,
                        )
                    }
                    issue == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.issue_loading), rows = 3) }
                    else -> {
                        item(key = "body") {
                            Comment(
                                author = issue.author,
                                body = issue.body ?: stringResource(R.string.issue_no_description),
                                createdAt = issue.createdAt,
                                reactions = issue.reactions,
                                context = context,
                                nowMillis = nowMillis,
                                onOpenUser = onOpenUser,
                                onLinkClick = onLinkClick,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                        itemsIndexed(state.items, key = { index, item -> item.key(index) }) { _, item ->
                            TimelineEntry(item, context, nowMillis, onOpenUser, onOpenIssue, onLinkClick)
                        }
                        if (state.nextPage != null) {
                            item(key = "more") {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    if (state.isLoadingMore) {
                                        CircularProgressIndicator(color = colors.accent, trackColor = colors.surface)
                                    } else {
                                        SoftTonalButton(stringResource(R.string.issue_load_more), onLoadMore)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
        }
    }
}

/** Fresh while open, cool once merged, warm once closed: the header field says where the conversation stands. */
private val IssueDetails.tintIndex: Int
    get() = when (state) {
        IssueState.OPEN -> 2
        IssueState.MERGED -> 1
        else -> 0
    }

@Composable
private fun Header(ref: IssueRef, issue: IssueDetails?, nowMillis: Long, onOpenRepo: (RepoId) -> Unit, onOpenUser: (String) -> Unit) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The repository it belongs to, one tap away.
        Row(
            Modifier
                .clip(SoftTokens.Pill)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.issue_open_repo)) { onOpenRepo(ref.repo) }
                .heightIn(min = 48.dp)
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ref.repo.fullName,
                style = Soft.type.secondary,
                color = colors.inkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
        }
        if (issue == null) {
            Text("#${ref.number}", style = Soft.type.title.copy(fontSize = 26.sp, lineHeight = 31.sp), color = colors.ink)
            return@Column
        }
        // The title, then its number (the owner's requested "Title - #123").
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                buildAnnotatedString {
                    append(issue.title)
                    withStyle(SpanStyle(color = colors.inkMuted)) { append(" - #${ref.number}") }
                },
                style = Soft.type.title.copy(fontSize = 26.sp, lineHeight = 31.sp),
                color = colors.ink,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateTag(issue)
            Text(
                stringResource(R.string.issue_opened_by, issue.author?.login ?: "ghost", relative(issue.createdAt, nowMillis)),
                style = Soft.type.secondary,
                color = colors.inkMuted,
                modifier = Modifier.clip(SoftTokens.Pill).clickable(enabled = issue.author != null) { issue.author?.let { onOpenUser(it.login) } },
            )
        }
        issue.pullRequest?.let { pr ->
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).background(colors.ground).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.issue_pr_branches, pr.baseRef, pr.headRef),
                    style = Soft.type.meta.copy(fontFamily = FontFamily.Monospace),
                    color = colors.ink,
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
                    style = Soft.type.meta,
                    color = colors.inkMuted,
                )
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
private fun StateTag(issue: IssueDetails) {
    val colors = Soft.colors
    val (label, icon) = when {
        issue.pullRequest?.isDraft == true && issue.state == IssueState.OPEN -> R.string.issue_state_draft to Icons.AutoMirrored.Outlined.CallMerge
        issue.state == IssueState.OPEN -> R.string.issue_state_open to if (issue.pullRequest != null) Icons.AutoMirrored.Outlined.CallMerge else Icons.Outlined.Adjust
        issue.state == IssueState.MERGED -> R.string.issue_state_merged to Icons.AutoMirrored.Outlined.CallMerge
        else -> R.string.issue_state_closed to Icons.Outlined.CheckCircleOutline
    }
    Row(
        Modifier.clip(SoftTokens.Pill).background(colors.ground).padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink)
        Text(stringResource(label), style = Soft.type.label, color = colors.ink)
    }
}

private val TimelineGutter = 40.dp

/** On a comment body still being parsed; lets tests wait until every body is rendered. */
const val MARKDOWN_PENDING_TAG = "markdown-pending"

/** One comment as a conversation turn: the author and when, then their words; unboxed, the reading is the point. */
@Composable
private fun Comment(
    author: ForgeUser?,
    body: String,
    createdAt: Instant?,
    reactions: Map<Reaction, Int>,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    Column(modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.clip(SoftTokens.Pill).clickable(enabled = author != null) { author?.let { onOpenUser(it.login) } }.padding(end = 8.dp),
        ) {
            Avatar(author?.avatarUrl, author?.login ?: "?", size = 28.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
            Text(author?.login ?: "ghost", style = Soft.type.control, color = colors.ink)
            createdAt?.let { Text(relative(it, nowMillis), style = Soft.type.meta, color = colors.inkMuted) }
        }
        Column(Modifier.padding(start = TimelineGutter, top = 6.dp).widthIn(max = SoftTokens.MaxMeasure)) {
            Markdown(body, context, onLinkClick)
            if (reactions.isNotEmpty()) {
                Reactions(reactions, Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun Markdown(body: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val colors = Soft.colors
    val parsed = rememberReadmeState(body, context, colors.isDark)
    if (parsed == null) {
        // Styled like the rendered paragraph, so nothing jumps when parsing lands.
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.ink,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(MARKDOWN_PENDING_TAG),
        )
    } else {
        ForgelineMarkdown(parsed, onLinkClick)
    }
}

@Composable
private fun Reactions(reactions: Map<Reaction, Int>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.forEach { (reaction, count) -> SoftTag("${reaction.emoji} $count") }
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
    val colors = Soft.colors
    val time = item.createdAt?.let { relative(it, nowMillis) }.orEmpty()
    when (item) {
        is TimelineItem.Comment -> Comment(item.author, item.body, item.createdAt, item.reactions, context, nowMillis, onOpenUser, onLinkClick)
        is TimelineItem.Review -> Column {
            val who = item.author?.login ?: "ghost"
            val (text, icon) = when (item.state) {
                ReviewState.APPROVED -> stringResource(R.string.issue_review_approved, who) to Icons.Outlined.Check
                ReviewState.CHANGES_REQUESTED -> stringResource(R.string.issue_review_changes, who) to Icons.Outlined.RateReview
                ReviewState.DISMISSED -> stringResource(R.string.issue_review_dismissed, who) to Icons.Outlined.RateReview
                ReviewState.COMMENTED -> stringResource(R.string.issue_review_commented, who) to Icons.Outlined.RateReview
            }
            EventLine(icon, "$text $time", badge = if (item.state == ReviewState.APPROVED) colors.fields[2] else colors.surface)
            item.body?.let { Comment(item.author, it, null, emptyMap(), context, nowMillis, onOpenUser, onLinkClick) }
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
            val (icon, badge) = when (item.change) {
                StateChange.MERGED -> Icons.AutoMirrored.Outlined.CallMerge to colors.fields[1]
                StateChange.REOPENED -> Icons.Outlined.Replay to colors.fields[2]
                StateChange.CLOSED -> Icons.Outlined.CheckCircleOutline to colors.fields[0]
            }
            EventLine(icon, text, badge = badge)
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
            onClick = { onOpenIssue(item.source) },
        )
        is TimelineItem.Committed -> EventLine(
            Icons.Outlined.Commit,
            "${item.sha.take(7)}  ${item.message.lineSequence().first()}",
            monospace = true,
        )
    }
}

/** A small event on the conversation's timeline: a soft round badge in the avatar column, then one muted line. */
@Composable
private fun EventLine(
    icon: ImageVector,
    text: String,
    badge: Color = Soft.colors.surface,
    monospace: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .then(if (onClick != null) Modifier.softPressable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = colors.ink)
        }
        Text(
            text,
            style = if (monospace) Soft.type.meta.copy(fontFamily = FontFamily.Monospace) else Soft.type.secondary,
            color = colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        trailing?.invoke()
    }
}

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
