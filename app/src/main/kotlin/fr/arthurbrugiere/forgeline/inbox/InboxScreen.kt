package fr.arthurbrugiere.forgeline.inbox

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen

@Composable
fun InboxScreen(modifier: Modifier = Modifier) {
    TopLevelScreen(title = stringResource(R.string.tab_inbox), modifier = modifier) { padding ->
        EmptyState(
            icon = Icons.Outlined.Inbox,
            title = stringResource(R.string.inbox_empty_title),
            body = stringResource(R.string.inbox_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
