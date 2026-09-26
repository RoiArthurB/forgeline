package fr.arthurbrugiere.forgeline.feed

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen

@Composable
fun FeedScreen(modifier: Modifier = Modifier) {
    TopLevelScreen(title = stringResource(R.string.tab_feed), modifier = modifier) { padding ->
        EmptyState(
            icon = Icons.Outlined.DynamicFeed,
            title = stringResource(R.string.feed_empty_title),
            body = stringResource(R.string.feed_empty_body),
            modifier = Modifier.padding(padding),
        )
    }
}
