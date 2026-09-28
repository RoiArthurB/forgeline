package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Adjust
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.IssueSummary
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RepoSummary
import fr.arthurbrugiere.forgeline.core.ui.format.compactCount
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable

// List rows shared by the repo, profile and search screens: unboxed, with the soft pressed surface.

private val RowModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)

@Composable
fun RepoSummaryRow(repo: RepoSummary, onOpenRepo: (RepoId) -> Unit) {
    val colors = Soft.colors
    Column(RowModifier.softPressable { onOpenRepo(repo.id) }.padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = colors.inkMuted)) { append("${repo.id.owner}/") }
                append(repo.id.name)
            },
            style = Soft.type.control.copy(fontSize = Soft.type.body.fontSize, lineHeight = Soft.type.body.lineHeight),
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        repo.description?.let {
            Text(
                it,
                style = Soft.type.body,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).widthIn(max = SoftTokens.MaxMeasure),
            )
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            repo.language?.let { Text(it, style = Soft.type.meta, color = colors.inkMuted) }
            Text(stringResource(R.string.trending_stars, compactCount(repo.stars)), style = Soft.type.meta, color = colors.inkMuted)
        }
    }
}

@Composable
fun IssueSummaryRow(issue: IssueSummary, nowMillis: Long, onOpen: (Int) -> Unit, repo: RepoId? = null) {
    val colors = Soft.colors
    Row(RowModifier.softPressable { onOpen(issue.number) }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(36.dp).background(if (issue.isDraft) colors.surface else colors.fields[if (issue.isPullRequest) 2 else 0], CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (issue.isPullRequest) Icons.AutoMirrored.Outlined.CallMerge else Icons.Outlined.Adjust,
                contentDescription = null,
                tint = if (issue.isDraft) colors.inkMuted else colors.ink,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // Search results come from anywhere, so they name their repo.
            repo?.let { Text(it.fullName, style = Soft.type.meta, color = colors.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(issue.title, style = Soft.type.body, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.repo_issue_meta, issue.number, relative(issue.createdAt, nowMillis), issue.author?.login ?: "ghost"),
                style = Soft.type.meta,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (issue.labels.isNotEmpty() || issue.isDraft) {
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (issue.isDraft) Badge(stringResource(R.string.repo_draft))
                    issue.labels.forEach { LabelChip(it) }
                }
            }
        }
        issue.comments?.takeIf { it > 0 }?.let { count ->
            val description = pluralStringResource(R.plurals.repo_comments, count, count)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 8.dp, top = 2.dp).semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(16.dp))
                Text("$count", style = Soft.type.meta, color = colors.inkMuted)
            }
        }
    }
}
