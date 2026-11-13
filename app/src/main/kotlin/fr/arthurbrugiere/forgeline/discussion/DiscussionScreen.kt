package fr.arthurbrugiere.forgeline.discussion

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ArrowCircleUp
import androidx.compose.material.icons.outlined.ArrowCircleUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.DiscussionComment
import fr.arthurbrugiere.forgeline.core.model.DiscussionReactions
import fr.arthurbrugiere.forgeline.core.model.DiscussionUpvotes
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.Reaction
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSendButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.issue.Comment
import fr.arthurbrugiere.forgeline.issue.CommentMenu
import fr.arthurbrugiere.forgeline.navigation.DiscussionRoute
import fr.arthurbrugiere.forgeline.navigation.openForgeLink
import fr.arthurbrugiere.forgeline.pull.ThreadCommentEditor
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.HeaderRepoLink
import fr.arthurbrugiere.forgeline.ui.LocalOpenCommit
import fr.arthurbrugiere.forgeline.ui.LocalOpenDiscussion
import fr.arthurbrugiere.forgeline.ui.LocalOpenRelease
import fr.arthurbrugiere.forgeline.ui.LocalOpenWiki
import fr.arthurbrugiere.forgeline.ui.Markdown
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.ShareLinkButton
import fr.arthurbrugiere.forgeline.ui.conversationContext
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.overBottomBarOrKeyboard
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberCustomTabOpener
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.ui.staysAboveKeyboard
import kotlinx.coroutines.launch

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
    val openWiki = LocalOpenWiki.current
    val openCommit = LocalOpenCommit.current
    val openFile = fr.arthurbrugiere.forgeline.ui.LocalOpenFile.current
    DiscussionScreen(
        state = state,
        signedIn = session.signedInOn(repo.forge),
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onOpenRepo = onOpenRepo,
        onOpenUser = onOpenUser,
        onOpenInBrowser = openUrl,
        onLinkClick = { url -> openForgeLink(url, repo.forge, onOpenRepo, onOpenIssue, onOpenUser, openUrl, onOpenRelease = openRelease, onOpenDiscussion = openDiscussion, onOpenWiki = openWiki, onOpenCommit = openCommit, onOpenFile = openFile) },
        onSignIn = onSignIn,
        onErrorShown = viewModel::errorShown,
        onSend = viewModel::send,
        onSetAnswer = viewModel::setAnswer,
        onSendErrorShown = viewModel::sendErrorShown,
        onReact = viewModel::react,
        onUpvote = viewModel::upvote,
        onEditComment = viewModel::editComment,
        onDeleteComment = viewModel::deleteComment,
        onEditDiscussion = viewModel::editDiscussion,
        onDeleteDiscussion = viewModel::deleteDiscussion,
        onReactionErrorShown = viewModel::reactionErrorShown,
    )
}

/** How far a reply is set in: it starts where the words of the comment it answers do. */
private val ReplyInset = 40.dp

/**
 * A discussion: what was asked or said, then the comments in the order they came, each with the replies it drew and
 * what the reader may do with it (reply, pick it as the answer), then a field to comment at the end. The answer that
 * was picked wears a tag.
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
    /** A comment to send: on the discussion, or a reply under the comment given. */
    onSend: (String, DiscussionComment?) -> Unit = { _, _ -> },
    onSetAnswer: (DiscussionComment, Boolean) -> Unit = { _, _ -> },
    onSendErrorShown: () -> Unit = {},
    /** A reaction given to the discussion's text, a comment or a reply, by its id; or taken back. */
    onReact: (String, Reaction) -> Unit = { _, _ -> },
    /** An upvote given to the discussion or a comment, by its id; or taken back. */
    onUpvote: (String) -> Unit = {},
    /** A comment or reply rewritten, or deleted; the discussion's title and text rewritten, or it deleted. */
    onEditComment: (DiscussionComment, String) -> Unit = { _, _ -> },
    onDeleteComment: (DiscussionComment) -> Unit = {},
    onEditDiscussion: (String, String) -> Unit = { _, _ -> },
    onDeleteDiscussion: () -> Unit = {},
    onReactionErrorShown: () -> Unit = {},
    nowMillis: Long = rememberNow(state.discussion),
) {
    val colors = Soft.colors
    val discussion = state.discussion
    val summary = state.summary
    val forge = state.repo.forge.displayName
    val context = state.repo.conversationContext()
    val snackbar = remember { SnackbarHostState() }
    // With the discussion on screen, a refresh that failed is only said; without it, it is the screen.
    SayOnce(stringResource(R.string.trending_refresh_failed).takeIf { state.error != null && discussion != null }, snackbar, onErrorShown)
    // What was written stays in its field when it didn't go through.
    val sendError = state.sendError
    SayOnce(
        sendError?.let {
            when {
                it == ForgeError.Unauthorized -> stringResource(R.string.issue_comment_error_expired, forge)
                it == ForgeError.Network -> stringResource(R.string.issue_comment_error_offline)
                it is ForgeError.Http && it.status in setOf(403, 404, 422) -> stringResource(R.string.issue_comment_error_refused)
                else -> stringResource(R.string.issue_comment_error)
            }
        },
        snackbar,
        onSendErrorShown,
    )
    SayOnce(stringResource(R.string.issue_reaction_failed).takeIf { state.reactionError != null }, snackbar, onReactionErrorShown)
    var draft by rememberSaveable { mutableStateOf("") }
    // The comment a reply is being written under, by its id, and that reply.
    var replyingTo by rememberSaveable { mutableStateOf<String?>(null) }
    var reply by rememberSaveable { mutableStateOf("") }
    // What is being rewritten in its place, by its id (the discussion's own for its title and text), and its words.
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var edited by rememberSaveable { mutableStateOf("") }
    var editedTitle by rememberSaveable { mutableStateOf("") }
    // What is asked about before it is deleted, by its id.
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sent) {
        when (val sent = state.sent?.first) {
            null -> Unit
            DiscussionViewModel.COMMENT -> draft = ""
            replyingTo -> {
                reply = ""
                replyingTo = null
            }
            // Rewritten: it reads as it now stands.
            DiscussionViewModel.EDIT_DISCUSSION, "edit:$editing" -> editing = null
            else -> if (sent == "delete:$replyingTo") replyingTo = null
        }
    }
    // Deleted from here, the discussion leaves nothing to show.
    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    discussion?.let { shown ->
        val asked = deleting ?: return@let
        val whole = asked == shown.id
        SoftConfirmSheet(
            title = stringResource(if (whole) R.string.discussion_delete_title else R.string.issue_comment_delete_title),
            body = stringResource(if (whole) R.string.discussion_delete_body else R.string.issue_comment_delete_body),
            confirm = stringResource(R.string.issue_comment_delete),
            cancel = stringResource(R.string.cancel),
            onConfirm = {
                deleting = null
                if (whole) onDeleteDiscussion() else shown.commentOrReply(asked)?.let(onDeleteComment)
            },
            onDismiss = { deleting = null },
        )
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Quoting the discussion's text adds it to the comment at the end, which the list goes to; quoting a comment or a
    // reply starts a reply in that comment's thread, quoting it.
    val quoteInComment = { text: String ->
        draft = draft.trimEnd().let { if (it.isEmpty()) "${quoted(text)}\n\n" else "$it\n\n${quoted(text)}\n\n" }
        scope.launch { listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
        Unit
    }
    val quoteInReply = { thread: DiscussionComment, text: String ->
        val written = if (replyingTo == thread.id) reply.trimEnd() else ""
        reply = if (written.isEmpty()) "${quoted(text)}\n\n" else "$written\n\n${quoted(text)}\n\n"
        replyingTo = thread.id
    }

    // Reacting takes an account and the forge's leave, not the right to comment: a locked discussion may still take them.
    fun reactTo(id: String?, reactions: DiscussionReactions): ((Reaction) -> Unit)? = if (id != null && reactions.canReact) ({ reaction -> onReact(id, reaction) }) else null
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
                // The comment and reply boxes stay above the keyboard (edge-to-edge doesn't resize the window for it).
                modifier = Modifier.fillMaxSize().sideSafeArea().imePadding(),
            ) {
                item(key = "header") {
                    SoftHeader(
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                        actions = {
                            ShareLinkButton(state.webUrl, (state.discussion?.summary ?: state.summary)?.title ?: "${state.repo.fullName}#${state.number}")
                            SoftIconButton(onClick = { onOpenInBrowser(state.webUrl) }) {
                                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = stringResource(R.string.repo_open_on_forge, forge), tint = Soft.colors.ink)
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            HeaderRepoLink(state.repo.fullName, onClick = { onOpenRepo(state.repo) })
                            Text(
                                summary?.let { "${it.title} - #${it.number}" } ?: "#${state.number}",
                                style = Soft.type.detailTitle,
                                color = Soft.colors.ink,
                                modifier = Modifier.semantics { heading() },
                            )
                            if (summary != null) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    summary.category?.let { SoftTag(it) }
                                    if (summary.isAnswered == true) SoftTag(stringResource(R.string.repo_discussion_answered))
                                    val upvotes = discussion?.upvoteTally ?: DiscussionUpvotes(summary.upvotes)
                                    val toggle = discussion?.id?.takeIf { upvotes.canGive }?.let { id -> { onUpvote(id) } }
                                    if (upvotes.count > 0 || toggle != null) Upvotes(upvotes, toggle)
                                }
                                val who = summary.author?.login ?: stringResource(R.string.deleted_account)
                                summary.createdAt?.let {
                                    Text(stringResource(R.string.discussion_opened, who, relative(it, nowMillis)), style = Soft.type.secondary, color = Soft.colors.inkMuted)
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
                            // When it was started is said in the header already.
                            Comment(
                                discussion.summary.author, discussion.body.ifBlank { stringResource(R.string.discussion_no_text) }, createdAt = null, discussion.reactions.counts,
                                context, nowMillis, onOpenUser, onLinkClick, Modifier.padding(top = 12.dp),
                                menu = CommentMenu(
                                    onQuote = { quoteInComment(discussion.body) }.takeIf { state.canComment && discussion.body.isNotBlank() },
                                    onEdit = {
                                        edited = discussion.body
                                        editedTitle = discussion.summary.title
                                        editing = discussion.id
                                    }.takeIf { discussion.canEdit && discussion.id != null },
                                    onDelete = { deleting = discussion.id }.takeIf { discussion.canDelete && discussion.id != null },
                                    onReact = reactTo(discussion.id, discussion.reactions),
                                ),
                                editor = if (editing != null && editing == discussion.id) {
                                    {
                                        DiscussionEditor(
                                            editedTitle, { editedTitle = it }, edited, { edited = it },
                                            busy = state.sending == DiscussionViewModel.EDIT_DISCUSSION,
                                            onCancel = { editing = null },
                                            onSave = { onEditDiscussion(editedTitle, edited) },
                                        )
                                    }
                                } else {
                                    null
                                },
                                parted = false,
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
                            item(key = comment.id) {
                                Entry(
                                    comment, context, nowMillis, onOpenUser, onLinkClick,
                                    onQuote = { quoteInReply(comment, comment.body) }.takeIf { state.canComment },
                                    onReact = reactTo(comment.id, comment.reactions),
                                    onUpvote = { onUpvote(comment.id) },
                                    onEdit = {
                                        edited = comment.body
                                        editing = comment.id
                                    }.takeIf { comment.canEdit },
                                    onDelete = { deleting = comment.id }.takeIf { comment.canDelete },
                                    editor = editorFor(comment, editing, edited, { edited = it }, state.sending, { editing = null }, onEditComment),
                                )
                            }
                            items(comment.replies, key = { it.id }) { reply ->
                                Entry(
                                    reply, context, nowMillis, onOpenUser, onLinkClick,
                                    // A reply has none of its own: one to it goes in its thread.
                                    onQuote = { quoteInReply(comment, reply.body) }.takeIf { state.canComment },
                                    onReact = reactTo(reply.id, reply.reactions),
                                    onUpvote = { onUpvote(reply.id) },
                                    onEdit = {
                                        edited = reply.body
                                        editing = reply.id
                                    }.takeIf { reply.canEdit },
                                    onDelete = { deleting = reply.id }.takeIf { reply.canDelete },
                                    editor = editorFor(reply, editing, edited, { edited = it }, state.sending, { editing = null }, onEditComment),
                                    isReply = true,
                                )
                            }
                            if (state.canComment || comment.canMarkAnswer || comment.canUnmarkAnswer) {
                                item(key = "actions-${comment.id}") {
                                    CommentActions(
                                        comment,
                                        canReply = state.canComment,
                                        replying = replyingTo == comment.id,
                                        reply = reply,
                                        onReplyChange = { reply = it },
                                        onStartReply = {
                                            if (replyingTo != comment.id) reply = ""
                                            replyingTo = comment.id
                                        },
                                        onSendReply = { onSend(reply, comment) },
                                        onSetAnswer = { onSetAnswer(comment, it) },
                                        sending = state.sending,
                                    )
                                }
                            }
                        }
                        item(key = "end") {
                            Column(
                                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (discussion.isPartial) Text(stringResource(R.string.discussion_partial), style = Soft.type.secondary, color = colors.inkMuted)
                                if (discussion.locked) Text(stringResource(R.string.discussion_locked), style = Soft.type.secondary, color = colors.inkMuted)
                                if (state.canComment) {
                                    val sendingComment = state.sending == DiscussionViewModel.COMMENT
                                    Row(Modifier.staysAboveKeyboard(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SoftTextField(
                                            value = draft,
                                            onValueChange = { draft = it },
                                            placeholder = stringResource(R.string.discussion_comment_placeholder),
                                            singleLine = false,
                                            maxLines = 8,
                                            readOnly = sendingComment,
                                            modifier = Modifier.weight(1f),
                                        )
                                        SoftSendButton(
                                            onClick = { onSend(draft, null) },
                                            description = stringResource(R.string.discussion_comment_send),
                                            enabled = draft.isNotBlank() && state.sending == null,
                                            busy = sendingComment,
                                        )
                                    }
                                }
                                SoftTonalButton(stringResource(R.string.repo_open_on_forge, forge), { onOpenInBrowser(state.webUrl) })
                            }
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).overBottomBarOrKeyboard())
    }
}

/**
 * Under a comment and its replies: a reply to it, written in place, and picking it as the answer or taking that back,
 * where the reader may.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentActions(
    comment: DiscussionComment,
    canReply: Boolean,
    replying: Boolean,
    reply: String,
    onReplyChange: (String) -> Unit,
    onStartReply: () -> Unit,
    onSendReply: () -> Unit,
    onSetAnswer: (Boolean) -> Unit,
    sending: String?,
) {
    val sendingReply = sending == comment.id
    Column(
        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp + ReplyInset, end = 20.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (replying) {
            Row(Modifier.staysAboveKeyboard(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftTextField(
                    value = reply,
                    onValueChange = onReplyChange,
                    placeholder = stringResource(R.string.discussion_reply_placeholder, comment.author?.login ?: stringResource(R.string.deleted_account)),
                    singleLine = false,
                    maxLines = 8,
                    readOnly = sendingReply,
                    modifier = Modifier.weight(1f),
                )
                SoftSendButton(
                    onClick = onSendReply,
                    description = stringResource(R.string.discussion_reply_send),
                    enabled = reply.isNotBlank() && sending == null,
                    busy = sendingReply,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canReply && !replying) SoftTonalButton(stringResource(R.string.discussion_reply), onClick = onStartReply)
            if (comment.canMarkAnswer && !comment.isAnswer) {
                SoftTonalButton(stringResource(R.string.discussion_mark_answer), onClick = { onSetAnswer(true) }, enabled = sending == null)
            }
            if (comment.canUnmarkAnswer && comment.isAnswer) {
                SoftTonalButton(stringResource(R.string.discussion_unmark_answer), onClick = { onSetAnswer(false) }, enabled = sending == null)
            }
        }
    }
}

/**
 * How many upvoted it. Where the reader may upvote, it is a toggle: an outlined arrow, filled marigold with its count
 * in bold once theirs is given, as the star toggle shows a star.
 */
@Composable
private fun Upvotes(upvotes: DiscussionUpvotes, onToggle: (() -> Unit)? = null) {
    val count = upvotes.count
    val said = androidx.compose.ui.res.pluralStringResource(R.plurals.discussion_upvotes, count, count)
    if (onToggle == null) {
        Box(Modifier.clearAndSetSemantics { contentDescription = said }) {
            SoftTag(stringResource(R.string.discussion_upvotes, count))
        }
        return
    }
    val colors = Soft.colors
    val label = stringResource(R.string.discussion_upvote)
    Row(
        Modifier
            .clip(SoftTokens.TagCorner)
            .toggleable(value = upvotes.given, role = Role.Checkbox, onValueChange = { onToggle() })
            .minimumInteractiveComponentSize()
            .clearAndSetSemantics {
                contentDescription = "$label, $said"
                toggleableState = androidx.compose.ui.state.ToggleableState(upvotes.given)
                role = Role.Checkbox
                onClick { onToggle(); true }
            }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            if (upvotes.given) Icons.Filled.ArrowCircleUp else Icons.Outlined.ArrowCircleUp,
            contentDescription = null,
            tint = if (upvotes.given) colors.accent else colors.inkMuted,
            modifier = Modifier.size(18.dp),
        )
        Text(
            count.toString(),
            style = Soft.type.tag.copy(fontWeight = if (upvotes.given) FontWeight.Bold else Soft.type.tag.fontWeight),
            color = colors.ink,
        )
    }
}

/**
 * One turn of the discussion, as a conversation's comments are drawn (who and when, their words, the reactions given,
 * a menu), its tags over its words. A reply stands in from the comment it answers, along a line.
 */
@Composable
private fun Entry(
    comment: DiscussionComment,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    onQuote: (() -> Unit)?,
    onReact: ((Reaction) -> Unit)?,
    onUpvote: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    editor: (@Composable () -> Unit)? = null,
    isReply: Boolean = false,
) {
    val line = Soft.colors.track
    val thread = if (!isReply) {
        Modifier
    } else {
        Modifier.padding(start = ReplyInset).drawBehind {
            val x = 8.dp.toPx().let { if (layoutDirection == LayoutDirection.Ltr) it else size.width - it }
            drawLine(line, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
        }
    }
    val upvotes = comment.upvoteTally
    val toggle = onUpvote?.takeIf { upvotes.canGive }
    val tags: (@Composable () -> Unit)? = if (comment.isAnswer || upvotes.count > 0 || toggle != null) {
        {
            if (comment.isAnswer) SoftTag(stringResource(R.string.discussion_answer), background = Soft.colors.fields[2])
            if (upvotes.count > 0 || toggle != null) Upvotes(upvotes, toggle)
        }
    } else {
        null
    }
    Comment(
        comment.author, comment.body, comment.createdAt, comment.reactions.counts, context, nowMillis, onOpenUser, onLinkClick, thread,
        menu = CommentMenu(onQuote = onQuote, onEdit = onEdit, onDelete = onDelete, onReact = onReact),
        editor = editor,
        tags = tags,
        parted = false,
    )
}

/** The field [comment] is rewritten in, while it is the one being rewritten. */
private fun editorFor(
    comment: DiscussionComment,
    editing: String?,
    edited: String,
    onChange: (String) -> Unit,
    sending: String?,
    onCancel: () -> Unit,
    onSave: (DiscussionComment, String) -> Unit,
): (@Composable () -> Unit)? = if (editing != comment.id) {
    null
} else {
    { ThreadCommentEditor(edited, onChange, busy = sending == "edit:${comment.id}", onCancel = onCancel, onSave = { onSave(comment, edited) }) }
}

/** The discussion's title and text, rewritten where they stand, then saved or left as they were. */
@Composable
private fun DiscussionEditor(title: String, onTitleChange: (String) -> Unit, body: String, onBodyChange: (String) -> Unit, busy: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftTextField(value = title, onValueChange = onTitleChange, placeholder = stringResource(R.string.discussion_title_placeholder), readOnly = busy, modifier = Modifier.fillMaxWidth())
        SoftTextField(
            value = body, onValueChange = onBodyChange, placeholder = stringResource(R.string.discussion_editing),
            singleLine = false, maxLines = 12, readOnly = busy, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftTonalButton(stringResource(R.string.cancel), onClick = onCancel, enabled = !busy)
            SoftButton(stringResource(R.string.issue_comment_save), onClick = onSave, enabled = title.isNotBlank() && !busy)
        }
    }
}

/** [text] as Markdown quotes it, line by line. */
internal fun quoted(text: String): String = text.trim().lines().joinToString("\n") { "> $it".trimEnd() }
