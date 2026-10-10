package fr.arthurbrugiere.forgeline.pull

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.DiffLine
import fr.arthurbrugiere.forgeline.core.model.DiffLineKind
import fr.arthurbrugiere.forgeline.core.model.FileChange
import fr.arthurbrugiere.forgeline.core.model.LineComment
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTag
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens

/**
 * The files of a change, one after the other: each a heading that folds it, then its hunks line by line. A line is
 * a row of the list itself, so a change of thousands of lines scrolls like any other list. [onLineClick] is what a
 * tap on a line does (a remark on it, when a review can be written); [comments] are the remarks waiting to be sent,
 * shown under their lines.
 */
internal fun LazyListScope.fileDiffItems(
    files: List<FileDiff>,
    isExpanded: (FileDiff) -> Boolean,
    onToggle: (String) -> Unit,
    onLineClick: ((String, DiffLine) -> Unit)? = null,
    comments: List<LineComment> = emptyList(),
    onRemoveComment: (LineComment) -> Unit = {},
) {
    files.forEachIndexed { index, diff ->
        val expanded = isExpanded(diff)
        item(key = "file-$index", contentType = "file") { FileHeading(diff, expanded, onToggle = { onToggle(diff.file.path) }) }
        if (!expanded) return@forEachIndexed
        if (diff.hunks.isEmpty()) {
            item(key = "file-$index-none", contentType = "none") {
                Text(
                    stringResource(R.string.changes_not_shown),
                    style = Soft.type.secondary,
                    color = Soft.colors.inkMuted,
                    modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        val remarks = comments.filter { it.path == diff.file.path }
        diff.hunks.forEachIndexed { hunkIndex, hunk ->
            item(key = "file-$index-hunk-$hunkIndex", contentType = "hunk") {
                Text(
                    hunk.header,
                    style = Soft.type.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color = Soft.colors.inkMuted,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth().background(Soft.colors.surface).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            itemsIndexed(hunk.lines, key = { lineIndex, _ -> "file-$index-hunk-$hunkIndex-line-$lineIndex" }, contentType = { _, _ -> "line" }) { _, line ->
                Column(Modifier.widthIn(max = SoftTokens.MaxReadingWidth).fillMaxWidth()) {
                    LineRow(line, onClick = onLineClick?.let { click -> { click(diff.file.path, line) } })
                    remarks.filter { it.oldLine == line.oldNumber && it.newLine == line.newNumber }.forEach { remark ->
                        PendingRemark(remark, onRemove = { onRemoveComment(remark) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FileHeading(diff: FileDiff, expanded: Boolean, onToggle: () -> Unit) {
    val colors = Soft.colors
    val file = diff.file
    val state = stringResource(if (expanded) R.string.changes_file_shown else R.string.changes_file_folded)
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.inkMuted, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.path, style = Soft.type.control.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), color = colors.ink)
            val note = when (file.change) {
                FileChange.ADDED -> stringResource(R.string.changes_file_added)
                FileChange.REMOVED -> stringResource(R.string.changes_file_removed)
                FileChange.RENAMED -> stringResource(R.string.changes_file_renamed, file.previousPath.orEmpty())
                FileChange.MODIFIED -> null
            }
            if (note != null) Text(note, style = Soft.type.meta, color = colors.inkMuted)
        }
        Spacer(Modifier.width(8.dp))
        // Read out in words: "+3 −1" alone says little to a screen reader.
        val counts = stringResource(R.string.changes_counts_spoken, file.additions, file.deletions)
        Row(Modifier.clearAndSetSemantics { contentDescription = counts }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (file.additions > 0) SoftTag("+${file.additions}", background = colors.fields[2])
            if (file.deletions > 0) SoftTag("−${file.deletions}", background = colors.fields[0])
        }
    }
}

/** One line of a change: its number before and after, then the line on the tint of what happened to it. */
@Composable
private fun LineRow(line: DiffLine, onClick: (() -> Unit)?) {
    val colors = Soft.colors
    val number = Soft.type.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 18.sp)
    val spoken = stringResource(
        when (line.kind) {
            DiffLineKind.ADDED -> R.string.changes_line_added
            DiffLineKind.REMOVED -> R.string.changes_line_removed
            DiffLineKind.CONTEXT -> R.string.changes_line_same
        },
        line.newNumber ?: line.oldNumber ?: 0,
        line.text,
    )
    val remark = stringResource(R.string.changes_remark_on_line)
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                when (line.kind) {
                    DiffLineKind.ADDED -> colors.fields[2]
                    DiffLineKind.REMOVED -> colors.fields[0]
                    DiffLineKind.CONTEXT -> colors.ground
                },
            )
            .then(if (onClick != null) Modifier.clickable(onClickLabel = remark, onClick = onClick) else Modifier)
            .padding(vertical = 1.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        Text(line.oldNumber?.toString().orEmpty(), style = number, color = colors.inkMuted, textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
        Text(line.newNumber?.toString().orEmpty(), style = number, color = colors.inkMuted, textAlign = TextAlign.End, modifier = Modifier.width(34.dp))
        Text(
            when (line.kind) {
                DiffLineKind.ADDED -> "+"
                DiffLineKind.REMOVED -> "−"
                DiffLineKind.CONTEXT -> ""
            },
            style = number.copy(fontSize = 12.sp),
            color = colors.ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(18.dp),
        )
        // Wrapped, not scrolled sideways: on a phone a long line is read by going down, not by losing the others.
        Text(
            line.text,
            style = Soft.type.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp),
            color = colors.ink,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
    }
}

/** A remark written on a line and not sent yet: it goes with the review. */
@Composable
private fun PendingRemark(remark: LineComment, onRemove: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(stringResource(R.string.changes_remark_pending), style = Soft.type.meta, color = colors.inkMuted)
            Text(remark.body, style = Soft.type.secondary, color = colors.ink)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.changes_remark_remove), tint = colors.inkMuted)
        }
    }
}
