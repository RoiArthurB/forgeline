package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.markdown.ReadmeContext
import fr.arthurbrugiere.forgeline.core.model.IssueState
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.RepoAccess
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.blobBaseUrl
import fr.arthurbrugiere.forgeline.core.model.rawBaseUrl
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSendButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.ui.Markdown
import fr.arthurbrugiere.forgeline.ui.label
import fr.arthurbrugiere.forgeline.ui.referenceLinks
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.staysAboveKeyboard

/**
 * The reader's turn, closing the conversation: a field that grows with the comment and one action. Signed out of the
 * conversation's forge, it says so and offers to sign in. A comment that wasn't sent stays written. Whoever may close
 * the conversation, or reopen it, finds that beside the comment's action, quieter.
 */
@Composable
internal fun Composer(
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
            background = colors.raised,
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
            // A rewrite is saved, in words; a new comment goes with the send arrow, quiet until something is written.
            if (isEditing) {
                SoftButton(
                    stringResource(if (state.isCommenting) R.string.issue_comment_saving else R.string.issue_comment_save),
                    onSend,
                    enabled = state.draft.isNotBlank() && !state.isCommenting,
                )
            } else {
                SoftSendButton(
                    onClick = onSend,
                    description = stringResource(if (state.isCommenting) R.string.issue_comment_sending else R.string.issue_comment_send),
                    enabled = state.draft.isNotBlank() && !state.isCommenting,
                    busy = state.isCommenting,
                )
            }
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
            .clip(RoundedCornerShape(8.dp))
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
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(colors.surface).testTag(REFERENCE_SUGGESTIONS_TAG)) {
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

/** A quiet action on a tile beside the one that sends: something done to what is being written. [busy] shows it at work. */
@Composable
internal fun WritingAction(icon: ImageVector, label: String, busy: Boolean = false, onClick: () -> Unit) {
    val colors = Soft.colors
    Box(
        Modifier
            .size(48.dp)
            .clip(SoftTokens.TileCorner)
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
