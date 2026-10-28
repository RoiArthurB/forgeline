package fr.arthurbrugiere.forgeline.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftConfirmSheet
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.navigation.CollectionRoute
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.RepoSummaryRow
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import kotlinx.coroutines.launch

@Composable
fun CollectionRoute(route: CollectionRoute, onBack: () -> Unit, onOpenRepo: (RepoId) -> Unit) {
    val viewModel = hiltViewModel<CollectionViewModel, CollectionViewModel.Factory>(key = "collection/${route.id}") { it.create(route.id) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectionScreen(
        state = state,
        onBack = onBack,
        onOpenRepo = onOpenRepo,
        onRename = viewModel::rename,
        onRemove = viewModel::remove,
        onRestore = viewModel::restore,
        onDelete = {
            viewModel.delete()
            onBack()
        },
    )
}

/** One collection's repositories, the latest added first; Edit renames it, takes repositories out, or deletes it. */
@Composable
fun CollectionScreen(
    state: CollectionUiState,
    onBack: () -> Unit,
    onOpenRepo: (RepoId) -> Unit,
    onRename: (String) -> Unit,
    onRemove: (RepoId) -> Unit,
    onRestore: (RepoSummary) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removedText = stringResource(R.string.collection_removed)
    val undo = stringResource(R.string.inbox_undo)
    val repos = state.repos
    if (deleting) {
        SoftConfirmSheet(
            title = stringResource(R.string.collection_delete_title, state.name.orEmpty()),
            body = stringResource(R.string.collection_delete_body),
            confirm = stringResource(R.string.collection_delete),
            cancel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                deleting = false
                onDelete()
            },
            onDismiss = { deleting = false },
        )
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            modifier = Modifier.fillMaxSize().sideSafeArea(),
        ) {
            item(key = "header") {
                SoftHeader(
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    actions = {
                        if (state.name != null) {
                            SoftIconButton(onClick = { editing = !editing }) {
                                Icon(
                                    if (editing) Icons.Outlined.Check else Icons.Outlined.Edit,
                                    contentDescription = stringResource(if (editing) R.string.collection_done else R.string.collection_edit),
                                    tint = Soft.colors.ink,
                                )
                            }
                        }
                    },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.name.orEmpty(), style = Soft.type.hero, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                        repos?.let { Text(pluralStringResource(R.plurals.collections_count, it.size, it.size), style = Soft.type.secondary, color = Soft.colors.inkMuted) }
                    }
                }
            }
            if (editing && state.name != null) item(key = "rename") { RenameField(state.name, onRename) }
            when {
                repos == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.collections_loading), rows = 4) }
                repos.isEmpty() -> item(key = "empty") {
                    SoftNotice(stringResource(R.string.collection_empty_title), stringResource(R.string.collection_empty_body), icon = Icons.Outlined.BookmarkAdd)
                }
                editing -> items(repos, key = { "edit-${it.id.key}" }) { repo ->
                    EditRow(repo) {
                        onRemove(repo.id)
                        scope.launch {
                            if (snackbar.showSnackbar(removedText.format(repo.id.fullName), undo, duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) onRestore(repo)
                        }
                    }
                }
                else -> {
                    val showForge = repos.map { it.id.forge }.toSet().size > 1
                    items(repos, key = { it.id.key }) { repo -> RepoSummaryRow(repo, onOpenRepo, forge = repo.id.forge.takeIf { showForge }) }
                }
            }
            if (editing && state.name != null) {
                item(key = "delete") {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        SoftTonalButton(stringResource(R.string.collection_delete), { deleting = true })
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}

@Composable
private fun RenameField(current: String, onRename: (String) -> Unit) {
    var name by rememberSaveable(current) { mutableStateOf(current) }
    SoftTextField(
        value = name,
        onValueChange = { name = it },
        placeholder = stringResource(R.string.collection_name),
        label = stringResource(R.string.collection_name),
        background = Soft.colors.surface,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onRename(name) }),
        trailing = if (name.isBlank() || name.trim() == current) {
            null
        } else {
            {
                SoftIconButton(onClick = { onRename(name) }) {
                    Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.collection_rename), tint = Soft.colors.ink)
                }
            }
        },
        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** A repository while editing: its name and forge, and a way out of the collection. */
@Composable
private fun EditRow(repo: RepoSummary, onRemove: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(repo.id.fullName, style = Soft.type.body, color = colors.ink)
            ForgeMark(repo.id.forge, style = Soft.type.meta, color = colors.inkMuted)
        }
        SoftIconButton(onClick = onRemove) {
            Icon(Icons.Outlined.RemoveCircleOutline, contentDescription = stringResource(R.string.collection_remove, repo.id.fullName), tint = colors.inkMuted)
        }
    }
}
