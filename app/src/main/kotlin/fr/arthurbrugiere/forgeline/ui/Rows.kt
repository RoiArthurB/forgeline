package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount

// List rows shared by the repo, profile and search screens.

@Composable
fun RepoSummaryRow(repo: RepoSummary, onOpenRepo: (RepoId) -> Unit) {
    ListItem(
        headlineContent = { Text(repo.id.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repo.description?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    repo.language?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(Icons.Outlined.StarBorder, contentDescription = null, modifier = Modifier.size(14.dp))
                        Text(compactCount(repo.stars), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        modifier = Modifier.clickable { onOpenRepo(repo.id) },
    )
    HorizontalDivider()
}

@Composable
fun IssueSummaryRow(issue: IssueSummary, nowMillis: Long, onOpen: (Int) -> Unit, repo: RepoId? = null) {
    ListItem(
        modifier = Modifier.clickable { onOpen(issue.number) },
        leadingContent = {
            Icon(
                if (issue.isPullRequest) Icons.AutoMirrored.Outlined.CallMerge else Icons.Outlined.Adjust,
                contentDescription = null,
                tint = if (issue.isDraft) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            )
        },
        // Search results come from anywhere, so they name their repo.
        overlineContent = repo?.let { { Text(it.fullName, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        headlineContent = { Text(issue.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.repo_issue_meta, issue.number, relative(issue.createdAt, nowMillis), issue.author?.login ?: "ghost"))
                if (issue.labels.isNotEmpty() || issue.isDraft) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (issue.isDraft) Badge(stringResource(R.string.repo_draft))
                        issue.labels.forEach { LabelChip(it) }
                    }
                }
            }
        },
        trailingContent = issue.comments?.takeIf { it > 0 }?.let { count ->
            {
                val description = pluralStringResource(R.plurals.repo_comments, count, count)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                ) {
                    Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("$count", style = MaterialTheme.typography.labelMedium)
                }
            }
        },
    )
    HorizontalDivider()
}
