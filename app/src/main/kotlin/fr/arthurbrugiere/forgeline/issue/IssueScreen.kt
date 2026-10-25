package fr.arthurbrugiere.forgeline.issue

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.ConversationAction
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.model.webUrl
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftDangerButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.navigation.IssueRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.pull.PullActions
import fr.arthurbrugiere.forgeline.pull.PullDone
import fr.arthurbrugiere.forgeline.pull.PullRequestChecks
import fr.arthurbrugiere.forgeline.pull.PullRequestDecisions
import fr.arthurbrugiere.forgeline.pull.PullRequestUiState
import fr.arthurbrugiere.forgeline.pull.PullRequestViewModel
import fr.arthurbrugiere.forgeline.pull.ReviewThreads
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.LocalUnreadMarkdown
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings
import fr.arthurbrugiere.forgeline.ui.Markdown
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.readPicture
import fr.arthurbrugiere.forgeline.ui.referenceLinks
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    /** Opens the form that changes a conversation's title and description. */
    onEditIssue: (IssueRef) -> Unit = {},
    /** Opens what a pull request changes, file by file. */
    onOpenChanges: (IssueRef) -> Unit = {},
    onOpenCommits: (IssueRef) -> Unit = {},
    /** Opens a CI run of the repository, by its id. */
    onOpenRun: (RepoId, Long) -> Unit = { _, _ -> },
    /** Opens what a check of the repository reported, by its id; its name shows meanwhile. */
    onOpenCheck: (RepoId, Long, String) -> Unit = { _, _, _ -> },
) {
    val ref = route.issue
    val viewModel = hiltViewModel<IssueViewModel, IssueViewModel.Factory>(key = "${ref.repo.key}${if (ref.isPullRequest == true) "!" else "#"}${ref.number}") { it.create(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    val openWiki = fr.arthurbrugiere.forgeline.ui.LocalOpenWiki.current
    val openRelease = fr.arthurbrugiere.forgeline.ui.LocalOpenRelease.current
    val signedIn = session.signedInOn(ref.repo.forge)
    // Who may close the conversation depends on who is signed in: signing in from here is asked about on the way back.
    LaunchedEffect(signedIn) { viewModel.checkPermissions() }
    LaunchedEffect(state.movedTo) { state.movedTo?.let(onMoved) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    val atUnread = LocalUserSettings.current.openAtUnread
    LaunchedEffect(viewModel) { if (route.unread && atUnread) viewModel.openAtUnread(route.lastReadAtMillis?.let(Instant::ofEpochMilli)) }
    val manage = remember(viewModel, openUrl) {
        ManageActions(
            onOpened = viewModel::manageOpened,
            onDoneShown = viewModel::manageDoneShown,
            onLoadLabels = viewModel::loadLabels,
            onLoadAssignable = viewModel::loadAssignable,
            onLoadMilestones = viewModel::loadMilestones,
            onSetLabels = viewModel::setLabels,
            onSetAssignees = viewModel::setAssignees,
            onSetReviewers = viewModel::setReviewers,
            onSetDraft = viewModel::setDraft,
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
            onSetDueDate = viewModel::setDueDate,
            onLoadTracking = viewModel::loadTracking,
            onToggleTimer = viewModel::toggleTimer,
            onAddTime = viewModel::addTime,
            onLoadDependencies = viewModel::loadDependencies,
            onAddDependency = viewModel::addDependency,
            onRemoveDependency = viewModel::removeDependency,
            onOpenOnForge = { openUrl(ref.webUrl(viewModel.state.value.issue?.pullRequest != null)) },
        )
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Nothing chosen, nothing said. What was chosen is read off the main thread: it can be megabytes.
        if (uri != null) scope.launch { viewModel.attach(withContext(Dispatchers.IO) { readPicture(context.contentResolver, uri) }) }
    }
    val comments = remember(viewModel, onEditIssue) {
        CommentActions(
            onQuote = viewModel::quote,
            onEdit = viewModel::startEditing,
            onCancelEdit = viewModel::cancelEditing,
            onDelete = viewModel::deleteComment,
            onDeleteErrorShown = viewModel::deleteErrorShown,
            onReact = viewModel::react,
            onReactionErrorShown = viewModel::reactionErrorShown,
            onPickPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onAttachErrorShown = viewModel::attachErrorShown,
            onReferenceTyped = viewModel.references::typed,
            // Said with its kind: GitLab numbers merge requests apart from issues.
            onEditIssue = { onEditIssue(IssueRef(ref.repo, ref.number, viewModel.state.value.issue?.pullRequest != null)) },
        )
    }
    val suggestions by viewModel.references.offer.collectAsStateWithLifecycle()
    // A pull request has more behind it than its conversation: asked for once it is known to be one.
    val pullRef = IssueRef(ref.repo, ref.number, isPullRequest = true)
    val pull = if (state.issue?.pullRequest != null) {
        hiltViewModel<PullRequestViewModel, PullRequestViewModel.Factory>(key = "pull-${ref.repo.key}-${ref.number}") { it.create(pullRef) }
    } else {
        null
    }
    val pullState = pull?.state?.collectAsStateWithLifecycle()?.value
    // Merged or reviewed from here: the conversation is read again, to show it.
    LaunchedEffect(pullState?.done) { if (pullState?.done != null) viewModel.refresh() }
    val pullActions = remember(pull, openUrl) {
        PullActions(
            onOpenChanges = { onOpenChanges(pullRef) },
            onOpenCommits = { onOpenCommits(pullRef) },
            onOpenRun = { runId -> onOpenRun(ref.repo, runId) },
            onOpenCheck = { checkId, name -> onOpenCheck(ref.repo, checkId, name) },
            onOpenUrl = openUrl,
            onMerge = { pull?.merge(it) },
            onMergeErrorShown = { pull?.mergeErrorShown() },
            onCancelMergeWhenChecksPass = { pull?.cancelMergeWhenChecksPass() },
            onUpdateBranch = { pull?.updateBranch() },
            onUpdateErrorShown = { pull?.updateErrorShown() },
            onReply = { thread, body -> pull?.reply(thread, body) },
            onSetResolved = { thread, resolved -> pull?.setResolved(thread, resolved) },
            onApplySuggestion = { thread, suggestion -> pull?.applySuggestion(thread, suggestion) },
            onDismissReview = { id, message -> pull?.dismissReview(id, message) },
            onThreadErrorShown = { pull?.threadErrorShown() },
            onReview = { verdict, body -> pull?.submitReview(verdict, body) },
            onReviewErrorShown = { pull?.reviewErrorShown() },
        )
    }
    IssueScreen(
        state = state,
        pull = pullState,
        pullActions = pullActions,
        comments = comments,
        suggestions = suggestions,
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
        onToEnd = viewModel::toEnd,
        onScrolled = viewModel::scrolled,
        onOpenIssue = onOpenIssue,
        onOpenRepo = onOpenRepo,
        onOpenUser = onOpenUser,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, ref.repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion, onOpenWiki = openWiki) },
        onErrorShown = viewModel::errorShown,
        onMarkReady = viewModel::markReady,
        onToggleSubscribed = viewModel::toggleSubscribed,
    )
}

/** What can be asked of a comment, and of the conversation's own text, beyond reading it. */
class CommentActions(
    /** Starts a reply quoting the text given. */
    val onQuote: (String) -> Unit = {},
    val onEdit: (Long) -> Unit = {},
    val onCancelEdit: () -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onDeleteErrorShown: () -> Unit = {},
    val onEditIssue: () -> Unit = {},
    /** Gives a reaction to a comment, or to the conversation's own text (null), or takes it back. */
    val onReact: (Long?, Reaction) -> Unit = { _, _ -> },
    val onReactionErrorShown: () -> Unit = {},
    /** Asks the reader for a picture to attach to the comment being written. */
    val onPickPicture: () -> Unit = {},
    val onAttachErrorShown: () -> Unit = {},
    /** Told the reference being typed at the cursor (`#12`, `#crash`), or null when there is none. */
    val onReferenceTyped: (TypedReference?) -> Unit = {},
)

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
    comments: CommentActions = CommentActions(),
    nowMillis: Long = rememberNow(state.issue, state.items),
    /** The conversations the reference being typed may mean, and whether the forge is still being asked. */
    suggestions: ReferenceOffer = ReferenceOffer(),
    /** Loads what is left of the conversation and asks, through the state, to be taken to its end. */
    onToEnd: () -> Unit = {},
    onScrolled: () -> Unit = {},
    /** What stands behind a pull request, beside its conversation; null for an issue and until it is known. */
    pull: PullRequestUiState? = null,
    pullActions: PullActions = PullActions(),
    /** Marks a draft pull request ready for review. */
    onMarkReady: () -> Unit = {},
    /** Has the reader hear of everything said here, or no longer. */
    onToggleSubscribed: () -> Unit = {},
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
        // A number alone names another conversation of the repository.
        references = state.ref.repo.referenceLinks(),
    )
    val snackbar = remember { SnackbarHostState() }
    val refreshFailed = stringResource(R.string.trending_refresh_failed)
    LaunchedEffect(state.error, issue != null) {
        if (state.error != null && issue != null) {
            snackbar.showSnackbar(refreshFailed)
            onErrorShown()
        }
    }

    SayOnce(stringResource(R.string.issue_comment_delete_failed).takeIf { state.deleteError != null }, snackbar, comments.onDeleteErrorShown)
    val attachFailed = state.attachError?.let { error ->
        stringResource(
            when {
                error is ForgeError.Http && error.status == 413 -> R.string.issue_attach_too_large
                error is ForgeError.Http && (error.status == 403 || error.status == 404) -> R.string.issue_attach_refused
                error == ForgeError.Unreadable -> R.string.issue_attach_unreadable
                else -> R.string.issue_attach_failed
            },
        )
    }
    SayOnce(attachFailed, snackbar, comments.onAttachErrorShown)
    SayOnce(stringResource(R.string.issue_reaction_failed).takeIf { state.reactionError != null }, snackbar, comments.onReactionErrorShown)
    // A merge set for later leaves nothing else on screen to say it went through: not every forge says so afterwards.
    // Nor does bringing its branch up to date: the forge may still be at it.
    val scheduled = stringResource(R.string.merge_scheduled)
    val updating = stringResource(R.string.pull_branch_updating)
    val committed = stringResource(R.string.thread_suggestion_committed)
    // The review being dismissed, until the forge took it or the reader called it off.
    var dismissing by rememberSaveable { mutableStateOf<Long?>(null) }
    // Dismissing someone's verdict is for whoever can write to the repository, where the forge can.
    val canDismiss = canComment && state.access >= RepoAccess.WRITE && pull?.dismissesReviews == true
    dismissing?.let { id -> DismissReviewSheet(isSending = pull?.busyThread == "review-$id", onDismissReview = { message -> pullActions.onDismissReview(id, message) }) { dismissing = null } }
    LaunchedEffect(pull?.done) {
        when (pull?.done?.first) {
            PullDone.DISMISSED -> dismissing = null
            PullDone.SCHEDULED -> snackbar.showSnackbar(scheduled)
            PullDone.UPDATED -> snackbar.showSnackbar(updating)
            PullDone.SUGGESTION_COMMITTED -> snackbar.showSnackbar(committed)
            else -> Unit
        }
    }
    val threadFailed = pull?.threadError?.let { error ->
        stringResource(
            when {
                // A suggestion whose lines have changed since, or a file too large for the forge to send.
                error == ForgeError.Conflict -> R.string.thread_suggestion_conflict
                error == ForgeError.Unsupported -> R.string.thread_suggestion_unsupported
                error is ForgeError.Http && (error.status == 403 || error.status == 404) -> R.string.issue_state_error_refused
                else -> R.string.issue_state_error
            },
        )
    }
    SayOnce(threadFailed, snackbar, pullActions.onThreadErrorShown)
    // A refused update has no sheet to say it in.
    SayOnce(stringResource(R.string.pull_branch_update_failed).takeIf { pull?.updateError != null }, snackbar, pullActions.onUpdateErrorShown)
    // The comment the reader asked to delete, until they have said yes or no.
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    deleting?.let { id ->
        SoftConfirmSheet(
            title = stringResource(R.string.issue_comment_delete_title),
            body = stringResource(R.string.issue_comment_delete_body),
            confirm = stringResource(R.string.issue_comment_delete),
            cancel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                deleting = null
                comments.onDelete(id)
            },
            onDismiss = { deleting = null },
        )
    }
    // A reply needs the reader's turn: signed in, and let in when the conversation is locked.
    val canWrite = canComment && !(issue?.isLocked == true && state.access < RepoAccess.WRITE)

    val postedOutOfSight = stringResource(R.string.issue_comment_posted_out_of_sight)
    LaunchedEffect(state.commentPostedOutOfSight) {
        if (state.commentPostedOutOfSight) {
            onCommentNoticeShown()
            snackbar.showSnackbar(postedOutOfSight)
        }
    }

    val colors = Soft.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // How many comments in sight still show their text unread: each changes size once its Markdown lands.
    val unread = remember { mutableIntStateOf(0) }
    // Counts the times the list was sent somewhere: a place being held gives way to the next one asked for.
    var sent by remember { mutableIntStateOf(0) }
    // An entry lands right below the status bar (and the band behind it), not under it; its own padding gives it air,
    // and nothing of the entry before it peeks in above.
    val topInset = WindowInsets.statusBars.getTop(LocalDensity.current)
    LaunchedEffect(state.scrollTo) {
        // The header and the description come first; the reader's turn comes last, after "Load more" if any.
        val (index, offset) = when (val target = state.scrollTo) {
            null -> return@LaunchedEffect
            ScrollTarget.End -> TIMELINE_START + state.items.size + (if (state.nextPage != null) 1 else 0) to 0
            is ScrollTarget.Item -> TIMELINE_START + target.index to -topInset
        }
        val mine = ++sent
        listState.scrollToItem(index, offset)
        // Comments are laid out as their Markdown is read, off the main thread: those that grow afterwards push the
        // place asked for away. It is put back each time, until every comment in sight is read and nothing has moved
        // it for a while; unless the reader takes over, by hand or by asking for another place.
        fun inPlace() = when (state.scrollTo) {
            ScrollTarget.End -> !listState.canScrollForward
            else -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset == -offset
        }
        val start = withFrameMillis { it }
        var settledSince = start
        while (true) {
            val now = withFrameMillis { it }
            if (sent != mine || listState.isScrollInProgress || now - settledSince >= SETTLE_MILLIS || now - start >= HOLD_LIMIT_MILLIS) break
            if (!inPlace()) listState.scrollToItem(index, offset) else if (unread.intValue == 0) continue
            settledSince = now
        }
        onScrolled()
    }
    CompositionLocalProvider(LocalUnreadMarkdown provides unread) {
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
                    // Room under the reader's turn for the buttons that float over the list's end.
                    contentPadding = PaddingValues(bottom = listBottomPadding(extra = 24.dp + JumpSize)),
                    // The comment box stays above the keyboard (edge-to-edge doesn't resize the window for it).
                    modifier = Modifier.fillMaxSize().sideSafeArea().imePadding(),
                ) {
                    item(key = "header") {
                        SoftHeader(
                            onBack = onBack,
                            backDescription = stringResource(R.string.navigate_up),
                            actions = {
                                if (state.canManage(signedIn = canComment)) {
                                    // Named, not a glyph to learn: what the issue's owner reaches for most.
                                    Row(
                                        Modifier
                                            .clip(SoftTokens.Pill)
                                            .background(Soft.colors.ground)
                                            .clickable(role = Role.Button, onClick = { managing = true })
                                            .heightIn(min = 40.dp)
                                            .padding(start = 10.dp, end = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Outlined.Tune, contentDescription = null, tint = Soft.colors.ink, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.manage_title), style = Soft.type.control, color = Soft.colors.ink, maxLines = 1)
                                    }
                                }
                                ShareLinkButton(webUrl, issue?.title ?: "${state.ref.repo.fullName}#${state.ref.number}")
                                SoftIconButton(onClick = { onOpenInBrowser(webUrl) }) {
                                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, state.ref.repo.forge.displayName), tint = Soft.colors.ink)
                                }
                            },
                        ) {
                            Header(state.ref, issue, nowMillis, onOpenRepo, onOpenUser, pull, pullActions)
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
                                    menu = CommentMenu(
                                        onQuote = issue.body?.takeIf { canWrite }?.let { text -> { comments.onQuote(text) } },
                                        onEdit = comments.onEditIssue.takeIf { state.canEditIssue },
                                        onDelete = null,
                                        // Reacting takes an account, not the right to comment: a locked conversation still takes them.
                                        onReact = if (canComment) ({ reaction -> comments.onReact(null, reaction) }) else null,
                                    ),
                                )
                            }
                            itemsIndexed(state.items, key = { index, item -> item.key(index) }) { _, item ->
                                // A comment being rewritten is rewritten where it stands: its words give way to the field.
                                val editor: (@Composable () -> Unit)? = if (item is TimelineItem.Comment && item.id == state.editing) {
                                    { Composer(state, canComment, onDraftChange, onSendComment, onToggleOpen, onSignIn, comments.onCancelEdit, comments.onPickPicture, inPlace = true, suggestions = suggestions, onReferenceTyped = comments.onReferenceTyped) }
                                } else null
                                TimelineEntry(item, context, nowMillis, onOpenUser, onOpenIssue, onLinkClick, editor, onDismissReview = if (canDismiss) ({ dismissing = it }) else null) { comment ->
                                    CommentMenu(
                                        onQuote = if (canWrite) ({ comments.onQuote(comment.body) }) else null,
                                        onEdit = if (canWrite && state.canEdit(comment)) ({ comments.onEdit(comment.id) }) else null,
                                        onDelete = if (state.canDelete(comment)) ({ deleting = comment.id }) else null,
                                        onReact = if (canComment) ({ reaction -> comments.onReact(comment.id, reaction) }) else null,
                                    )
                                }
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
                            // What was said on lines of its change, after the rest of what was said.
                            if (!pull?.threads.isNullOrEmpty()) {
                                item(key = "threads") {
                                    ReviewThreads(
                                        pull.threads, pull, pullActions, canReply = canWrite, context, nowMillis, onOpenUser, onLinkClick,
                                        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 24.dp),
                                        // Its branch takes a commit from whoever may push to the repository, or opened it.
                                        canCommit = canWrite && issue?.state == IssueState.OPEN &&
                                            (state.access >= RepoAccess.WRITE || (state.me != null && issue.author?.login == state.me)),
                                        branch = issue?.pullRequest?.headRef.orEmpty(),
                                    )
                                }
                            }
                            // What ran on a pull request, right above the turn where it is merged or not.
                            if (!pull?.checks.isNullOrEmpty()) {
                                item(key = "checks") {
                                    PullRequestChecks(
                                        pull.checks,
                                        pullActions,
                                        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 24.dp),
                                    )
                                }
                            }
                            // Where the next comment will appear: the conversation ends with the reader's turn.
                            // It waits while a comment is rewritten further up: one thing is written at a time.
                            item(key = "composer") {
                                if (!state.isEditingInPlace) {
                                    Composer(
                                        state, canComment, onDraftChange, onSendComment, onToggleOpen, onSignIn, comments.onCancelEdit, comments.onPickPicture,
                                        suggestions = suggestions, onReferenceTyped = comments.onReferenceTyped, onToggleSubscribed = onToggleSubscribed,
                                        decisions = {
                                            if (pull != null) {
                                                PullRequestDecisions(
                                                    issue, pull, pullActions,
                                                    onReady = onMarkReady.takeIf { ConversationAction.DRAFT in state.actions },
                                                    isMarkingReady = state.isMarkingReady,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
            SoftStatusBarScrim(scrolled)
            // A long conversation is crossed in one tap: each end is offered while the list isn't there. The end is also
            // offered while some of the conversation is still to load, which is what stands between the reader and it.
            // With the reader's turn in sight the end is as good as reached, and a button there would sit on "Comment".
            val turnInSight by remember {
                derivedStateOf { listState.layoutInfo.run { visibleItemsInfo.lastOrNull()?.index == totalItemsCount - 1 } }
            }
            val atEnd by remember { derivedStateOf { !listState.canScrollForward } }
            if (issue != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .sideSafeArea()
                        .imePadding()
                        .padding(end = 16.dp, bottom = LocalBottomBarSpace.current + 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (scrolled) {
                        JumpButton(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.issue_to_top)) {
                            sent++
                            scope.launch { listState.scrollToItem(0) }
                        }
                    }
                    if (!turnInSight || (atEnd && state.nextPage != null)) {
                        JumpButton(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.issue_to_end), busy = state.isLoadingMore, onClick = onToEnd)
                    }
                }
            }
            SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
        }
    }
}

/** Marks each comment, and the conversation's own text: what a long press, a double tap or a pull is made on. */
const val COMMENT_TAG = "comment"

/** Where the timeline starts in the list: after the header and the description. */
private const val TIMELINE_START = 2

private val JumpSize = 48.dp

/** How long nothing must have moved the list from the place it was sent to before it is left alone. */
private const val SETTLE_MILLIS = 600L

/** However restless what is around it, the list is the reader's again after this long. */
private const val HOLD_LIMIT_MILLIS = 5_000L

/** A tile floating over the conversation, above the navigation bar. */
@Composable
private fun JumpButton(icon: ImageVector, label: String, busy: Boolean = false, onClick: () -> Unit) {
    val colors = Soft.colors
    Box(
        Modifier
            .shadow(8.dp, SoftTokens.TileCorner, ambientColor = colors.ink.copy(alpha = 0.18f), spotColor = colors.ink.copy(alpha = 0.18f))
            .clip(SoftTokens.TileCorner)
            .background(colors.raised)
            .softPressable(role = Role.Button) { if (!busy) onClick() }
            .size(JumpSize)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(22.dp), color = colors.accent, trackColor = colors.surface, strokeWidth = 2.5.dp)
        } else {
            Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(26.dp))
        }
    }
}

/** A day as the reader's language writes it, like "Oct 10, 2026". */
internal fun formatDay(day: LocalDate): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/** Why a review no longer counts, asked before it is dismissed: the forge shows it with the review. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DismissReviewSheet(isSending: Boolean, onDismissReview: (String) -> Unit, onDismiss: () -> Unit) {
    var message by rememberSaveable { mutableStateOf("") }
    SoftSheet(stringResource(R.string.review_dismiss), onDismiss) {
        Text(stringResource(R.string.review_dismiss_body), style = Soft.type.secondary, color = Soft.colors.inkMuted)
        SoftTextField(value = message, onValueChange = { message = it }, placeholder = stringResource(R.string.review_dismiss_placeholder), singleLine = false, maxLines = 6, readOnly = isSending)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftTonalButton(stringResource(R.string.cancel), onDismiss)
            SoftDangerButton(stringResource(R.string.review_dismiss_confirm), onClick = { onDismissReview(message) }, enabled = message.isNotBlank() && !isSending)
        }
    }
}
