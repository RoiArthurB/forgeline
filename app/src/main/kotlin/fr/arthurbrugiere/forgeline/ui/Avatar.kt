package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Round avatar; shows the login's initial until (or unless) the image loads. The placeholder goes away once it
 * has, so it can't show through transparent pictures.
 */
@Composable
fun Avatar(url: String?, login: String, size: Dp, modifier: Modifier = Modifier) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier = modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                // Sized from the avatar (dp-based, so font scaling can't make it overflow the circle).
                val fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() }
                Text(
                    login.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, lineHeight = fontSize),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size), onSuccess = { loaded = true })
        }
    }
}
