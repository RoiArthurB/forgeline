package fr.arthurbrugiere.forgeline.you

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.ui.TopLevelScreen

@Composable
fun YouScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    TopLevelScreen(title = stringResource(R.string.tab_you), modifier = modifier) { padding ->
        Column(Modifier.padding(padding)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_title)) },
                leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenSettings),
            )
        }
    }
}
