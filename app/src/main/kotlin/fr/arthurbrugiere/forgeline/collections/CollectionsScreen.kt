package fr.arthurbrugiere.forgeline.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.data.collections.ImportReport
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSnackbarHost
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTextField
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.LocalBottomBarSpace
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import fr.arthurbrugiere.forgeline.you.NavigationRow

@Composable
fun CollectionsRoute(onBack: () -> Unit, onOpenCollection: (String) -> Unit, viewModel: CollectionsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A collection just made opens, to be filled.
    LaunchedEffect(viewModel) { viewModel.created.collect(onOpenCollection) }
    CollectionsScreen(
        state = state,
        onBack = onBack,
        onOpenCollection = onOpenCollection,
        onCreate = viewModel::create,
        onImport = viewModel::import,
        onImportShown = viewModel::importShown,
    )
}

/**
 * The reader's collections: lists of repositories from any forge, kept on the phone. Made here, filled from a
 * repository's page, or brought over from one's GitHub lists.
 */
@Composable
fun CollectionsScreen(
    state: CollectionsUiState,
    onBack: () -> Unit,
    onOpenCollection: (String) -> Unit,
    onCreate: (String) -> Unit,
    onImport: (Account) -> Unit,
    onImportShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val snackbar = remember { SnackbarHostState() }
    val importedText = state.imported?.let { importedMessage(it) }
    val failedText = state.importFailed?.let { stringResource(R.string.collections_import_failed, stringResource(it.message)) }
    LaunchedEffect(importedText, failedText) {
        val text = importedText ?: failedText ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        onImportShown()
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
                SoftHeader(onBack = onBack, backDescription = stringResource(R.string.navigate_up)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.collections_title), style = Soft.type.hero, color = Soft.colors.ink, modifier = Modifier.semantics { heading() })
                        Text(stringResource(R.string.collections_summary), style = Soft.type.secondary, color = Soft.colors.inkMuted)
                    }
                }
            }
            item(key = "new") { NewCollectionField(onCreate) }
            val collections = state.collections
            when {
                collections == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.collections_loading), rows = 3) }
                collections.isEmpty() -> item(key = "empty") {
                    SoftNotice(stringResource(R.string.collections_empty_title), stringResource(R.string.collections_empty_body), icon = Icons.Outlined.Bookmarks)
                }
                else -> items(collections, key = { it.id }) { collection ->
                    NavigationRow(
                        Icons.Outlined.Bookmarks,
                        collection.name,
                        { onOpenCollection(collection.id) },
                        summary = pluralStringResource(R.plurals.collections_count, collection.repoCount, collection.repoCount),
                    )
                }
            }
            if (state.importable.isNotEmpty()) {
                item(key = "import-title") { SoftSectionTitle(stringResource(R.string.collections_import_title)) }
                items(state.importable, key = { "import-${it.id}" }) { account ->
                    val importing = state.importing == account.id
                    NavigationRow(
                        Icons.Outlined.CloudDownload,
                        stringResource(if (importing) R.string.collections_importing else R.string.collections_import, account.forge.displayName),
                        { onImport(account) },
                        summary = stringResource(R.string.collections_import_summary, account.user.login),
                    )
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        SoftSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomBarSpace.current))
    }
}

@Composable
private fun importedMessage(report: ImportReport): String = when {
    report.lists == 0 -> stringResource(R.string.collections_imported_none)
    else -> pluralStringResource(R.plurals.collections_imported_lists, report.lists, report.lists) + " " +
        pluralStringResource(R.plurals.collections_imported_repos, report.added, report.added)
}

/** A name, then Done: the collection is made and opens. */
@Composable
private fun NewCollectionField(onCreate: (String) -> Unit) {
    val colors = Soft.colors
    var name by rememberSaveable { mutableStateOf("") }
    val create = {
        onCreate(name)
        name = ""
    }
    SoftTextField(
        value = name,
        onValueChange = { name = it },
        placeholder = stringResource(R.string.collections_new),
        background = colors.surface,
        trailing = if (name.isBlank()) {
            null
        } else {
            {
                SoftIconButton(onClick = create) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = stringResource(R.string.collections_create), tint = colors.ink)
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) create() }),
        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}
