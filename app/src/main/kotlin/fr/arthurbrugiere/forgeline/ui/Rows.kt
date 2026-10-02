package fr.arthurbrugiere.forgeline.ui

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.ui.format.forgeInlineContent
import fr.arthurbrugiere.forgeline.core.ui.format.appendForge
import fr.arthurbrugiere.forgeline.core.ui.format.ForgeMark
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
import fr.arthurbrugiere.forgeline.core.model.IssueState
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.CheckCircleOutline
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import fr.arthurbrugiere.forgeline.core.ui.format.languageColor
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable

// List rows shared by the repo, profile and search screens: unboxed, with the soft pressed surface.

private val RowModifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)

/**
 * A repository in a list: its owner's avatar (a user or organisation; left out where every row shares the owner, as
 * on someone's own repositories), `owner/` muted and the name, a two-line description, then its language with the
 * linguist color and its stars.
 */
/** [forge] shows the repository's forge (its logo), for lists mixing several. */
@Composable
fun RepoSummaryRow(repo: RepoSummary, onOpenRepo: (RepoId) -> Unit, showOwner: Boolean = true, forge: ForgeInstance? = null) {
    val colors = Soft.colors
    Row(RowModifier.softPressable { onOpenRepo(repo.id) }.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        if (showOwner) {
            Avatar(
                repo.ownerAvatarUrl,
                repo.id.owner,
                size = 36.dp,
                placeholderColor = colors.surface,
                placeholderContentColor = colors.inkMuted,
                modifier = Modifier.clearAndSetSemantics {},
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
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
                repo.language?.let { language ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        languageColor(language)?.let { dot ->
                            Box(Modifier.size(9.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(language, style = Soft.type.meta, color = colors.inkMuted)
                    }
                }
                Text(stringResource(R.string.trending_stars, compactCount(repo.stars)), style = Soft.type.meta, color = colors.inkMuted)
                forge?.let { ForgeMark(it, style = Soft.type.meta, color = colors.inkMuted) }
            }
        }
    }
}

@Composable
fun IssueSummaryRow(
    issue: IssueSummary,
    nowMillis: Long,
    onOpen: (Int) -> Unit,
    repo: RepoId? = null,
    forge: ForgeInstance? = null,
    /** Pinned above its repository's list: it wears a pin instead of its state. */
    pinned: Boolean = false,
) {
    val colors = Soft.colors
    // Open ones wear their kind's color; a merged pull request the cool field; what was closed, or isn't ready, goes quiet.
    val quiet = issue.isDraft || issue.state == IssueState.CLOSED
    val badge = when {
        quiet -> colors.surface
        issue.state == IssueState.MERGED -> colors.fields[1]
        else -> colors.fields[if (issue.isPullRequest) 2 else 0]
    }
    val icon = when {
        pinned -> Icons.Outlined.PushPin
        issue.isPullRequest -> Icons.AutoMirrored.Outlined.CallMerge
        issue.state == IssueState.CLOSED -> Icons.Outlined.CheckCircleOutline
        else -> Icons.Outlined.Adjust
    }
    Row(RowModifier.softPressable { onOpen(issue.number) }.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).background(badge, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (quiet) colors.inkMuted else colors.ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            // Search results come from anywhere, so they name their repo.
            repo?.let {
                Text(
                    buildAnnotatedString {
                        forge?.let { forge -> appendForge(forge); append(" ") }
                        append(it.fullName)
                    },
                    inlineContent = forge?.let { forge -> forgeInlineContent(forge, colors.inkMuted) }.orEmpty(),
                    style = Soft.type.meta,
                    color = colors.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
