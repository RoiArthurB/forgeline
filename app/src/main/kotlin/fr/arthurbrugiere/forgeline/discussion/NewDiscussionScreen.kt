package fr.arthurbrugiere.forgeline.discussion

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftChoiceRow
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.issue.PreviewToggle
import fr.arthurbrugiere.forgeline.issue.WritingPreview
import fr.arthurbrugiere.forgeline.navigation.NewDiscussionRoute
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.session.signedInOn
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.ui.staysAboveKeyboard

@Composable
fun NewDiscussionRoute(route: NewDiscussionRoute, session: SessionState, onBack: () -> Unit, onSignIn: () -> Unit, onCreated: (RepoId, Int) -> Unit) {
    val repo = route.repo
    val viewModel = hiltViewModel<NewDiscussionViewModel, NewDiscussionViewModel.Factory>(key = "new-discussion:${repo.key}") { it.create(repo) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The discussion started takes the form's place.
    LaunchedEffect(state.created) { state.created?.let { onCreated(repo, it) } }
    NewDiscussionScreen(
        state = state,
        signedIn = session.signedInOn(repo.forge),
        onPickCategory = viewModel::categoryPicked,
        onRetryCategories = viewModel::readCategories,
        onTitleChange = viewModel::titleChanged,
        onBodyChange = viewModel::bodyChanged,
        onSend = viewModel::send,
        onSignIn = onSignIn,
        onBack = onBack,
    )
}

/**
 * Starting a discussion, as an issue is opened: where it is filed, among the repository's categories (each saying
 * what it is for, and whether it takes an answer), its title, then its text, and one action. Signed out of the
 * repository's forge, it says so and offers to sign in. One that wasn't sent stays written.
 */
@Composable
fun NewDiscussionScreen(
    state: NewDiscussionUiState,
    signedIn: Boolean,
    onPickCategory: (String) -> Unit,
    onRetryCategories: () -> Unit,
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
            SoftHeader(title = stringResource(R.string.new_discussion_title), onBack = onBack, backDescription = stringResource(R.string.navigate_up)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(state.repo.fullName, style = Soft.type.secondary, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    ForgeMark(state.repo.forge, style = Soft.type.secondary, color = colors.inkMuted, size = 16.dp)
                }
            }
            Column(
                Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!signedIn) {
                    Text(stringResource(R.string.new_discussion_sign_in, forge), style = Soft.type.body, color = colors.inkMuted)
                    SoftTonalButton(stringResource(R.string.sign_in), onSignIn)
                    return@Column
                }
                Text(stringResource(R.string.new_discussion_category), style = Soft.type.label, color = colors.inkMuted, modifier = Modifier.semantics { heading() })
                val categories = state.categories
                when {
                    categories != null -> Column {
                        categories.forEach { category ->
                            SoftChoiceRow(
                                title = category.name,
                                selected = category.id == state.category,
                                onSelect = { onPickCategory(category.id) },
                                summary = listOfNotNull(category.description, stringResource(R.string.new_discussion_answerable).takeIf { category.isAnswerable }).joinToString(" · ").ifEmpty { null },
                            )
                        }
                    }
                    state.categoriesError != null -> {
                        Text(stringResource(R.string.new_discussion_categories_failed), style = Soft.type.secondary, color = colors.accent)
                        SoftTonalButton(stringResource(R.string.retry), onRetryCategories)
                    }
                    else -> SoftLoadingRows(stringResource(R.string.new_discussion_categories_loading), rows = 2)
                }
                SoftTextField(
                    value = state.title,
                    onValueChange = onTitleChange,
                    label = stringResource(R.string.new_issue_title_placeholder),
                    placeholder = "",
                    readOnly = state.isSending,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                var previewing by rememberSaveable { mutableStateOf(false) }
                val showPreview = previewing && state.body.isNotBlank()
                if (showPreview) WritingPreview(state.body, state.repo)
                val error = state.error?.let { discussionErrorText(it, forge) }
                if (showPreview && error != null) Text(error, style = Soft.type.secondary, color = colors.accent)
                // The text and the action: kept in sight together above the keyboard.
                Column(Modifier.fillMaxWidth().staysAboveKeyboard(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!showPreview) {
                        SoftTextField(
                            value = state.body,
                            onValueChange = onBodyChange,
                            label = stringResource(R.string.new_discussion_body_label),
                            placeholder = stringResource(R.string.new_issue_body_placeholder),
                            error = error,
                            singleLine = false,
                            minLines = 6,
                            readOnly = state.isSending,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (state.body.isNotBlank()) PreviewToggle(showPreview) { previewing = !showPreview }
                        SoftButton(stringResource(if (state.isSending) R.string.issue_comment_sending else R.string.new_discussion_send), onSend, enabled = state.canSend)
                    }
                }
            }
            Spacer(Modifier.height(listBottomPadding()))
        }
        val scrolled by remember { derivedStateOf { scroll.value > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}

/** Why a discussion wasn't started, in words that say what to do next. */
@Composable
private fun discussionErrorText(error: ForgeError, forge: String): String = when {
    error == ForgeError.Unauthorized -> stringResource(R.string.new_discussion_error_expired, forge)
    error is ForgeError.Http && error.status in setOf(403, 404, 422) -> stringResource(R.string.new_discussion_error_refused)
    error == ForgeError.Network -> stringResource(R.string.new_discussion_error_offline)
    else -> stringResource(R.string.new_discussion_error)
}
