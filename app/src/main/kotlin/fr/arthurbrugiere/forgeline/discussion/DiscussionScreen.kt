package fr.arthurbrugiere.forgeline.discussion

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.DiscussionComment
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.issue.Markdown
import fr.arthurbrugiere.forgeline.navigation.DiscussionRoute
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import java.time.Instant

@Composable
fun DiscussionRoute(
    route: DiscussionRoute,
    session: SessionState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    onSignIn: () -> Unit,
) {
    val repo = route.repo
    val viewModel = hiltViewModel<DiscussionViewModel, DiscussionViewModel.Factory>(key = "${repo.key}#discussion-${route.number}") { it.create(repo, route.number) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openRelease = LocalOpenRelease.current
    val openDiscussion = LocalOpenDiscussion.current
    DiscussionScreen(
        state = state,
        signedIn = session.signedInOn(repo.forge),
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenRepo = onOpenRepo,
        onOpenUser = onOpenUser,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion) },
        onSignIn = onSignIn,
        onErrorShown = viewModel::errorShown,
    )
}

/** How far a reply is set in: it starts where the words of the comment it answers do. */
private val ReplyInset = 40.dp

/**
 * A discussion, to read: what was asked or said, then the comments in the order they came, each with the replies it
 * drew. The answer that was picked wears a tag. Writing there is the forge's for now.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiscussionScreen(
    state: DiscussionUiState,
    signedIn: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onSignIn: () -> Unit,
    onErrorShown: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberNow(state.discussion),
) {
    val colors = Soft.colors
    val discussion = state.discussion
    val summary = state.summary
    val forge = state.repo.forge.displayName
    val context = ReadmeContext(rawBaseUrl = state.repo.rawBaseUrl("HEAD"), blobBaseUrl = state.repo.blobBaseUrl("HEAD"))
    val snackbar = remember { SnackbarHostState() }
    // With the discussion on screen, a refresh that failed is only said; without it, it is the screen.
    SayOnce(stringResource(R.string.trending_refresh_failed).takeIf { state.error != null && discussion != null }, snackbar, onErrorShown)
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isLoading && discussion != null,
            onRefresh = onRefresh,
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isLoading && discussion != null,
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
                modifier = Modifier.fillMaxSize().sideSafeArea(),
            ) {
                item(key = "header") {
                    SoftHeader(
                        tint = colors.fields[1],
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            IconButton(onClick = { onOpenInBrowser(state.webUrl) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forge), tint = colors.ink)
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                state.repo.fullName,
                                style = Soft.type.secondary,
                                color = colors.inkMuted,
                                modifier = Modifier.clickable { onOpenRepo(state.repo) },
                            )
                            Text(
                                summary?.let { "${it.title} - #${it.number}" } ?: "#${state.number}",
                                style = Soft.type.detailTitle,
                                color = colors.ink,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (summary != null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    summary.category?.let { SoftTag(it, background = colors.ground) }
                                    if (summary.isAnswered == true) SoftTag(stringResource(R.string.repo_discussion_answered), background = colors.ground)
                                    if (summary.upvotes > 0) Upvotes(summary.upvotes, background = true)
                                }
                                val who = summary.author?.login ?: "ghost"
                                summary.createdAt?.let {
                                    Text(stringResource(R.string.discussion_opened, who, relative(it, nowMillis)), style = Soft.type.secondary, color = colors.inkMuted)
                                }
                            }
                        }
                    }
                }
                when {
                    discussion == null && state.error == ForgeError.Unauthorized && !signedIn -> item(key = "sign-in") {
                        SoftNotice(
                            stringResource(R.string.repo_discussions_sign_in_title),
                            stringResource(R.string.repo_discussions_sign_in_body, forge),
                            action = stringResource(R.string.sign_in),
                            onAction = onSignIn,
                            secondaryAction = stringResource(R.string.repo_open_on_forge, forge),
                            onSecondaryAction = { onOpenInBrowser(state.webUrl) },
                        )
                    }
                    discussion == null && state.error != null -> item(key = "error") {
                        val missing = state.error is ForgeError.Http && state.error.status == 404
                        SoftNotice(
                            stringResource(R.string.discussion_error_title),
                            if (missing) stringResource(R.string.discussion_missing_body) else stringResource(if (state.error == ForgeError.Network) R.string.trending_error_offline else R.string.sign_in_error_unknown),
                            action = stringResource(if (missing) R.string.repo_open_on_forge else R.string.retry, forge),
                            onAction = { if (missing) onOpenInBrowser(state.webUrl) else onRefresh() },
                        )
                    }
                    discussion == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.issue_loading), rows = 3) }
                    else -> {
                        item(key = "body") {
                            Entry(
                                discussion.summary.author, discussion.body.ifBlank { stringResource(R.string.discussion_no_text) }, createdAt = null, upvotes = 0, isAnswer = false,
                                context, nowMillis, onOpenUser, onLinkClick, Modifier.padding(top = 12.dp),
                            )
                        }
                        if (discussion.comments.isEmpty()) {
                            item(key = "none") {
                                Text(
                                    stringResource(R.string.discussion_no_comments),
                                    style = Soft.type.body,
                                    color = colors.inkMuted,
                                    modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                                )
                            }
                        }
                        discussion.comments.forEach { comment ->
                            item(key = comment.id) { Entry(comment, context, nowMillis, onOpenUser, onLinkClick) }
                            items(comment.replies, key = { it.id }) { reply -> Entry(reply, context, nowMillis, onOpenUser, onLinkClick, isReply = true) }
                        }
                        item(key = "end") {
                            Column(
                                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (discussion.isPartial) Text(stringResource(R.string.discussion_partial), style = Soft.type.secondary, color = colors.inkMuted)
                                Text(stringResource(R.string.discussion_read_only, forge), style = Soft.type.secondary, color = colors.inkMuted)
                                SoftTonalButton(stringResource(R.string.repo_open_on_forge, forge), { onOpenInBrowser(state.webUrl) })
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

@Composable
private fun Upvotes(count: Int, background: Boolean = false) {
    val said = androidx.compose.ui.res.pluralStringResource(R.plurals.discussion_upvotes, count, count)
    Box(Modifier.clearAndSetSemantics { contentDescription = said }) {
        SoftTag(stringResource(R.string.discussion_upvotes, count), background = if (background) Soft.colors.ground else Soft.colors.surface)
    }
}

@Composable
private fun Entry(
    comment: DiscussionComment,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    isReply: Boolean = false,
) = Entry(comment.author, comment.body, comment.createdAt, comment.upvotes, comment.isAnswer, context, nowMillis, onOpenUser, onLinkClick, isReply = isReply)

/** One turn of the discussion: who and when, then their words. A reply stands in from the comment it answers, along a line. */
@Composable
private fun Entry(
    author: ForgeUser?,
    body: String,
    createdAt: Instant?,
    upvotes: Int,
    isAnswer: Boolean,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isReply: Boolean = false,
) {
    val colors = Soft.colors
    val line = colors.track
    Column(
        modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(start = if (isReply) 20.dp + ReplyInset else 20.dp, end = 20.dp)
            .then(if (isReply) Modifier.drawBehind { drawLine(line, Offset(-12.dp.toPx(), 0f), Offset(-12.dp.toPx(), size.height), 2.dp.toPx()) } else Modifier)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(SoftTokens.Pill)
                    .clickable(enabled = author != null) { author?.let { onOpenUser(it.login) } }
                    .padding(end = 8.dp),
            ) {
                Avatar(author?.avatarUrl, author?.login ?: "?", size = if (isReply) 24.dp else 28.dp, placeholderColor = colors.surface, placeholderContentColor = colors.inkMuted)
                Text(author?.login ?: "ghost", style = Soft.type.control, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                createdAt?.let { Text(relative(it, nowMillis), style = Soft.type.meta, color = colors.inkMuted, maxLines = 1) }
            }
            if (isAnswer) SoftTag(stringResource(R.string.discussion_answer), background = colors.fields[2])
            if (upvotes > 0) Upvotes(upvotes)
        }
        Column(Modifier.padding(start = if (isReply) 0.dp else 40.dp, top = 6.dp).widthIn(max = SoftTokens.MaxMeasure)) {
            Markdown(body, context, onLinkClick)
        }
    }
}
