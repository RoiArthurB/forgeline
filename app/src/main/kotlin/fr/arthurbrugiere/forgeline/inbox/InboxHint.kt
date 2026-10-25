package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Hint
import fr.arthurbrugiere.forgeline.core.model.SwipeAction
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.ui.LocalUserSettings

/**
 * A word of help above the Inbox's list, laid out like the notification ask: a tile, what to know, one button that
 * puts it away for good. The swipes are told as Settings has them.
 */
@Composable
fun InboxHintCard(hint: Hint, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val (icon, title, body) = when (hint) {
        Hint.INBOX_SWIPES -> Triple(Icons.Outlined.Swipe, stringResource(R.string.inbox_hint_swipes_title), swipesText())
        Hint.INBOX_PARTICIPATING -> Triple(
            Icons.Outlined.Forum,
            stringResource(R.string.inbox_hint_participating_title),
            stringResource(R.string.inbox_hint_participating_body),
        )
        Hint.INBOX_YOURS -> Triple(Icons.Outlined.AssignmentInd, stringResource(R.string.inbox_hint_yours_title), stringResource(R.string.inbox_hint_yours_body))
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp).background(colors.fields[0], SoftTokens.TileCorner), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = Soft.type.section, color = colors.ink)
                Text(body, style = Soft.type.secondary, color = colors.inkMuted)
            }
        }
        SoftTonalButton(stringResource(R.string.inbox_hint_dismiss), onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 12.dp))
    }
}

/** What each side's swipe does, whether a repository can be swiped away with its threads, and how to pick several. */
@Composable
private fun swipesText(): String {
    val settings = LocalUserSettings.current
    val right = when (settings.inboxSwipeRight) {
        SwipeAction.MARK_READ -> stringResource(R.string.inbox_hint_swipe_right_read)
        SwipeAction.DONE -> stringResource(R.string.inbox_hint_swipe_right_done)
        SwipeAction.NONE -> null
    }
    val left = when (settings.inboxSwipeLeft) {
        SwipeAction.MARK_READ -> stringResource(R.string.inbox_hint_swipe_left_read)
        SwipeAction.DONE -> stringResource(R.string.inbox_hint_swipe_left_done)
        SwipeAction.NONE -> null
    }
    // A repository's heading goes the way "done" goes for a thread, when one side means it.
    val repository = stringResource(R.string.inbox_hint_swipe_repository)
        .takeIf { settings.inboxSwipeRight == SwipeAction.DONE || settings.inboxSwipeLeft == SwipeAction.DONE }
    return listOfNotNull(right, left, repository, stringResource(R.string.inbox_hint_swipes_hold)).joinToString(" ")
}
