package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/** Round avatar; shows the login's initial until (or unless) the image loads. */
@Composable
fun Avatar(url: String?, login: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        // Sized from the avatar (dp-based, so font scaling can't make it overflow the circle).
        val fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() }
        Text(
            login.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, lineHeight = fontSize),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}
