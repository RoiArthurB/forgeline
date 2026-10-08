package fr.arthurbrugiere.forgeline.issue

import fr.arthurbrugiere.forgeline.ui.staysAboveKeyboard
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.ui.SayOnce
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import fr.arthurbrugiere.forgeline.ui.readPicture
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.outlined.Image
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.outlined.MoreHoriz
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.imePadding
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Timer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
import androidx.compose.material.icons.automirrored.outlined.Label
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
    /** Opens the form that changes a conversation's title and description. */
    onEditIssue: (IssueRef) -> Unit = {},
) {
    val ref = route.issue
    val viewModel = hiltViewModel<IssueViewModel, IssueViewModel.Factory>(key = "${ref.repo.key}${if (ref.isPullRequest == true) "!" else "#"}${ref.number}") { it.create(ref) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openUrl = rememberCustomTabOpener()
    val openDiscussion = fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion.current
    val openRelease = fr.arthurbrugiere.forgeline.ui.LocalOpenRelease.current
    val signedIn = session.signedInOn(ref.repo.forge)
    // Who may close the conversation depends on who is signed in: signing in from here is asked about on the way back.
    LaunchedEffect(signedIn) { viewModel.checkPermissions() }
    LaunchedEffect(state.movedTo) { state.movedTo?.let(onMoved) }
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    LaunchedEffect(viewModel) { if (route.unread) viewModel.openAtUnread(route.lastReadAtMillis?.let(Instant::ofEpochMilli)) }
    val manage = remember(viewModel, openUrl) {
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
    IssueScreen(
        state = state,
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
        onLinkClick = { url -> openForgeLink(url, ref.repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion) },
        onErrorShown = viewModel::errorShown,
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

/** What one comment's menu offers; a null entry is not offered. [onReact] also makes the reactions shown tappable. */
private class CommentMenu(val onQuote: (() -> Unit)?, val onEdit: (() -> Unit)?, val onDelete: (() -> Unit)?, val onReact: ((Reaction) -> Unit)? = null) {
    val isEmpty: Boolean get() = onQuote == null && onEdit == null && onDelete == null && onReact == null
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
    comments: CommentActions = CommentActions(),
    nowMillis: Long = rememberNow(state.issue, state.items),
    /** The conversations the reference being typed may mean, and whether the forge is still being asked. */
    suggestions: ReferenceOffer = ReferenceOffer(),
    /** Loads what is left of the conversation and asks, through the state, to be taken to its end. */
    onToEnd: () -> Unit = {},
    onScrolled: () -> Unit = {},
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
    // The comment the reader asked to delete, until they have said yes or no.
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.issue_comment_delete_title)) },
            text = { Text(stringResource(R.string.issue_comment_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    comments.onDelete(id)
                }) { Text(stringResource(R.string.issue_comment_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
            shape = RoundedCornerShape(28.dp),
            containerColor = Soft.colors.raised,
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
    // An entry lands below the status bar, not under it.
    val topInset = WindowInsets.statusBars.getTop(LocalDensity.current) + with(LocalDensity.current) { 8.dp.roundToPx() }
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
                            TimelineEntry(item, context, nowMillis, onOpenUser, onOpenIssue, onLinkClick, editor) { comment ->
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
                        // Where the next comment will appear: the conversation ends with the reader's turn.
                        // It waits while a comment is rewritten further up: one thing is written at a time.
                        item(key = "composer") {
                            if (!state.isEditingInPlace) {
                                Composer(
                                    state, canComment, onDraftChange, onSendComment, onToggleOpen, onSignIn, comments.onCancelEdit, comments.onPickPicture,
                                    suggestions = suggestions, onReferenceTyped = comments.onReferenceTyped,
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
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current)) { data ->
            Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground)
        }
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

/** A round button floating over the conversation, like the navigation bar it sits above. */
@Composable
private fun JumpButton(icon: ImageVector, label: String, busy: Boolean = false, onClick: () -> Unit) {
    val colors = Soft.colors
    Box(
        Modifier
            .shadow(8.dp, CircleShape, ambientColor = colors.ink.copy(alpha = 0.18f), spotColor = colors.ink.copy(alpha = 0.18f))
            .clip(CircleShape)
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
    onCancelEdit: () -> Unit = {},
    onPickPicture: () -> Unit = {},
    /** Set where the comment being rewritten stands, which gives it its room; the keyboard comes up for it. */
    inPlace: Boolean = false,
    suggestions: ReferenceOffer = ReferenceOffer(),
    onReferenceTyped: (TypedReference?) -> Unit = {},
) {
    val colors = Soft.colors
    val forge = state.ref.repo.forge.displayName
    val isEditing = state.editing != null
    val focus = remember { FocusRequester() }
    // Nothing to focus while the rewrite is previewed: the field is not there.
    if (inPlace) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        (if (inPlace) Modifier.fillMaxWidth() else Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp))
            .staysAboveKeyboard(),
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
        if (isEditing) {
            Text(stringResource(R.string.issue_comment_editing), style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.semantics { heading() })
        }
        // The cursor is the field's own. Text put there by something other than typing (a quote, a comment to
        // rewrite) leaves it at the end, where the reader goes on writing.
        var field by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
        LaunchedEffect(state.draftPlaced) {
            if (field.text != state.draft) field = TextFieldValue(state.draft, TextRange(state.draft.length))
        }
        val marks = state.ref.repo.referenceMarks
        // What is written, as it will read once sent. Nothing written, nothing to preview: the field is back.
        var previewing by rememberSaveable { mutableStateOf(false) }
        val showPreview = previewing && state.draft.isNotBlank()
        if (showPreview) {
            WritingPreview(state.draft, state.ref.repo)
            state.commentError?.let { error ->
                Text(
                    commentErrorText(error, forge),
                    style = Soft.type.secondary,
                    color = colors.accent,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        } else SoftTextField(
            value = if (field.text == state.draft) field else field.copy(text = state.draft),
            onValueChange = { written ->
                val changed = written.text != state.draft
                field = written
                if (changed) onDraftChange(written.text)
                onReferenceTyped(written.typedReference(marks))
            },
            placeholder = stringResource(R.string.issue_comment_placeholder),
            error = state.commentError?.let { error ->
                commentErrorText(error, forge)
            },
            singleLine = false,
            minLines = 2,
            maxLines = 12,
            // Not to be changed while it is on its way.
            readOnly = state.isCommenting,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            // On the rounded ground a rewrite sits on, the field is the lighter of the two.
            background = if (inPlace) colors.raised else colors.surface,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        // The conversations the reference being typed may mean, right under where it is typed.
        if (!showPreview) {
            ReferenceSuggestionList(suggestions) { picked ->
                val typed = field.typedReference(marks) ?: return@ReferenceSuggestionList
                field = field.withReference(typed, picked.number)
                onDraftChange(field.text)
                onReferenceTyped(null)
            }
        }
        // Side by side while they fit; at large text the comment's action goes under the other, still at the end.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val issue = state.issue
            if (state.canAttach) {
                WritingAction(
                    Icons.Outlined.Image,
                    stringResource(if (state.isAttaching) R.string.issue_attaching else R.string.issue_attach),
                    busy = state.isAttaching,
                    onClick = onPickPicture,
                )
            }
            if (state.draft.isNotBlank()) PreviewToggle(showPreview) { previewing = !showPreview }
            if (isEditing) SoftTonalButton(stringResource(R.string.cancel), onCancelEdit, enabled = !state.isCommenting)
            // A merged pull request stays merged. Not offered beside a comment being rewritten: one thing at a time.
            if (!isEditing && state.canChangeState && issue != null && issue.state != IssueState.MERGED) {
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
                stringResource(
                    when {
                        isEditing -> if (state.isCommenting) R.string.issue_comment_saving else R.string.issue_comment_save
                        state.isCommenting -> R.string.issue_comment_sending
                        else -> R.string.issue_comment_send
                    },
                ),
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

/** Why a comment wasn't sent, in words that say what to do next. */
@Composable
private fun commentErrorText(error: ForgeError, forge: String): String = when {
    error == ForgeError.Unauthorized -> stringResource(R.string.issue_comment_error_expired, forge)
    error is ForgeError.Http && (error.status == 403 || error.status == 404) -> stringResource(R.string.issue_comment_error_refused)
    error == ForgeError.Network -> stringResource(R.string.issue_comment_error_offline)
    else -> stringResource(R.string.issue_comment_error)
}

/** Markdown being written, as it will read once the forge has it: on the field's own ground, in the field's place. */
@Composable
internal fun WritingPreview(text: String, repo: RepoId, modifier: Modifier = Modifier) {
    // Links and pictures relative to the repository resolve as they will in the conversation.
    val context = remember(repo) { ReadmeContext(rawBaseUrl = repo.rawBaseUrl("HEAD"), blobBaseUrl = repo.blobBaseUrl("HEAD"), references = repo.referenceLinks()) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Soft.colors.surface)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag(WRITING_PREVIEW_TAG),
    ) {
        // A preview is read, not followed: its links go nowhere.
        Markdown(text, context, onLinkClick = {})
    }
}

const val WRITING_PREVIEW_TAG = "writing-preview"

/** Goes from writing to reading what was written, and back. */
/** The reference the cursor stands at the end of; none while text is selected. */
internal fun TextFieldValue.typedReference(marks: String): TypedReference? =
    if (selection.collapsed) typedReference(text, selection.end, marks) else null

const val REFERENCE_SUGGESTIONS_TAG = "reference-suggestions"

/**
 * The conversations a reference being typed may mean, each by its number and title: tapping one writes its number.
 * Nothing is shown when there is nothing to suggest.
 */
@Composable
internal fun ReferenceSuggestionList(offer: ReferenceOffer, onPick: (IssueSummary) -> Unit) {
    if (offer.suggestions.isEmpty() && !offer.isLoading) return
    val colors = Soft.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(colors.surface).testTag(REFERENCE_SUGGESTIONS_TAG)) {
        // There from the first letter: a far forge takes its time, and says so here meanwhile.
        if (offer.isLoading) {
            val looking = stringResource(R.string.reference_looking)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = looking },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), color = colors.accent, trackColor = colors.track, strokeWidth = 2.dp)
                Text(looking, style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        offer.suggestions.forEach { suggestion ->
            val mark = if (suggestion.isPullRequest) stringResource(R.string.reference_pull_request) else stringResource(R.string.reference_issue)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onPick(suggestion) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .semantics(mergeDescendants = true) { contentDescription = "$mark #${suggestion.number}, ${suggestion.title}" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    if (suggestion.isPullRequest) Icons.AutoMirrored.Outlined.CallMerge else Icons.Outlined.Adjust,
                    contentDescription = null,
                    tint = colors.inkMuted,
                    modifier = Modifier.size(18.dp),
                )
                Text("#${suggestion.number}", style = Soft.type.control, color = colors.ink, modifier = Modifier.clearAndSetSemantics { })
                Text(suggestion.title, style = Soft.type.secondary, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clearAndSetSemantics { })
            }
        }
    }
}

@Composable
internal fun PreviewToggle(previewing: Boolean, onToggle: () -> Unit) {
    WritingAction(
        if (previewing) Icons.Outlined.Edit else Icons.Outlined.Visibility,
        stringResource(if (previewing) R.string.write_preview_back else R.string.write_preview),
        onClick = onToggle,
    )
}

/** A quiet round action beside the one that sends: something done to what is being written. [busy] shows it at work. */
@Composable
internal fun WritingAction(icon: ImageVector, label: String, busy: Boolean = false, onClick: () -> Unit) {
    val colors = Soft.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(colors.surface)
            .softPressable(role = Role.Button) { if (!busy) onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = colors.accent, trackColor = colors.ground, strokeWidth = 2.5.dp)
        } else {
            Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
        }
    }
}

/** A day as the reader's language writes it, like "Oct 10, 2026". */
internal fun formatDay(day: LocalDate): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

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
        issue.dueDate?.let { Text(stringResource(R.string.issue_due, formatDay(it)), style = Soft.type.secondary, color = colors.inkMuted) }
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

/** The count of comments in sight whose Markdown is still being read, kept by the conversation they are in. */
private val LocalUnreadMarkdown = compositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

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
    menu: CommentMenu? = null,
    /** What stands in for the comment's words while it is rewritten. */
    editor: (@Composable () -> Unit)? = null,
) {
    val colors = Soft.colors
    val haptics = LocalHapticFeedback.current
    val offered = rememberUpdatedState(menu?.takeIf { !it.isEmpty && editor == null })
    // Where the menu opens: under the finger after a long press, at its button otherwise.
    var pressedAt by remember { mutableStateOf<IntOffset?>(null) }
    // Where a double tap landed, while its thumbs up shows.
    var thumbAt by remember { mutableStateOf<IntOffset?>(null) }
    val gestures = if (offered.value == null) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(
            onLongPress = { at ->
                if (offered.value != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    pressedAt = at.round()
                }
            },
            onDoubleTap = { at ->
                offered.value?.onReact?.let { react ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    thumbAt = at.round()
                    react(Reaction.THUMBS_UP)
                }
            },
        )
    }
    SwipeToReply(offered.value?.onQuote, modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().testTag(COMMENT_TAG).then(gestures)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // A comment being rewritten stands out from those around it, on a ground of its own.
                    .then(if (editor != null) Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clip(RoundedCornerShape(24.dp)).background(colors.surface).padding(horizontal = 12.dp, vertical = 6.dp) else Modifier.padding(horizontal = 20.dp, vertical = 10.dp)),
            ) {
                // The author takes the room their name needs; the menu sits at the end of the line whatever that is.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(SoftTokens.Pill)
                            .clickable(enabled = author != null) { author?.let { onOpenUser(it.login) } }
                            .padding(end = 8.dp),
                    ) {
                        Avatar(author?.avatarUrl, author?.login ?: "?", size = 28.dp, placeholderColor = colors.raised.takeIf { editor != null } ?: colors.surface, placeholderContentColor = colors.inkMuted)
                        Text(author?.login ?: "ghost", style = Soft.type.control, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        createdAt?.let { Text(relative(it, nowMillis), style = Soft.type.meta, color = colors.inkMuted, maxLines = 1) }
                    }
                    offered.value?.let { CommentMenuButton(it) }
                }
                if (editor != null) {
                    Box(Modifier.padding(top = 10.dp, bottom = 6.dp)) { editor() }
                } else {
                    Column(Modifier.padding(start = TimelineGutter, top = 6.dp).widthIn(max = SoftTokens.MaxMeasure)) {
                        Markdown(body, context, onLinkClick)
                        if (reactions.isNotEmpty()) {
                            // Tappable, each brings its own room: no more is added above.
                            Reactions(reactions, Modifier.padding(top = if (menu?.onReact == null) 8.dp else 0.dp), onReact = menu?.onReact)
                        }
                    }
                }
            }
            pressedAt?.let { at ->
                Box(Modifier.offset { at }) { offered.value?.let { CommentDropdown(it, expanded = true) { pressedAt = null } } }
            }
            thumbAt?.let { at -> ThumbsUpGiven(at) { thumbAt = null } }
        }
    }
}

/** How far a comment is pulled before letting go quotes it, and how far it follows the finger at most. */
private val ReplyPull = 64.dp
private val ReplyPullLimit = 96.dp

/**
 * Pulling a comment toward the end of the line and letting go starts a reply quoting it, as in a messaging app. A
 * reply arrow comes in behind it, full once letting go will quote. Without [onReply] nothing moves.
 */
@Composable
private fun SwipeToReply(onReply: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (onReply == null) {
        Box(modifier) { content() }
        return
    }
    val colors = Soft.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val reply = rememberUpdatedState(onReply)
    val pulled = remember { Animatable(0f) }
    val toEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val threshold = with(LocalDensity.current) { ReplyPull.toPx() }
    val limit = with(LocalDensity.current) { ReplyPullLimit.toPx() }
    fun release(quote: Boolean) {
        if (quote && pulled.value >= threshold) reply.value()
        scope.launch { pulled.animateTo(0f) }
    }
    Box(
        modifier.pointerInput(toEnd) {
            detectHorizontalDragGestures(
                onDragEnd = { release(quote = true) },
                onDragCancel = { release(quote = false) },
            ) { change, amount ->
                val before = pulled.value
                val after = (before + amount * toEnd).coerceIn(0f, limit)
                if (after != before) {
                    change.consume()
                    if (before < threshold && after >= threshold) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch { pulled.snapTo(after) }
                }
            }
        },
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.Reply,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 20.dp)
                .size(24.dp)
                .graphicsLayer {
                    val progress = (pulled.value / threshold).coerceIn(0f, 1f)
                    alpha = progress
                    scaleX = 0.6f + 0.4f * progress
                    scaleY = 0.6f + 0.4f * progress
                },
        )
        Box(Modifier.graphicsLayer { translationX = pulled.value * toEnd }) { content() }
    }
}

/** The thumbs up a double tap gave, where the finger was: it grows, then fades, on its way to the comment's tags. */
@Composable
private fun ThumbsUpGiven(at: IntOffset, onGone: () -> Unit) {
    val shown = remember(at) { Animatable(0f) }
    LaunchedEffect(at) {
        shown.animateTo(1f, tween(450))
        onGone()
    }
    val half = with(LocalDensity.current) { 24.dp.roundToPx() }
    Box(
        Modifier
            .offset { at - IntOffset(half, half) }
            .size(48.dp)
            .graphicsLayer {
                val grown = 0.6f + 0.8f * shown.value
                scaleX = grown
                scaleY = grown
                alpha = 1f - shown.value * shown.value
            }
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) { Text(Reaction.THUMBS_UP.emoji, fontSize = 28.sp) }
}

/** The quiet "more" at the end of a comment's first line, and what it opens. */
@Composable
private fun CommentMenuButton(menu: CommentMenu) {
    val colors = Soft.colors
    var open by remember { mutableStateOf(false) }
    // Its dots end where the comment's text does: the room around them is for the finger, not the eye.
    Box(Modifier.offset(x = 12.dp)) {
        // The avatar's height, so the comment's first line keeps its own; the touch target stays 48dp around it.
        IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp).minimumInteractiveComponentSize()) {
            Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.issue_comment_more), tint = colors.inkMuted)
        }
        CommentDropdown(menu, open) { open = false }
    }
}

/** What a comment's menu offers, from its button or from a long press on the comment. */
@Composable
private fun CommentDropdown(menu: CommentMenu, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(20.dp), containerColor = Soft.colors.raised) {
        // Reactions first, as two rows of four: the quickest answer there is.
        menu.onReact?.let { onReact ->
            FlowRow(Modifier.padding(horizontal = 8.dp), maxItemsInEachRow = 4) {
                Reaction.entries.forEach { reaction ->
                    val name = stringResource(reaction.label)
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button) { onDismiss(); onReact(reaction) }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) { Text(reaction.emoji, fontSize = 22.sp) }
                }
            }
        }
        @Composable
        fun choice(label: Int, action: (() -> Unit)?) {
            if (action != null) DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { onDismiss(); action() })
        }
        choice(R.string.issue_comment_quote, menu.onQuote)
        choice(R.string.issue_comment_edit, menu.onEdit)
        choice(R.string.issue_comment_delete, menu.onDelete)
    }
}

@Composable
internal fun Markdown(body: String, context: ReadmeContext, onLinkClick: (String) -> Unit) {
    val colors = Soft.colors
    val parsed = rememberReadmeState(body, context, colors.isDark)
    if (parsed == null) {
        // Counted while it shows: a list sent somewhere waits for it before letting go.
        val unread = LocalUnreadMarkdown.current
        DisposableEffect(unread) {
            unread.intValue++
            onDispose { unread.intValue-- }
        }
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

/**
 * The reactions a text was given, each with its count. With [onReact], tapping one gives it too, or takes back the
 * reader's own: each then has a finger's room around it.
 */
@Composable
private fun Reactions(reactions: Map<Reaction, Int>, modifier: Modifier = Modifier, onReact: ((Reaction) -> Unit)? = null) {
    val gap = if (onReact == null) 6.dp else 0.dp
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
        reactions.forEach { (reaction, count) ->
            if (onReact == null) {
                SoftTag("${reaction.emoji} $count")
            } else {
                val said = stringResource(R.string.issue_reaction_count, stringResource(reaction.label), count)
                Box(
                    Modifier
                        .clip(SoftTokens.Pill)
                        .clickable(role = Role.Button) { onReact(reaction) }
                        .minimumInteractiveComponentSize()
                        .clearAndSetSemantics { contentDescription = said },
                    contentAlignment = Alignment.Center,
                ) { SoftTag("${reaction.emoji} $count") }
            }
        }
    }
}

private val Reaction.label: Int
    get() = when (this) {
        Reaction.THUMBS_UP -> R.string.reaction_thumbs_up
        Reaction.THUMBS_DOWN -> R.string.reaction_thumbs_down
        Reaction.LAUGH -> R.string.reaction_laugh
        Reaction.HOORAY -> R.string.reaction_hooray
        Reaction.CONFUSED -> R.string.reaction_confused
        Reaction.HEART -> R.string.reaction_heart
        Reaction.ROCKET -> R.string.reaction_rocket
        Reaction.EYES -> R.string.reaction_eyes
    }

@Composable
private fun TimelineEntry(
    item: TimelineItem,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onLinkClick: (String) -> Unit,
    editor: (@Composable () -> Unit)? = null,
    menuFor: (TimelineItem.Comment) -> CommentMenu? = { null },
) {
    val colors = Soft.colors
    val time = item.createdAt?.let { relative(it, nowMillis) }.orEmpty()
    when (item) {
        is TimelineItem.Comment -> Comment(item.author, item.body, item.createdAt, item.reactions, context, nowMillis, onOpenUser, onLinkClick, menu = menuFor(item), editor = editor)
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
            EventLine(Icons.AutoMirrored.Outlined.Label, stringResource(if (item.added) R.string.issue_labeled_event else R.string.issue_unlabeled_event, who)) {
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
                // The day is kept as written (ISO); anything else is shown as it came.
                ConversationEvent.DEADLINE_SET -> Icons.Outlined.Event to stringResource(
                    R.string.issue_deadline_set_event, who, runCatching { formatDay(LocalDate.parse(subject)) }.getOrDefault(subject), time,
                )
                ConversationEvent.DEADLINE_REMOVED -> Icons.Outlined.Event to stringResource(R.string.issue_deadline_removed_event, who, time)
                ConversationEvent.TRACKING_STARTED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_started_event, who, time)
                ConversationEvent.TRACKING_STOPPED -> Icons.Outlined.Timer to stringResource(R.string.issue_tracking_stopped_event, who, time)
                ConversationEvent.TIME_ADDED -> Icons.Outlined.Timer to stringResource(R.string.issue_time_added_event, who, time)
                ConversationEvent.DEPENDENCY_ADDED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_dependency_added_event, who, subject, time)
                ConversationEvent.DEPENDENCY_REMOVED -> Icons.Outlined.AccountTree to stringResource(R.string.issue_dependency_removed_event, who, subject, time)
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
