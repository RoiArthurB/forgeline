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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import coil3.compose.AsyncImage

/**
 * Round avatar; shows the login's initial until (or unless) the image loads. The placeholder goes away once it
 * has, so it can't show through transparent pictures.
 */
@Composable
fun Avatar(
    url: String?,
    login: String,
    size: Dp,
    modifier: Modifier = Modifier,
    placeholderColor: Color = MaterialTheme.colorScheme.primaryContainer,
    placeholderContentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier = modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(Modifier.matchParentSize().background(placeholderColor), contentAlignment = Alignment.Center) {
                // Sized from the avatar (dp-based, so font scaling can't make it overflow the circle).
                val fontSize = with(LocalDensity.current) { (size * 0.45f).toSp() }
                Text(
                    login.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, lineHeight = fontSize),
                    color = placeholderContentColor,
                )
            }
        }
        if (url != null) {
            val pixels = with(LocalDensity.current) { size.roundToPx() }
            AsyncImage(model = remember(url, pixels) { sizedAvatarUrl(url, pixels) }, contentDescription = null, modifier = Modifier.size(size), onSuccess = { loaded = true })
        }
    }
}

private const val GITHUB_AVATARS = "https://avatars.githubusercontent.com/"

/** The sizes asked of GitHub: a few steps, so one download serves every avatar shown near that size. */
private val AVATAR_STEPS = listOf(48, 96, 144, 192, 288)

/** GitHub's own largest. */
private const val AVATAR_FULL = 460

/**
 * [url] asked at about [pixels] wide, where the forge can: GitHub sends 460 px unless told (`s=`), several times the
 * bytes a row's avatar needs. Other forges' avatars are left as they are (Codeberg refuses a size).
 */
internal fun sizedAvatarUrl(url: String, pixels: Int): String {
    if (!url.startsWith(GITHUB_AVATARS)) return url
    val query = url.substringAfter('?', "")
    if (query.split('&').any { it.startsWith("s=") || it.startsWith("size=") }) return url
    val size = AVATAR_STEPS.firstOrNull { it >= pixels } ?: AVATAR_FULL
    return url + (if (query.isEmpty() && '?' !in url) "?" else "&") + "s=$size"
}
