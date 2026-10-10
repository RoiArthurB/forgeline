package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.file.FileTarget
import fr.arthurbrugiere.forgeline.navigation.BlameRoute
import fr.arthurbrugiere.forgeline.repo.Loadable
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun BlameRoute(route: BlameRoute, onBack: () -> Unit, onOpenCommit: (String) -> Unit, onSignIn: () -> Unit) {
    val target = FileTarget(route.repo, route.path, route.ref)
    val viewModel = hiltViewModel<BlameViewModel, BlameViewModel.Factory>(key = "blame-$route") { it.create(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BlameScreen(state, onBack = onBack, onRetry = viewModel::retry, onOpenCommit = onOpenCommit, onSignIn = onSignIn)
}

/**
 * A file read by who last changed it: each run of lines under the commit that left it as it is. A commit opens on
 * what it changed.
 */
@Composable
fun BlameScreen(
    state: BlameUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenCommit: (String) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    nowMillis: Long = rememberNow(state.blocks),
) {
    val colors = Soft.colors
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().sideSafeArea().navigationBarsPadding(),
        ) {
            item(key = "header") {
                SoftHeader(
                    tint = colors.fields[1],
                    title = stringResource(R.string.blame_title),
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    content = { Text("${state.target.path} · ${state.target.ref}", style = Soft.type.secondary, color = colors.inkMuted) },
                )
            }
            when (val blocks = state.blocks) {
                Loadable.Idle, Loadable.Loading -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.blame_loading), rows = 4) }
                // GitHub only tells signed-in people who last changed a line.
                is Loadable.Failed -> item(key = "error") {
                    if (blocks.error == ForgeError.Unauthorized) {
                        SoftNotice(stringResource(R.string.blame_signed_out_title), stringResource(R.string.blame_signed_out_body), action = stringResource(R.string.sign_in), onAction = onSignIn)
                    } else {
                        SoftNotice(stringResource(R.string.blame_error_title), stringResource(blocks.error.message), action = stringResource(R.string.retry), onAction = onRetry)
                    }
                }
                is Loadable.Loaded -> blocks.value.forEachIndexed { index, block ->
                    item(key = "block-$index", contentType = "commit") {
                        Column(
                            Modifier
                                .widthIn(max = SoftTokens.MaxReadingWidth)
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .background(colors.surface)
                                .softPressable(role = Role.Button) { onOpenCommit(block.commit.sha) }
                                .heightIn(min = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .semantics(mergeDescendants = true) { heading() },
                        ) {
                            Text(block.commit.title, style = Soft.type.control, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(block.commit.byline(nowMillis), style = Soft.type.meta, color = colors.inkMuted)
                        }
                    }
                    itemsIndexed(block.lines, key = { line, _ -> "block-$index-$line" }, contentType = { _, _ -> "line" }) { offset, line ->
                        Row(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(vertical = 1.dp)) {
                            Text(
                                "${block.startLine + offset}",
                                style = Soft.type.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 18.sp),
                                color = colors.inkMuted,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(40.dp),
                            )
                            Text(
                                line,
                                style = Soft.type.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
                                color = colors.ink,
                                modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
    }
}
