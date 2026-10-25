package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.ReviewThread
import fr.arthurbrugiere.forgeline.core.model.parsePatch
import fr.arthurbrugiere.forgeline.core.model.splitSuggestions
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHairline
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSendButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.issue.Comment
import fr.arthurbrugiere.forgeline.issue.CommentMenu

/**
 * The remarks on lines of a pull request's change, near its end, after its conversation: the open threads each with
 * the code it is on, what was said, and a way to answer or resolve it; the resolved ones folded behind one row.
 * [canReply] is whether the reader may write here at all; resolving also takes the forge's say ([ReviewThread.canResolve]).
 * [branch] is where a suggestion goes once committed, for whoever may push there ([canCommit]).
 */
@Composable
fun ReviewThreads(
    threads: List<ReviewThread>,
    state: PullRequestUiState,
    actions: PullActions,
    canReply: Boolean,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    canCommit: Boolean = false,
    branch: String = "",
) {
    if (threads.isEmpty()) return
    val colors = Soft.colors
    val (open, resolved) = threads.partition { !it.resolved }
    var showResolved by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        Text(
            if (open.isNotEmpty()) {
                pluralStringResource(R.plurals.threads_open, open.size, open.size)
            } else {
                pluralStringResource(R.plurals.threads_all_resolved, resolved.size, resolved.size)
            },
            style = Soft.type.name,
            color = colors.ink,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp).semantics { heading() },
        )
        open.forEach { thread ->
            SoftHairline()
            Thread(thread, state, actions, canReply, canCommit, branch, context, nowMillis, onOpenUser, onLinkClick)
        }
        if (resolved.isNotEmpty()) {
            SoftHairline()
            if (showResolved) {
                resolved.forEachIndexed { index, thread ->
                    if (index > 0) SoftHairline()
                    Thread(thread, state, actions, canReply, canCommit, branch, context, nowMillis, onOpenUser, onLinkClick)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(SoftTokens.RowCorner)
                        .clickable(role = Role.Button) { showResolved = true }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.CheckCircleOutline, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        pluralStringResource(R.plurals.threads_show_resolved, resolved.size, resolved.size),
                        style = Soft.type.control,
                        color = colors.inkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** How many lines of the code a remark is on are shown above it: the line itself and a few before it. */
private const val HUNK_LINES_SHOWN = 4

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Thread(
    thread: ReviewThread,
    state: PullRequestUiState,
    actions: PullActions,
    canReply: Boolean,
    canCommit: Boolean,
    branch: String,
    context: ReadmeContext,
    nowMillis: Long,
    onOpenUser: (String) -> Unit,
    onLinkClick: (String) -> Unit,
) {
    val colors = Soft.colors
    // The suggestion asked to be committed, as "comment:suggestion", until the reader has said yes or no.
    var committing by rememberSaveable(thread.id) { mutableStateOf<String?>(null) }
    var replying by rememberSaveable(thread.id) { mutableStateOf(false) }
    var reply by rememberSaveable(thread.id) { mutableStateOf("") }
    // A reply that went through empties its field and puts it away.
    LaunchedEffect(state.replied) {
        if (state.replied?.first == thread.id) {
            reply = ""
            replying = false
        }
    }
    val busy = state.busyThread == thread.id
    // The reader's own remark being rewritten in its place, and the one they asked to take back.
    var editing by rememberSaveable(thread.id) { mutableStateOf<Long?>(null) }
    var edited by rememberSaveable(thread.id) { mutableStateOf("") }
    var deleting by rememberSaveable(thread.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.threadEdited) {
        if (state.threadEdited != null && state.threadEdited.first == editing) editing = null
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Where it is: the file and the line, and whether the code it was on has moved on since.
        Text(
            listOfNotNull(
                "${thread.path}${thread.line?.let { ":$it" }.orEmpty()}",
                stringResource(R.string.thread_outdated).takeIf { thread.outdated },
                stringResource(R.string.thread_resolved).takeIf { thread.resolved },
            ).joinToString(" · "),
            style = Soft.type.meta.copy(fontFamily = FontFamily.Monospace),
            color = colors.inkMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        val lines = remember(thread.hunk) { thread.hunk?.let { parsePatch(it).lastOrNull()?.lines?.takeLast(HUNK_LINES_SHOWN) }.orEmpty() }
        if (lines.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().clip(SoftTokens.RowCorner)) { lines.forEach { LineRow(it, onClick = null) } }
        }
        val over = remember(thread) { thread.suggestedOver }
        // Only where the lines it replaces are known, and still where the remark was made.
        val commits = canCommit && state.appliesSuggestions && over.isNotEmpty() && !thread.outdated
        thread.comments.forEachIndexed { at, comment ->
            val split = remember(comment.body) { splitSuggestions(comment.body) }
            val own = canReply && state.editsThreadComments && state.me != null && comment.author?.login.equals(state.me, ignoreCase = true)
            Comment(
                comment.author, split.text, comment.createdAt, emptyMap(), context, nowMillis, onOpenUser, onLinkClick,
                menu = CommentMenu(
                    onQuote = null,
                    onEdit = {
                        edited = comment.body
                        editing = comment.id
                    },
                    onDelete = { deleting = comment.id }.takeIf { state.deletesThreadComments },
                ).takeIf { own },
                editor = if (editing == comment.id) {
                    {
                        ThreadCommentEditor(
                            text = edited,
                            onChange = { edited = it },
                            busy = busy,
                            onCancel = { editing = null },
                            onSave = { actions.onEditThreadComment(thread, comment, edited) },
                        )
                    }
                } else {
                    null
                },
            )
            if (deleting == comment.id) {
                SoftConfirmSheet(
                    title = stringResource(R.string.issue_comment_delete_title),
                    body = stringResource(R.string.issue_comment_delete_body),
                    confirm = stringResource(R.string.issue_comment_delete),
                    cancel = stringResource(R.string.cancel),
                    onConfirm = {
                        deleting = null
                        actions.onDeleteThreadComment(thread, comment)
                    },
                    onDismiss = { deleting = null },
                )
            }
            split.suggestions.forEachIndexed { index, suggested ->
                val key = "$at:$index"
                // Where the forge keeps it (GitLab), it says itself what it replaces and whether it can still be made.
                val kept = comment.suggestions.getOrNull(index)?.takeIf { it.lines == suggested }
                SuggestedChange(
                    suggested,
                    kept?.let { k -> k.over.mapIndexed { i, text -> DiffLine(DiffLineKind.CONTEXT, text, oldNumber = null, newNumber = k.firstLine + i) } } ?: over,
                    thread.line,
                    busy = busy,
                    onCommit = { committing = key }.takeIf { if (kept != null) canCommit && state.appliesSuggestions && kept.appliable else commits },
                    applied = kept?.applied == true,
                )
                if (committing == key) {
                    SoftConfirmSheet(
                        title = stringResource(R.string.thread_suggestion_confirm_title),
                        body = stringResource(R.string.thread_suggestion_confirm_body, branch),
                        confirm = stringResource(R.string.thread_suggestion_confirm),
                        cancel = stringResource(R.string.cancel),
                        onConfirm = {
                            committing = null
                            actions.onApplySuggestion(thread, suggested)
                        },
                        onDismiss = { committing = null },
                    )
                }
            }
        }
        if (replying) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SoftTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    placeholder = stringResource(R.string.thread_reply_placeholder),
                    singleLine = false,
                    maxLines = 8,
                    readOnly = busy,
                    modifier = Modifier.weight(1f),
                )
                SoftSendButton(
                    onClick = { actions.onReply(thread, reply) },
                    description = stringResource(R.string.thread_reply_send),
                    enabled = reply.isNotBlank() && !busy,
                    busy = busy,
                )
            }
        }
        val canResolve = canReply && state.resolvesThreads && thread.canResolve
        if ((canReply && !replying) || canResolve) {
            FlowRow(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canReply && !replying && thread.line != null) SoftTonalButton(stringResource(R.string.thread_reply), onClick = { replying = true })
                if (canResolve) {
                    SoftTonalButton(
                        stringResource(if (thread.resolved) R.string.thread_unresolve else R.string.thread_resolve),
                        onClick = { actions.onSetResolved(thread, !thread.resolved) },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** The reader's own remark, rewritten where it stands: its words in a field, then saved or left as they were. */
@Composable
private fun ThreadCommentEditor(text: String, onChange: (String) -> Unit, busy: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftTextField(
            value = text, onValueChange = onChange, placeholder = stringResource(R.string.issue_comment_editing),
            singleLine = false, maxLines = 12, readOnly = busy, modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftTonalButton(stringResource(R.string.cancel), onClick = onCancel, enabled = !busy)
            SoftButton(stringResource(R.string.issue_comment_save), onClick = onSave, enabled = text.isNotBlank() && !busy)
        }
    }
}

/**
 * A change a reviewer suggests, as it would read once made: the lines the remark is on taken away, where the forge
 * gave them ([over]), and the suggested ones in their place from [line]; or that the reviewer would take them away.
 * [onCommit] offers to make it, where the reader may; [applied] says it was made, where the forge tells.
 */
@Composable
private fun SuggestedChange(suggested: List<String>, over: List<DiffLine>, line: Int?, busy: Boolean, onCommit: (() -> Unit)?, applied: Boolean = false) {
    val colors = Soft.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            listOfNotNull(
                stringResource(if (suggested.isEmpty()) R.string.thread_suggestion_removes else R.string.thread_suggestion),
                stringResource(R.string.thread_suggestion_applied).takeIf { applied },
            ).joinToString(" · "),
            style = Soft.type.meta,
            color = colors.inkMuted,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Column(Modifier.fillMaxWidth().clip(SoftTokens.RowCorner)) {
            over.forEach { LineRow(DiffLine(DiffLineKind.REMOVED, it.text, oldNumber = it.newNumber, newNumber = null), onClick = null) }
            val first = over.firstOrNull()?.newNumber ?: line ?: 1
            suggested.forEachIndexed { index, text -> LineRow(DiffLine(DiffLineKind.ADDED, text, oldNumber = null, newNumber = first + index), onClick = null) }
        }
        if (onCommit != null) {
            SoftTonalButton(stringResource(R.string.thread_suggestion_commit), onClick = onCommit, enabled = !busy, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}
