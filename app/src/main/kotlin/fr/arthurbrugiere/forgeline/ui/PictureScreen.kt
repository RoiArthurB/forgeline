package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.navigation.PictureRoute

/** A picture from a README, a comment or a release's notes, on its own: to be looked at closely. */
@Composable
fun PictureScreen(route: PictureRoute, onBack: () -> Unit, onOpenInBrowser: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = Soft.colors
    val inBrowser = stringResource(R.string.open_in_browser)
    Column(modifier.fillMaxSize().background(colors.ground).sideSafeArea()) {
        SoftHeader(
            tint = colors.fields[1],
            onBack = onBack,
            backDescription = stringResource(R.string.navigate_up),
            actions = {
                IconButton(onClick = { onOpenInBrowser(route.url) }) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = inBrowser, tint = colors.ink)
                }
            },
        ) {
            Text(route.description?.takeIf { it.isNotBlank() } ?: route.fileName, style = Soft.type.name, color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = listBottomPadding())) {
            ZoomablePicture(route.url, route.description, onOpenInBrowser = { onOpenInBrowser(route.url) }, browserLabel = inBrowser)
        }
    }
}
