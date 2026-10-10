package fr.arthurbrugiere.forgeline.work

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.WorkKind
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftLoadingRows
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftSectionTitle
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.ui.IssueSummaryRow
import fr.arthurbrugiere.forgeline.ui.message

/**
 * What waits on the people signed in, across their accounts: the reviews asked of them first (someone else is
 * waiting), then their own open pull requests, then what is assigned to them. Listed in the Inbox, under its "Yours"
 * filter: it had a page of its own under the You tab, which read as a second inbox.
 */
internal fun LazyListScope.workItems(state: WorkUiState, nowMillis: Long, onRefresh: () -> Unit, onOpenIssue: (IssueRef) -> Unit) {
    val work = state.work
    when {
        work == null && state.error != null -> item(key = "error") {
            SoftNotice(
                stringResource(R.string.work_error_title),
                stringResource(state.error.message),
                action = stringResource(R.string.retry),
                onAction = onRefresh,
            )
        }
        work == null -> item(key = "loading") { SoftLoadingRows(stringResource(R.string.work_loading), rows = 4) }
        else -> {
            if (work.isEmpty && work.failed.isEmpty() && work.pending.isEmpty()) {
                item(key = "empty") { SoftNotice(stringResource(R.string.work_empty_title), stringResource(R.string.work_empty_body)) }
            }
            WorkKind.entries.forEach { kind ->
                val items = work.sections[kind].orEmpty()
                if (items.isEmpty()) return@forEach
                item(key = "title-$kind", contentType = "title") { SoftSectionTitle(stringResource(kind.title)) }
                // Keyed by position: one conversation can be under two headings.
                itemsIndexed(items, key = { index, _ -> "$kind-$index" }, contentType = { _, _ -> "row" }) { _, item ->
                    IssueSummaryRow(
                        item.issue,
                        nowMillis,
                        onOpen = { number -> onOpenIssue(IssueRef(item.repo, number, item.issue.isPullRequest)) },
                        repo = item.repo,
                        // Which forge, once work comes from more than one.
                        forge = item.repo.forge.takeIf { work.forges.size > 1 },
                    )
                }
            }
            // A forge still to answer is named: its work isn't missing, only on its way.
            if (work.pending.isNotEmpty()) {
                item(key = "pending") {
                    Text(
                        stringResource(R.string.work_pending, work.pending.joinToString { it.displayName }),
                        style = Soft.type.secondary,
                        color = Soft.colors.inkMuted,
                        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            }
            // A forge that couldn't be asked is said, so its silence isn't read as nothing to do.
            if (work.failed.isNotEmpty()) {
                item(key = "failed") {
                    Text(
                        stringResource(R.string.work_failed, work.failed.joinToString { it.displayName }),
                        style = Soft.type.secondary,
                        color = Soft.colors.inkMuted,
                        modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    )
                }
            }
        }
    }
}

private val WorkKind.title: Int
    get() = when (this) {
        WorkKind.REVIEW_REQUESTED -> R.string.work_reviews
        WorkKind.OWN_PULL_REQUESTS -> R.string.work_own
        WorkKind.ASSIGNED -> R.string.work_assigned
    }
