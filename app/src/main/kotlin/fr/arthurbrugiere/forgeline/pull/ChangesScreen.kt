package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.model.Commit
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.model.ReviewVerdict
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftStatusBarScrim
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.navigation.ChangesRoute
import fr.arthurbrugiere.forgeline.ui.SayOnce
import fr.arthurbrugiere.forgeline.ui.message
import fr.arthurbrugiere.forgeline.ui.relative
import fr.arthurbrugiere.forgeline.ui.rememberNow
import fr.arthurbrugiere.forgeline.ui.sideSafeArea

@Composable
fun ChangesRoute(route: ChangesRoute, onBack: () -> Unit) {
    val target = route.target
    val viewModel = hiltViewModel<ChangesViewModel, ChangesViewModel.Factory>(key = "changes-$route") { it.create(target) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChangesScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onErrorShown = viewModel::errorShown,
        onToggle = viewModel::toggle,
        review = ReviewActions(
            onStartComment = viewModel::startComment,
            onCancelComment = viewModel::cancelComment,
            onAddComment = viewModel::addComment,
            onRemoveComment = viewModel::removeComment,
            onOpen = viewModel::openReview,
            onClose = viewModel::closeReview,
            onSubmit = viewModel::submitReview,
            onSentShown = viewModel::reviewSentShown,
        ),
    )
}

/** What writing a review asks of the screen's owner. */
class ReviewActions(
    val onStartComment: (String, DiffLine) -> Unit = { _, _ -> },
    val onCancelComment: () -> Unit = {},
    val onAddComment: (String) -> Unit = {},
    val onRemoveComment: (LineComment) -> Unit = {},
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onSubmit: (ReviewVerdict, String) -> Unit = { _, _ -> },
    val onSentShown: () -> Unit = {},
)

/**
 * What a pull request or a commit changes: each file with its change, line by line. On a pull request, someone
 * signed in taps a line to remark on it, and sends the remarks with a review from the button at the bottom.
 */
@Composable
fun ChangesScreen(
    state: ChangesUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onErrorShown: () -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
    review: ReviewActions = ReviewActions(),
    nowMillis: Long = rememberNow(state.commit),
) {
    val colors = Soft.colors
    val files = state.files
    val snackbar = remember { SnackbarHostState() }
    SayOnce(stringResource(R.string.changes_more_failed).takeIf { state.error != null && files != null }, snackbar, onErrorShown)
    SayOnce(stringResource(R.string.review_sent).takeIf { state.reviewSent }, snackbar, review.onSentShown)
    state.drafting?.let { LineRemarkSheet(it, onAdd = review.onAddComment, onDismiss = review.onCancelComment) }
    if (state.isReviewOpen) {
        ReviewSheet(state.verdicts, state.comments.size, state.isSending, state.reviewError, onSubmit = review.onSubmit, onDismiss = review.onClose)
    }
    val listState = rememberLazyListState()
    Box(modifier.fillMaxSize().background(colors.ground)) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            // Room for the review button, so the last lines can be read above it.
            contentPadding = PaddingValues(bottom = if (state.canReview) 96.dp else 24.dp),
            modifier = Modifier.fillMaxSize().sideSafeArea().navigationBarsPadding(),
        ) {
            item(key = "header") {
                val target = state.target
                SoftHeader(
                    tint = colors.fields[1],
                    title = when (target) {
                        is ChangesTarget.Pull -> stringResource(R.string.changes_title)
                        is ChangesTarget.OfCommit -> state.commit?.title ?: target.sha.take(7)
                    },
                    onBack = onBack,
                    backDescription = stringResource(R.string.navigate_up),
                    content = {
                        when (target) {
                            is ChangesTarget.Pull -> Text(target.ref.label(), style = Soft.type.secondary, color = colors.inkMuted)
                            is ChangesTarget.OfCommit -> state.commit?.let { CommitFacts(it, nowMillis) }
                        }
                    },
                )
            }
            when {
                files == null && state.error != null -> item(key = "error") {
                    SoftNotice(stringResource(R.string.changes_error_title), stringResource(state.error.message), action = stringResource(R.string.retry), onAction = onRefresh)
                }
                files == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.changes_loading), rows = 4, leadingDot = false) }
                files.isEmpty() -> item(key = "empty") { SoftNotice(stringResource(R.string.changes_empty_title), stringResource(R.string.changes_empty_body)) }
                else -> {
                    item(key = "summary") {
                        val summary = stringResource(
                            R.string.changes_summary,
                            pluralStringResource(R.plurals.issue_pr_files, files.size, files.size),
                            files.sumOf { it.file.additions },
                            files.sumOf { it.file.deletions },
                        )
                        Text(
                            // Counted over the files read so far: said, when there are more to come.
                            if (state.nextPage != null) stringResource(R.string.changes_summary_more, summary) else summary,
                            style = Soft.type.secondary,
                            color = colors.inkMuted,
                            modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
                        )
                    }
                    fileDiffItems(
                        files,
                        isExpanded = state::isExpanded,
                        onToggle = onToggle,
                        onLineClick = review.onStartComment.takeIf { state.canReview },
                        comments = state.comments,
                        onRemoveComment = review.onRemoveComment,
                    )
                    if (state.nextPage != null) {
                        item(key = "more") {
                            // Coming in sight is the ask: there is no button to find at the end of a long change.
                            LaunchedEffect(state.nextPage) { onLoadMore() }
                            SoftLoadingRows(stringResource(R.string.changes_loading), rows = 1, leadingDot = false)
                        }
                    }
                }
            }
        }
        val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
        SoftStatusBarScrim(scrolled)
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            SnackbarHost(snackbar) { data -> Snackbar(data, shape = RoundedCornerShape(16.dp), containerColor = colors.ink, contentColor = colors.ground) }
            if (state.canReview && files != null) {
                val remarks = state.comments.size
                SoftButton(
                    if (remarks == 0) stringResource(R.string.review_start) else pluralStringResource(R.plurals.review_finish, remarks, remarks),
                    onClick = review.onOpen,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/** A conversation as its forge writes it: `owner/name#12`, or `!12` for a merge request where those are numbered apart. */
internal fun IssueRef.label(): String = "${repo.fullName}${if (repo.forge.type.numbersMergeRequestsApart && isPullRequest == true) "!" else "#"}$number"

/** Who wrote a commit, when and which one it is, then what its message says beyond its first line. */
@Composable
private fun CommitFacts(commit: Commit, nowMillis: Long) {
    val colors = Soft.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(commit.byline(nowMillis), style = Soft.type.secondary, color = colors.inkMuted)
        commit.description?.let { Text(it, style = Soft.type.secondary.copy(fontFamily = FontFamily.Monospace), color = colors.ink) }
    }
}

/** "octocat · 2 hours ago · 1a2b3c4". */
@Composable
internal fun Commit.byline(nowMillis: Long): String =
    listOfNotNull(author?.login ?: authorName, date?.let { relative(it, nowMillis) }, shortSha).joinToString(" · ")
