package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.MarkEmailRead
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.InboxAction
import fr.arthurbrugiere.forgeline.core.model.NotificationThread
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIconButton
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftIndication
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens

/** The threads picked so far, and how a row picks itself or lets itself go. */
internal class Picking(val selected: Set<String>, val onToggle: (NotificationThread) -> Unit)

/**
 * What can be done to the threads picked, all at once: on the ink bar the Undo snackbar uses, in its place. Read and
 * unread share a button: read while any of them is unread, unread once they are all read.
 */
@Composable
internal fun SelectionBar(
    threads: List<NotificationThread>,
    onSelectAll: () -> Unit,
    onClear: () -> Unit,
    onAct: (InboxAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    val anyUnread = threads.any { it.unread }
    // On ink, the usual veil (ink over the ground) wouldn't show: here a press washes the ground's colour over it.
    CompositionLocalProvider(LocalIndication provides SoftIndication(colors.ground.copy(alpha = 0.14f))) {
        Row(
            modifier
                .widthIn(max = SoftTokens.MaxReadingWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.ink)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SoftIconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.inbox_selection_cancel), tint = colors.ground)
            }
            Text(
                pluralStringResource(R.plurals.inbox_selected, threads.size, threads.size),
                style = Soft.type.control,
                color = colors.ground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            SoftIconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.inbox_select_all), tint = colors.ground)
            }
            // What the picked threads become, said in words: a glyph alone had to be learned.
            BarAction(
                if (anyUnread) Icons.Outlined.MarkEmailRead else Icons.Outlined.MarkEmailUnread,
                stringResource(if (anyUnread) R.string.inbox_selection_read else R.string.inbox_selection_unread),
                stringResource(if (anyUnread) R.string.inbox_mark_read else R.string.inbox_mark_unread),
            ) { onAct(if (anyUnread) InboxAction.READ else InboxAction.UNREAD) }
            BarAction(Icons.Outlined.Done, stringResource(R.string.inbox_selection_done), stringResource(R.string.inbox_mark_done)) { onAct(InboxAction.DONE) }
        }
    }
}

/** An action of the selection bar: its glyph and its word on the ink bar, named in full for a screen reader. */
@Composable
private fun BarAction(icon: ImageVector, label: String, description: String, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .clip(SoftTokens.Pill)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .heightIn(min = 48.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.ground, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = Soft.type.control, color = colors.ground, maxLines = 1)
    }
}
