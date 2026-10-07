package fr.arthurbrugiere.forgeline.issue

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.NewIssueRoute
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun NewIssueRoute(route: NewIssueRoute, session: SessionState, onBack: () -> Unit, onSignIn: () -> Unit, onCreated: (IssueRef) -> Unit) {
    val repo = route.repo
    val editing = route.editing
    val key = if (editing == null) "new-issue:${repo.key}" else "edit-issue:${repo.key}${if (editing.isPullRequest == true) "!" else "#"}${editing.number}"
    val viewModel = hiltViewModel<NewIssueViewModel, NewIssueViewModel.Factory>(key = key) { it.create(repo, editing) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A new issue's conversation takes the form's place; an edited one is already under it.
    LaunchedEffect(state.created) { state.created?.let { if (editing == null) onCreated(it) else onBack() } }
    NewIssueScreen(
        state = state,
        // Opening an issue takes an account on the repository's own forge.
        signedIn = session.signedInOn(repo.forge),
        onTitleChange = viewModel::titleChanged,
        onBodyChange = viewModel::bodyChanged,
        onSend = viewModel::send,
        onSignIn = onSignIn,
        onBack = onBack,
    )
}

/**
 * Writing an issue: its title, then a description that grows with what is written, and one action. Signed out of the
 * repository's forge, it says so and offers to sign in. An issue that wasn't sent stays written.
 */
@Composable
fun NewIssueScreen(
    state: NewIssueUiState,
    signedIn: Boolean,
    onTitleChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onSend: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val forge = state.repo.forge.displayName
    val scroll = rememberScrollState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        Column(
            // The fields stay above the keyboard (edge-to-edge doesn't resize the window for it).
            Modifier.fillMaxSize().sideSafeArea().imePadding().verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The field an open conversation wears: that is what this becomes.
            SoftHeader(
                tint = colors.fields[2],
                title = stringResource(
                    when {
                        state.editing == null -> R.string.new_issue_title
                        state.isPullRequest -> R.string.edit_pull_title
                        else -> R.string.edit_issue_title
                    },
                ),
                onBack = onBack,
                backDescription = stringResource(R.string.navigate_up),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        state.repo.fullName,
                        style = Soft.type.secondary,
                        color = colors.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ForgeMark(state.repo.forge, style = Soft.type.secondary, color = colors.inkMuted, size = 16.dp)
                }
            }
            Column(
                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!signedIn) {
                    Text(stringResource(R.string.new_issue_sign_in, forge), style = Soft.type.body, color = colors.inkMuted)
                    SoftTonalButton(stringResource(R.string.sign_in), onSignIn)
                    return@Column
                }
                SoftTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    placeholder = stringResource(R.string.new_issue_title_placeholder),
                    // Not to be changed while it is on its way.
                    readOnly = state.isSending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                SoftTextField(
                    value = state.body,
                    onValueChange = onBodyChange,
                    placeholder = stringResource(R.string.new_issue_body_placeholder),
                    error = state.error?.let { error ->
                        val edits = state.editing != null
                        when {
                            error == ForgeError.Unauthorized -> stringResource(if (edits) R.string.edit_issue_error_expired else R.string.new_issue_error_expired, forge)
                            // 410 is GitHub's answer when a repository's issues are switched off.
                            error is ForgeError.Http && error.status in REFUSED -> stringResource(if (edits) R.string.edit_issue_error_refused else R.string.new_issue_error_refused)
                            error == ForgeError.Network -> stringResource(if (edits) R.string.edit_issue_error_offline else R.string.new_issue_error_offline)
                            else -> stringResource(if (edits) R.string.edit_issue_error else R.string.new_issue_error)
                        }
                    },
                    singleLine = false,
                    minLines = 6,
                    readOnly = state.isSending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                SoftButton(
                    stringResource(
                        when {
                            state.editing != null -> if (state.isSending) R.string.issue_comment_saving else R.string.edit_issue_send
                            state.isSending -> R.string.issue_comment_sending
                            else -> R.string.new_issue_send
                        },
                    ),
                    onSend,
                    Modifier.align(Alignment.End),
                    enabled = state.canSend,
                )
            }
            Spacer(Modifier.height(listBottomPadding()))
        }
        SoftStatusBarScrim(scroll.value > 0)
    }
}

private val REFUSED = setOf(403, 404, 410)
