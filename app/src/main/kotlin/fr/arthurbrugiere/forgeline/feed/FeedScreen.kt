package fr.arthurbrugiere.forgeline.feed

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.EmptyState
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen

@Composable
fun FeedScreen(session: SessionState, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    TopLevelScreen(title = stringResource(R.string.tab_feed), modifier = modifier) { padding ->
        when (session) {
            SessionState.Loading -> Unit
            SessionState.SignedOut -> EmptyState(
                icon = Icons.Outlined.DynamicFeed,
                title = stringResource(R.string.feed_signed_out_title),
                body = stringResource(R.string.feed_signed_out_body),
                actionLabel = stringResource(R.string.sign_in),
                onAction = onSignIn,
                modifier = Modifier.padding(padding),
            )
            is SessionState.SignedIn -> EmptyState(
                icon = Icons.Outlined.DynamicFeed,
                title = stringResource(R.string.feed_empty_title),
                body = stringResource(R.string.feed_empty_body),
                modifier = Modifier.padding(padding),
            )
        }
    }
}
