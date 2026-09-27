package fr.arthurbrugiere.forgeline.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen

@Composable
fun YouScreen(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenProfile: (String) -> Unit = {},
) {
    TopLevelScreen(title = stringResource(R.string.tab_you), modifier = modifier) { padding ->
        Column(Modifier.padding(padding)) {
            when (session) {
                SessionState.Loading -> Unit
                SessionState.SignedOut -> SignInCard(onSignIn)
                is SessionState.SignedIn -> AccountHeader(session.account) { onOpenProfile(session.account.user.login) }
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_title)) },
                leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }
    }
}

@Composable
private fun AccountHeader(account: Account, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Avatar(account.user.avatarUrl, account.user.login, size = 56.dp) },
        headlineContent = {
            Text(account.user.name ?: account.user.login, style = MaterialTheme.typography.titleLarge)
        },
        supportingContent = { Text("@${account.user.login} · ${account.forge.host}") },
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
    )
}

@Composable
private fun SignInCard(onSignIn: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.you_signed_out_title), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.you_signed_out_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
        }
    }
}
