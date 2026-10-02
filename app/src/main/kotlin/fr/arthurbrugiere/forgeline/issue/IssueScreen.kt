package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.imePadding
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
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
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
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
    session: SessionState,
    onSignIn: () -> Unit,
    /** Opens the form for a new issue in a repository; a duplicate starts there. */
    onNewIssue: (RepoId) -> Unit,
    /** The issue is now somewhere else: its conversation there takes this one's place. */
    onMoved: (IssueRef) -> Unit,
) {
    val ref = route.issue
    val viewModel = hiltViewModel<IssueViewModel, IssueViewModel.Factory>(key = "${ref.repo.key}#${ref.number}") { it.create(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val signedIn = session.signedInOn(ref.repo.forge)
    // Who may close the conversation depends on who is signed in: signing in from here is asked about on the way back.
    LaunchedEffect(signedIn) { viewModel.checkPermissions() }
    LaunchedEffect(state.movedTo) { state.movedTo?.let(onMoved) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val manage = remember(viewModel) {
        ManageActions(
            onOpened = viewModel::manageOpened,
            onDoneShown = viewModel::manageDoneShown,
            onLoadLabels = viewModel::loadLabels,
            onLoadAssignable = viewModel::loadAssignable,
            onLoadMilestones = viewModel::loadMilestones,
            onSetLabels = viewModel::setLabels,
            onSetAssignees = viewModel::setAssignees,
            onSetMilestone = viewModel::setMilestone,
            onClose = viewModel::close,
            onToggleLocked = viewModel::toggleLocked,
            onTogglePinned = viewModel::togglePinned,
            onDuplicate = {
                viewModel.duplicate()
                onNewIssue(ref.repo)
            },
            onTransfer = viewModel::transfer,
            onDelete = viewModel::delete,
        )
    }
    IssueScreen(
        state = state,
        // Commenting takes an account on the conversation's own forge.
        canComment = signedIn,
        onDraftChange = viewModel::draftChanged,
        onSendComment = viewModel::sendComment,
        onToggleOpen = viewModel::toggleOpen,
        manage = manage,
        onSignIn = onSignIn,
        onCommentNoticeShown = viewModel::commentNoticeShown,
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
    canComment: Boolean,
    onDraftChange: (String) -> Unit,
    onSendComment: () -> Unit,
    onToggleOpen: () -> Unit,
    onSignIn: () -> Unit,
    onCommentNoticeShown: () -> Unit,
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
    manage: ManageActions = ManageActions(),
    nowMillis: Long = System.currentTimeMillis(),
) {
    val issue = state.issue
    var managing by rememberSaveable { mutableStateOf(false) }
    if (managing) IssueManageSheet(state, manage, onDismiss = { managing = false })
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

    val postedOutOfSight = stringResource(R.string.issue_comment_posted_out_of_sight)
    LaunchedEffect(state.commentPostedOutOfSight) {
        if (state.commentPostedOutOfSight) {
            onCommentNoticeShown()
            snackbar.showSnackbar(postedOutOfSight)
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
                // The comment box stays above the keyboard (edge-to-edge doesn't resize the window for it).
                modifier = Modifier.fillMaxSize().sideSafeArea().imePadding(),
            ) {
                item(key = "header") {
                    SoftHeader(
                        tint = colors.fields[issue?.tintIndex ?: 2],
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            if (state.canManage(signedIn = canComment)) {
                                IconButton(onClick = { managing = true }) {
                                    Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.manage_title), tint = colors.ink)
                                }
                            }
                            IconButton(onClick = { onOpenInBrowser(webUrl) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.ref.repo.forge.displayName), tint = colors.ink)
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
                        // Where the next comment will appear: the conversation ends with the reader's turn.
                        item(key = "composer") {
                            Composer(state, canComment, onDraftChange, onSendComment, onToggleOpen, onSignIn)
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

/**
 * The reader's turn, closing the conversation: a field that grows with the comment and one action. Signed out of the
 * conversation's forge, it says so and offers to sign in. A comment that wasn't sent stays written. Whoever may close
 * the conversation, or reopen it, finds that beside the comment's action, quieter.
 */
@Composable
private fun Composer(
    state: IssueUiState,
    canComment: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleOpen: () -> Unit,
    onSignIn: () -> Unit,
) {
    val colors = Soft.colors
    val forge = state.ref.repo.forge.displayName
    Column(
        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!canComment) {
            Text(stringResource(R.string.issue_comment_sign_in, forge), style = Soft.type.body, color = colors.inkMuted)
            SoftTonalButton(stringResource(R.string.sign_in), onSignIn)
            return@Column
        }
        // A locked conversation only takes comments from whoever can write to the repository.
        if (state.issue?.isLocked == true && state.access < RepoAccess.WRITE) {
            Text(stringResource(R.string.issue_comment_locked), style = Soft.type.body, color = colors.inkMuted)
            return@Column
        }
        SoftTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            placeholder = stringResource(R.string.issue_comment_placeholder),
            error = state.commentError?.let { error ->
                when {
                    error == ForgeError.Unauthorized -> stringResource(R.string.issue_comment_error_expired, forge)
                    error is ForgeError.Http && (error.status == 403 || error.status == 404) -> stringResource(R.string.issue_comment_error_refused)
                    error == ForgeError.Network -> stringResource(R.string.issue_comment_error_offline)
                    else -> stringResource(R.string.issue_comment_error)
                }
            },
            singleLine = false,
            minLines = 2,
            maxLines = 12,
            // Not to be changed while it is on its way.
            readOnly = state.isCommenting,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        // Side by side while they fit; at large text the comment's action goes under the other, still at the end.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val issue = state.issue
            // A merged pull request stays merged.
            if (state.canChangeState && issue != null && issue.state != IssueState.MERGED) {
                val isOpen = issue.state == IssueState.OPEN
                val isPullRequest = issue.pullRequest != null
                SoftTonalButton(
                    stringResource(
                        when {
                            state.isChangingState -> if (isOpen) R.string.issue_closing else R.string.issue_reopening
                            isOpen -> if (isPullRequest) R.string.issue_close_pull else R.string.issue_close
                            else -> if (isPullRequest) R.string.issue_reopen_pull else R.string.issue_reopen
                        },
                    ),
                    onToggleOpen,
                    enabled = !state.isChangingState,
                )
            }
            SoftButton(
                stringResource(if (state.isCommenting) R.string.issue_comment_sending else R.string.issue_comment_send),
                onSend,
                enabled = state.draft.isNotBlank() && !state.isCommenting,
            )
        }
        state.stateError?.let { error ->
            Text(
                when {
                    error == ForgeError.Unauthorized -> stringResource(R.string.issue_state_error_expired, forge)
                    error is ForgeError.Http && (error.status == 403 || error.status == 404) -> stringResource(R.string.issue_state_error_refused)
                    error == ForgeError.Network -> stringResource(R.string.issue_state_error_offline)
                    else -> stringResource(R.string.issue_state_error)
                },
                style = Soft.type.secondary,
                color = colors.accent,
            )
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
            Text("#${ref.number}", style = Soft.type.hero, color = colors.ink, modifier = Modifier.semantics { heading() })
            return@Column
        }
        // The title, then its number (the owner's requested "Title - #123").
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                buildAnnotatedString {
                    append(issue.title)
                    withStyle(SpanStyle(color = colors.inkMuted)) { append(" - #${ref.number}") }
                },
                style = Soft.type.hero,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
        }
        // Wraps at large text: on one row the sentence was squeezed beside the tags and clipped.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            StateTag(issue)
            if (issue.isLocked) HeaderTag(Icons.Outlined.Lock, stringResource(R.string.issue_locked))
            Text(
                stringResource(R.string.issue_opened_by, issue.author?.login ?: "ghost", relative(issue.createdAt, nowMillis)),
                style = Soft.type.secondary,
                color = colors.inkMuted,
                // Not a pill: over two lines its round ends cut into the first and last letters.
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = issue.author != null) { issue.author?.let { onOpenUser(it.login) } },
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
        // Who it is on and what it is for: one quiet line each, only when there is something to say.
        if (issue.assignees.isNotEmpty()) {
            Text(
                stringResource(R.string.issue_assigned_to, issue.assignees.joinToString(", ") { it.login }),
                style = Soft.type.secondary,
                color = colors.inkMuted,
            )
        }
        issue.milestone?.let { Text(stringResource(R.string.issue_milestone, it.title), style = Soft.type.secondary, color = colors.inkMuted) }
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
    HeaderTag(icon, stringResource(label))
}

/** A pill in the header field: a glyph and a word, for where the conversation stands. */
@Composable
private fun HeaderTag(icon: ImageVector, label: String) {
    val colors = Soft.colors
    Row(
        Modifier.clip(SoftTokens.Pill).background(colors.ground).padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = colors.ink)
        Text(label, style = Soft.type.label, color = colors.ink)
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
        is TimelineItem.Event -> {
            val who = item.actor?.login ?: "ghost"
            val subject = item.subject.orEmpty()
            val (icon, text) = when (item.event) {
                ConversationEvent.LOCKED -> Icons.Outlined.Lock to stringResource(R.string.issue_locked_event, who, time)
                ConversationEvent.UNLOCKED -> Icons.Outlined.LockOpen to stringResource(R.string.issue_unlocked_event, who, time)
                ConversationEvent.PINNED -> Icons.Outlined.PushPin to stringResource(R.string.issue_pinned_event, who, time)
                ConversationEvent.UNPINNED -> Icons.Outlined.PushPin to stringResource(R.string.issue_unpinned_event, who, time)
                ConversationEvent.ASSIGNED -> Icons.Outlined.PersonOutline to if (subject.equals(who, ignoreCase = true)) {
                    stringResource(R.string.issue_self_assigned_event, who, time)
                } else {
                    stringResource(R.string.issue_assigned_event, who, subject, time)
                }
                ConversationEvent.UNASSIGNED -> Icons.Outlined.PersonOutline to if (subject.equals(who, ignoreCase = true)) {
                    stringResource(R.string.issue_self_unassigned_event, who, time)
                } else {
                    stringResource(R.string.issue_unassigned_event, who, subject, time)
                }
                ConversationEvent.MILESTONED -> Icons.Outlined.Flag to stringResource(R.string.issue_milestoned_event, who, subject, time)
                ConversationEvent.DEMILESTONED -> Icons.Outlined.Flag to stringResource(R.string.issue_demilestoned_event, who, subject, time)
                ConversationEvent.TRANSFERRED -> Icons.Outlined.SwapHoriz to stringResource(R.string.issue_transferred_event, who, time)
                ConversationEvent.CONVERTED_TO_DISCUSSION -> Icons.Outlined.Forum to stringResource(R.string.issue_converted_event, who, time)
            }
            EventLine(icon, text)
        }
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
